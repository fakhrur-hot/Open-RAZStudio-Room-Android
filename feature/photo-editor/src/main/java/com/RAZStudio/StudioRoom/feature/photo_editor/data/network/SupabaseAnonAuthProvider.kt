/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * Manages the anonymous Supabase session (JWT + refresh token) for the
 * Online AI Editing feature.
 *
 * Session material is persisted exclusively via [EncryptedSharedPreferences]
 * (Jetpack Security) so that the JWT and refresh token survive across process
 * restarts without ever being written to plain-text storage.
 *
 * No Supabase secret/service-role key is stored here — only the per-user
 * anonymous JWT issued by Supabase's anonymous sign-in endpoint
 * (Requirement 4.3, Property 3: No APK-embedded secret).
 *
 * The JWT expiry check in [getValidJwt] is a client-side, proactive refresh
 * decision — it trusts the `exp` claim only for that purpose. The Cloudflare
 * Worker (not this class) is the actual JWT verifier for security purposes.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.data.network

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import javax.inject.Inject
import javax.inject.Singleton

// ── Supabase project config ──────────────────────────────────────────────────

/**
 * Supabase project endpoint + publishable ("anon") key.
 *
 * The publishable key is, by Supabase's own design, meant to be embedded in
 * client apps — it is the client-facing counterpart to the secret/service-
 * role key, which is never referenced here or anywhere else in this feature
 * (Requirement 4.3, Property 3: No APK-embedded secret). It authorizes only
 * anonymous-sign-in and token-refresh calls scoped by the project's Row Level
 * Security policies — it cannot perform privileged operations.
 */
private object SupabaseConfig {
    const val URL = "https://jxxzdmbvazxfbhkittlm.supabase.co"
    const val PUBLISHABLE_KEY = "sb_publishable_mhbkJ3si-NLzAbWzn2L1jA_WvIf24bS"
}

// ── Exception ─────────────────────────────────────────────────────────────────

/**
 * Thrown by [SupabaseAnonAuthProvider.getValidJwt] when the persisted JWT's
 * `exp` claim is in the past. Task 2.2 catches this to trigger the
 * token-refresh path before retrying the original request.
 */
class JwtExpiredException(message: String = "Stored JWT is expired") : Exception(message)

// ── Provider ──────────────────────────────────────────────────────────────────

/**
 * Singleton that owns the anonymous Supabase session for the Online AI
 * Editing feature.
 *
 * Responsibilities:
 * 1. Open (or lazily create) an [EncryptedSharedPreferences] file keyed by
 *    [PREFS_FILE], secured by a randomly-generated AES-256-GCM [MasterKey].
 * 2. Expose [ensureValidJwt] as the single entry point [OnlineAiEditClient]
 *    calls before every request — resolves to a valid JWT via cache hit,
 *    refresh, or silent re-anonymization, never surfacing a "session
 *    expired" state to the caller (Requirements 4.4, 4.5, 4.6).
 * 3. Expose [getValidJwt] (throws [JwtExpiredException] on expiry) and
 *    [persistSession]/[clearSession] as the lower-level primitives
 *    [ensureValidJwt] composes.
 * 4. Talk to Supabase's `/auth/v1/signup` (anonymous sign-in) and
 *    `/auth/v1/token?grant_type=refresh_token` endpoints directly using the
 *    project's publishable key — never the secret/service-role key
 *    (Requirement 4.3).
 */
@Singleton
class SupabaseAnonAuthProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val http: HttpClient,
) {

    // ── Constants ─────────────────────────────────────────────────────────────

    companion object {
        /** Name of the [EncryptedSharedPreferences] file on disk. */
        const val PREFS_FILE = "supabase_anon_session"

        /** Key under which the anonymous JWT is stored. */
        const val KEY_JWT = "jwt"

        /** Key under which the Supabase refresh token is stored. */
        const val KEY_REFRESH_TOKEN = "refresh_token"
    }

    // ── EncryptedSharedPreferences setup ──────────────────────────────────────

    /**
     * Lazily initialized encrypted prefs. Using [lazy] with the default
     * [LazyThreadSafetyMode.SYNCHRONIZED] to ensure the backing file is
     * opened at most once even under concurrent coroutine access at
     * app-startup time.
     */
    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns the cached JWT if it exists **and** its `exp` claim is in the
     * future; otherwise throws [JwtExpiredException].
     *
     * The expiry check decodes only the JWT's *payload* segment (middle
     * segment of the three-part `header.payload.signature` structure) with
     * standard Base64url decoding. No cryptographic verification is performed
     * here — the Worker is the actual verifier; this is a proactive,
     * client-side refresh trigger only.
     *
     * @throws JwtExpiredException when the stored JWT has expired or is absent.
     * @throws IllegalArgumentException when the stored string is not a
     *   well-formed three-part JWT (corrupt storage guard).
     */
    suspend fun getValidJwt(): String {
        val jwt = prefs.getString(KEY_JWT, null)
            ?: throw JwtExpiredException("No JWT stored — session has not been established yet")

        val expSeconds = extractExpClaim(jwt)
        val nowSeconds = System.currentTimeMillis() / 1000L

        if (expSeconds <= nowSeconds) {
            throw JwtExpiredException(
                "JWT expired at epoch $expSeconds (now $nowSeconds)"
            )
        }

        return jwt
    }

    /**
     * Returns the stored refresh token, or `null` if none is persisted.
     *
     * Used by task 2.2 to call Supabase's token-refresh endpoint when
     * [getValidJwt] throws [JwtExpiredException].
     */
    fun getRefreshToken(): String? = prefs.getString(KEY_REFRESH_TOKEN, null)

    /**
     * Returns a valid JWT, refreshing or silently re-establishing the
     * anonymous session as needed (Requirements 4.4, 4.5, 4.6).
     *
     * Resolution order:
     * 1. [getValidJwt] — if the stored JWT is unexpired, return it directly.
     * 2. On [JwtExpiredException], attempt one token refresh via the stored
     *    refresh token ([refreshSession]).
     * 3. If no refresh token exists, or the refresh call itself fails
     *    (refresh token expired/revoked), fall back to a fresh anonymous
     *    sign-in ([signInAnonymously]) — **without** re-showing the consent
     *    dialog, since consent is per-install, not per-session.
     *
     * This method never surfaces a "session expired" state to the caller —
     * either it returns a valid JWT, or it throws because the network itself
     * is unavailable (a genuine connectivity failure, which the caller
     * — [OnlineAiEditClient] — maps to [OnlineAiEditResult.Failure]).
     */
    suspend fun ensureValidJwt(): String {
        val current = try {
            getValidJwt()
        } catch (_: JwtExpiredException) {
            null
        }
        if (current != null) return current

        val refreshToken = getRefreshToken()
        if (refreshToken != null && refreshSession(refreshToken)) {
            return getValidJwt()
        }

        // Refresh token missing, expired, or revoked — start a fresh
        // anonymous session. Consent was already granted once for this
        // install (gated upstream, before this method is ever called), so no
        // dialog is re-shown here.
        clearSession()
        signInAnonymously()
        return getValidJwt()
    }

    /**
     * Calls Supabase's anonymous sign-up endpoint to establish a brand-new
     * anonymous session, persisting the resulting JWT + refresh token.
     *
     * @throws Exception (network/HTTP failure) propagated to the caller —
     *   [ensureValidJwt] does not swallow this, since a failure here means
     *   the feature genuinely cannot proceed without connectivity.
     */
    suspend fun signInAnonymously() {
        val response = http.post("${SupabaseConfig.URL}/auth/v1/signup") {
            header("apikey", SupabaseConfig.PUBLISHABLE_KEY)
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        require(response.status == HttpStatusCode.OK) {
            "Anonymous sign-in failed: HTTP ${response.status.value}"
        }
        val body = response.bodyAsText()
        persistSession(
            jwt = extractJsonStringField(body, "access_token")
                ?: error("Anonymous sign-in response missing access_token"),
            refreshToken = extractJsonStringField(body, "refresh_token")
                ?: error("Anonymous sign-in response missing refresh_token"),
        )
    }

    /**
     * Calls Supabase's refresh-token grant endpoint. Returns `true` and
     * persists the new session on success; returns `false` (does not throw)
     * on a non-2xx response, so [ensureValidJwt] can fall back to
     * [signInAnonymously] instead of surfacing a refresh-specific error.
     *
     * Genuine network exceptions (no connectivity at all) DO propagate —
     * only a request that reached Supabase and was rejected (expired/revoked
     * refresh token) is treated as a normal "fall back to re-anonymization"
     * case.
     */
    private suspend fun refreshSession(refreshToken: String): Boolean {
        val response = http.post("${SupabaseConfig.URL}/auth/v1/token?grant_type=refresh_token") {
            header("apikey", SupabaseConfig.PUBLISHABLE_KEY)
            contentType(ContentType.Application.Json)
            setBody("""{"refresh_token":"$refreshToken"}""")
        }
        if (response.status != HttpStatusCode.OK) return false

        val body = response.bodyAsText()
        val newJwt = extractJsonStringField(body, "access_token") ?: return false
        val newRefreshToken = extractJsonStringField(body, "refresh_token") ?: return false
        persistSession(newJwt, newRefreshToken)
        return true
    }

    /**
     * Persists a fresh [jwt] + [refreshToken] pair into [EncryptedSharedPreferences],
     * replacing any previous values atomically via [apply].
     *
     * Called from:
     * - Task 2.2 after a successful token-refresh response.
     * - Task 2.3 after a successful silent re-anonymization sign-in.
     *
     * @param jwt        The new anonymous JWT returned by Supabase.
     * @param refreshToken The new refresh token paired with [jwt].
     */
    fun persistSession(jwt: String, refreshToken: String) {
        prefs.edit()
            .putString(KEY_JWT, jwt)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .apply()
    }

    /**
     * Removes both the stored JWT and refresh token.
     *
     * Called by task 2.3 before a silent re-anonymization attempt, so that a
     * partially-corrupt or definitively-revoked session cannot be re-used.
     */
    fun clearSession() {
        prefs.edit()
            .remove(KEY_JWT)
            .remove(KEY_REFRESH_TOKEN)
            .apply()
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Extracts a single string-valued top-level field from a small, flat JSON
     * object without pulling in a JSON library — mirrors [extractExpClaim]'s
     * approach, since the shared Ktor `HttpClient` (`RemoteModule.client()`)
     * has no `ContentNegotiation`/JSON plugin installed, and adding one here
     * would affect every other consumer of that singleton.
     *
     * Only handles Supabase auth responses, which are small and flat
     * (`access_token`/`refresh_token` are always plain string values, never
     * nested or escaped in a way this simple pattern can't handle).
     *
     * @return the field's string value, or `null` if the field is absent.
     */
    private fun extractJsonStringField(json: String, field: String): String? =
        Regex(""""$field"\s*:\s*"([^"]*)"""").find(json)?.groupValues?.get(1)

    /**
     * Decodes the JWT payload segment and extracts the numeric `exp` Unix
     * timestamp claim.
     *
     * A standard JWT is `<header>.<payload>.<signature>` where each part is
     * Base64url-encoded. Only the middle segment is decoded here.
     *
     * @throws IllegalArgumentException if [jwt] does not contain exactly
     *   three dot-separated segments or the payload cannot be decoded.
     * @throws NumberFormatException if the `exp` field value is not parseable
     *   as a long.
     */
    private fun extractExpClaim(jwt: String): Long {
        val segments = jwt.split(".")
        require(segments.size == 3) {
            "Malformed JWT: expected 3 segments separated by '.', got ${segments.size}"
        }

        // JWT uses Base64url (RFC 4648 §5) — replace '-'/'_' before decoding
        // with Android's Base64 decoder which understands URL_SAFE mode.
        val payloadJson = String(
            Base64.decode(segments[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP),
            Charsets.UTF_8,
        )

        // Minimal JSON field extraction without pulling in a JSON library —
        // the payload is a small, flat JSON object and `exp` is always a plain
        // numeric value (never a string or nested object per JWT spec).
        //
        // Pattern: "exp":<digits>  (optional whitespace around the colon)
        val expMatch = Regex(""""exp"\s*:\s*(\d+)""").find(payloadJson)
            ?: throw IllegalArgumentException(
                "JWT payload does not contain an 'exp' claim: $payloadJson"
            )

        return expMatch.groupValues[1].toLong()
    }
}
