/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.RAZStudio.StudioRoom.feature.main.presentation.components

import android.util.Log
import com.RAZStudio.StudioRoom.feature.main.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

/**
 * Alpha-trial expiry gate (private RAZStudio Room).
 *
 * Each APK is allowed for **3 calendar months from that APK's assemble time**
 * ([BuildConfig.TRIAL_BUILD_EPOCH_MS]). After that, the main page hides every
 * entry button and shows the expired-trial background. Rebuilding produces a
 * new window; an old APK keeps the stamp baked into that binary.
 *
 * "Today" is resolved in two passes:
 *  1. **Online** — HTTP HEAD against a public endpoint, parse the RFC 1123
 *     `Date:` response header with the exact `EEE, dd MMM yyyy HH:mm:ss zzz`
 *     format. If the header is missing or the format doesn't match exactly,
 *     the result is treated as "no online date" — we do NOT relax the parser.
 *  2. **System** — fall back to the device clock when the network call fails
 *     or the format check rejects the response.
 *
 * The decision is cached for the process lifetime so we don't hit the network
 * on every recomposition.
 */
object TrialExpiry {

    private const val TAG = "TrialExpiry"

    private const val LIVE_MONTHS = 3

    /** Endpoints tried in order. First one that returns a parseable Date wins. */
    private val ONLINE_ENDPOINTS = listOf(
        "https://www.google.com/generate_204",
        "https://cloudflare.com/cdn-cgi/trace",
        "https://www.cloudflare.com/",
    )

    /** Exact RFC 1123 format Date headers MUST match. No fallbacks. */
    private const val RFC_1123 = "EEE, dd MMM yyyy HH:mm:ss zzz"

    @Volatile private var cachedDecision: Boolean? = null

    /**
     * Returns `true` when this APK's 3-month live window has not ended.
     * Suspends on first call while the online probe runs (timeout ~3s). Subsequent
     * calls within the same process return the cached decision instantly.
     */
    suspend fun isActive(): Boolean {
        if (!com.RAZStudio.StudioRoom.core.ui.edition.EditionCapabilities.privateTrial) {
            cachedDecision = true
            return true
        }
        cachedDecision?.let { return it }
        val today = resolveToday()
        val cutoff = expiryDate()
        val active = !today.after(cutoff)
        cachedDecision = active
        Log.i(TAG,
            "trial decision active=$active today=${today} cutoff=${cutoff}")
        return active
    }

    /** Synchronous variant for places that can't suspend. Uses cache or system clock. */
    fun isActiveBlocking(): Boolean {
        if (!com.RAZStudio.StudioRoom.core.ui.edition.EditionCapabilities.privateTrial) {
            return true
        }
        cachedDecision?.let { return it }
        val today = systemToday()
        val active = !today.after(expiryDate())
        // Do NOT cache here — the online probe may give a different answer
        // later. We only return a best-effort interim value.
        return active
    }

    private suspend fun resolveToday(): Date {
        val online = withTimeoutOrNull(3_000L) {
            withContext(Dispatchers.IO) { fetchOnlineDate() }
        }
        return online ?: systemToday().also {
            Log.i(TAG, "online date unavailable — falling back to system clock")
        }
    }

    private fun fetchOnlineDate(): Date? {
        for (endpoint in ONLINE_ENDPOINTS) {
            val date = runCatching { probe(endpoint) }.getOrNull()
            if (date != null) {
                Log.i(TAG, "online date from $endpoint = $date")
                return date
            }
        }
        return null
    }

    private fun probe(endpoint: String): Date? {
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod   = "HEAD"
            connectTimeout  = 2_000
            readTimeout     = 2_000
            instanceFollowRedirects = true
            setRequestProperty("Cache-Control", "no-cache")
        }
        try {
            conn.connect()
            val header = conn.getHeaderField("Date") ?: return null
            return parseStrict(header)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Strict RFC 1123 parse. The input MUST match the exact format down to
     * casing of the weekday/month abbreviations. SimpleDateFormat in lenient
     * mode would happily accept "1 Jan 26 0:0:0 GMT" — we reject everything
     * that isn't byte-for-byte canonical.
     */
    private fun parseStrict(header: String): Date? {
        val fmt = SimpleDateFormat(RFC_1123, Locale.US).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("GMT")
        }
        val parsed = runCatching { fmt.parse(header) }.getOrNull() ?: return null
        // Round-trip: reformat and require exact equality to the original.
        // SimpleDateFormat's parser tolerates trailing garbage; the round-trip
        // check rejects anything that isn't the canonical form.
        val reformatted = fmt.format(parsed)
        return if (reformatted == header.trim()) parsed else null
    }

    private fun systemToday(): Date = Date()

    private fun expiryDate(): Date = GregorianCalendar(
        TimeZone.getTimeZone("GMT"),
    ).apply {
        timeInMillis = BuildConfig.TRIAL_BUILD_EPOCH_MS
        add(Calendar.MONTH, LIVE_MONTHS)
        set(Calendar.HOUR_OF_DAY, 23)
        set(Calendar.MINUTE, 59)
        set(Calendar.SECOND, 59)
        set(Calendar.MILLISECOND, 999)
    }.time
}
