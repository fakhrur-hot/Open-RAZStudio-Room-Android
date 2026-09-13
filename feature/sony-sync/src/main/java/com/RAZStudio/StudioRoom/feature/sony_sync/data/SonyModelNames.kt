/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 */

package com.RAZStudio.StudioRoom.feature.sony_sync.data

/**
 * Maps Sony's internal EXIF/USB product IDs (e.g. `ILCE-7M2`) to the marketing
 * names people actually recognise (`α7 II`). Used for the Camera Remote header,
 * the auto-created Gallery project name, and logs — so the UI reads "Sony α7 II"
 * instead of "ILCE-7M2".
 *
 * The returned name is WITHOUT the "Sony" prefix (callers that want it prepend
 * their own, e.g. the "Sony <name> <date>" project name), and any model not in
 * the table falls back to its raw ID unchanged, so a new body still shows
 * *something* correct rather than a wrong guess.
 */
object SonyModelNames {

    // ILCE = interchangeable-lens α (E-mount); DSC = Cyber-shot; ILCA = α (A-mount).
    // Kept small and factual — extend as bodies are confirmed. α is U+03B1.
    private val TABLE: Map<String, String> = mapOf(
        // α7 line
        "ILCE-7" to "α7",
        "ILCE-7M2" to "α7 II",
        "ILCE-7M3" to "α7 III",
        "ILCE-7M4" to "α7 IV",
        "ILCE-7R" to "α7R",
        "ILCE-7RM2" to "α7R II",
        "ILCE-7RM3" to "α7R III",
        "ILCE-7RM4" to "α7R IV",
        "ILCE-7RM5" to "α7R V",
        "ILCE-7S" to "α7S",
        "ILCE-7SM2" to "α7S II",
        "ILCE-7SM3" to "α7S III",
        "ILCE-7C" to "α7C",
        "ILCE-7CM2" to "α7C II",
        // α9 / α1
        "ILCE-9" to "α9",
        "ILCE-9M2" to "α9 II",
        "ILCE-9M3" to "α9 III",
        "ILCE-1" to "α1",
        "ILCE-1M2" to "α1 II",
        // APS-C α6x00 / ZV
        "ILCE-6000" to "α6000",
        "ILCE-6100" to "α6100",
        "ILCE-6300" to "α6300",
        "ILCE-6400" to "α6400",
        "ILCE-6500" to "α6500",
        "ILCE-6600" to "α6600",
        "ILCE-6700" to "α6700",
        "ILCE-5100" to "α5100",
        "ZV-E10" to "ZV-E10",
        "ZV-E10M2" to "ZV-E10 II",
        "ZV-E1" to "ZV-E1",
    )

    /**
     * Pretty display name for a raw model ID. Trims, is case-insensitive on the
     * key, and returns the raw (trimmed) value unchanged when unknown. Blank /
     * null input yields "Camera".
     */
    fun pretty(raw: String?): String {
        val id = raw?.trim().orEmpty()
        if (id.isEmpty()) return "Camera"
        return TABLE[id] ?: TABLE[id.uppercase()] ?: id
    }
}
