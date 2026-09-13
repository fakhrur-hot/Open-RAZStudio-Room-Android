/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 */

package com.RAZStudio.StudioRoom.feature.sony_sync.data.cloud

import org.json.JSONObject
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Google Drive [CloudProvider] via the REST API v3, authenticated with a
 * pasted OAuth2 access token (Bearer). Zero SDK deps — plain java.net, matching
 * this module's thin style. A full in-app OAuth login can replace
 * [setAccessToken] later without touching callers.
 *
 * Scope needed on the token: `https://www.googleapis.com/auth/drive.file`
 * (create + manage files/folders this app makes) — enough for create folder,
 * share, and upload.
 */
class GoogleDriveProvider : CloudProvider {
    override val displayName = "Google Drive"
    private var token: String = ""

    override fun isConnected() = token.isNotBlank()
    override fun setAccessToken(token: String) { this.token = token.trim() }

    override fun verify(): CloudResult<String> {
        if (token.isBlank()) return CloudResult.Err("No access token.")
        return request("GET", "https://www.googleapis.com/drive/v3/about?fields=user")
            .map { JSONObject(it).optJSONObject("user")?.optString("emailAddress").orEmpty().ifBlank { "connected" } }
    }

    override fun createFolder(name: String): CloudResult<CloudFolder> {
        val body = JSONObject()
            .put("name", name)
            .put("mimeType", "application/vnd.google-apps.folder")
            .toString()
        return request(
            "POST", "https://www.googleapis.com/drive/v3/files?fields=id,name",
            contentType = "application/json; charset=UTF-8", body = body.toByteArray(),
        ).map { CloudFolder(id = JSONObject(it).getString("id"), name = JSONObject(it).optString("name", name)) }
    }

    override fun makeFolderPublic(folderId: String): CloudResult<String> {
        val body = JSONObject().put("role", "reader").put("type", "anyone").toString()
        val perm = request(
            "POST", "https://www.googleapis.com/drive/v3/files/$folderId/permissions",
            contentType = "application/json; charset=UTF-8", body = body.toByteArray(),
        )
        if (perm is CloudResult.Err) return perm
        // The canonical shareable folder link.
        return CloudResult.Ok("https://drive.google.com/drive/folders/$folderId?usp=sharing")
    }

    override fun uploadJpeg(folderId: String, fileName: String, bytes: ByteArray): CloudResult<String> {
        // Multipart/related upload: metadata JSON part + JPEG media part.
        val boundary = "razb" + System.nanoTime().toString(16)
        val meta = JSONObject().put("name", fileName).put("parents", org.json.JSONArray().put(folderId)).toString()
        val out = java.io.ByteArrayOutputStream()
        fun w(s: String) = out.write(s.toByteArray())
        w("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n")
        w("--$boundary\r\nContent-Type: image/jpeg\r\n\r\n")
        out.write(bytes)
        w("\r\n--$boundary--\r\n")
        return request(
            "POST", "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id",
            contentType = "multipart/related; boundary=$boundary", body = out.toByteArray(),
        ).map { JSONObject(it).getString("id") }
    }

    override fun parseFolderId(link: String): String? {
        val t = link.trim()
        Regex("/folders/([A-Za-z0-9_-]+)").find(t)?.let { return it.groupValues[1] }
        Regex("[?&]id=([A-Za-z0-9_-]+)").find(t)?.let { return it.groupValues[1] }
        if (t.matches(Regex("[A-Za-z0-9_-]{20,}"))) return t   // bare id pasted
        return null
    }

    override fun checkFolder(folderId: String): CloudResult<FolderInfo> = request(
        "GET",
        "https://www.googleapis.com/drive/v3/files/$folderId" +
            "?fields=id,name,mimeType,capabilities(canAddChildren)&supportsAllDrives=true",
    ).map {
        val j = JSONObject(it)
        FolderInfo(
            name = j.optString("name", ""),
            isFolder = j.optString("mimeType") == "application/vnd.google-apps.folder",
            canWrite = j.optJSONObject("capabilities")?.optBoolean("canAddChildren", false) ?: false,
        )
    }

    // ── HTTP plumbing ────────────────────────────────────────────────────────
    private fun request(
        method: String,
        url: String,
        contentType: String? = null,
        body: ByteArray? = null,
    ): CloudResult<String> {
        return runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15000
                readTimeout = 30000
                setRequestProperty("Authorization", "Bearer $token")
                if (contentType != null) setRequestProperty("Content-Type", contentType)
                if (body != null) { doOutput = true; (outputStream as OutputStream).use { it.write(body) } }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()
            if (code in 200..299) CloudResult.Ok(text)
            else CloudResult.Err(errorMessage(code, text))
        }.getOrElse { CloudResult.Err(it.message ?: "Network error") }
    }

    private fun errorMessage(code: Int, text: String): String {
        val msg = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull()
        return "HTTP $code${msg?.let { ": $it" } ?: ""}" +
            if (code == 401) " (token expired — paste a fresh one)" else ""
    }

    private inline fun <T> CloudResult<String>.map(f: (String) -> T): CloudResult<T> = when (this) {
        is CloudResult.Ok -> runCatching { CloudResult.Ok(f(value)) }.getOrElse { CloudResult.Err(it.message ?: "Parse error") }
        is CloudResult.Err -> this
    }
}
