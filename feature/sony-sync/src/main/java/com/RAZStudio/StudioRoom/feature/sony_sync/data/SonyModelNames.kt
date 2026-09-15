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
 * Also provides [canonicalForLensfun]: camera-and-lens-profile / LibRaw profiles
 * key off the ILCE-/ILCA-/ILME-/DSC-/ZV- product string. Export EXIF Model should
 * stay canonical so profile correction keeps matching after a round trip.
 * Pretty names are UI-only.
 *
 * The returned pretty name is WITHOUT the "Sony" prefix (callers that want it
 * prepend their own). Unknown models fall back to the raw ID unchanged.
 */
object SonyModelNames {

    // ILCE = interchangeable-lens α (E-mount); DSC = Cyber-shot; ILCA = α (A-mount);
    // ILME = Cinema Line. α is U+03B1.
    private val TABLE: Map<String, String> = mapOf(
        "ILCE-7" to "α7",
        "ILCE-7M2" to "α7 II",
        "ILCE-7M3" to "α7 III",
        "ILCE-7M3A" to "α7 IIIA",
        "ILCE-7M4" to "α7 IV",
        "ILCE-7R" to "α7R",
        "ILCE-7RM2" to "α7R II",
        "ILCE-7RM3" to "α7R III",
        "ILCE-7RM3A" to "α7R IIIA",
        "ILCE-7RM4" to "α7R IV",
        "ILCE-7RM4A" to "α7R IVA",
        "ILCE-7RM5" to "α7R V",
        "ILCE-7S" to "α7S",
        "ILCE-7SM2" to "α7S II",
        "ILCE-7SM3" to "α7S III",
        "ILCE-7C" to "α7C",
        "ILCE-7CM2" to "α7C II",
        "ILCE-7CR" to "α7CR",
        "ILCE-9" to "α9",
        "ILCE-9M2" to "α9 II",
        "ILCE-9M3" to "α9 III",
        "ILCE-1" to "α1",
        "ILCE-1M2" to "α1 II",
        "ILCE-6000" to "α6000",
        "ILCE-6100" to "α6100",
        "ILCE-6300" to "α6300",
        "ILCE-6400" to "α6400",
        "ILCE-6500" to "α6500",
        "ILCE-6600" to "α6600",
        "ILCE-6700" to "α6700",
        "ILCE-5100" to "α5100",
        "ILCE-3000" to "α3000",
        "ILCE-3500" to "α3500",
        "ILCE-5000" to "α5000",
        "ZV-E10" to "ZV-E10",
        "ZV-E10M2" to "ZV-E10 II",
        "ZV-E1" to "ZV-E1",
        "ILME-FX3" to "FX3",
        "ILME-FX30" to "FX30",
        "ILME-FX2" to "FX2",
        "ILME-FX6V" to "FX6",
        "ILME-FX6VK" to "FX6",
        "ILCA-68" to "α68",
        "ILCA-77M2" to "α77 II",
        "ILCA-99M2" to "α99 II",
        "SLT-A58" to "α58",
        "SLT-A77V" to "α77",
        "SLT-A99V" to "α99",
        "NEX-3" to "NEX-3",
        "NEX-3N" to "NEX-3N",
        "NEX-5" to "NEX-5",
        "NEX-5N" to "NEX-5N",
        "NEX-5R" to "NEX-5R",
        "NEX-5T" to "NEX-5T",
        "NEX-6" to "NEX-6",
        "NEX-7" to "NEX-7",
        "NEX-F3" to "NEX-F3",
        "DSC-RX100" to "RX100",
        "DSC-RX100M2" to "RX100 II",
        "DSC-RX100M3" to "RX100 III",
        "DSC-RX100M4" to "RX100 IV",
        "DSC-RX100M5" to "RX100 V",
        "DSC-RX100M5A" to "RX100 VA",
        "DSC-RX100M6" to "RX100 VI",
        "DSC-RX100M7" to "RX100 VII",
        "DSC-RX10" to "RX10",
        "DSC-RX10M2" to "RX10 II",
        "DSC-RX10M3" to "RX10 III",
        "DSC-RX10M4" to "RX10 IV",
        "DSC-RX1" to "RX1",
        "DSC-RX1R" to "RX1R",
        "DSC-RX1RM2" to "RX1R II",
        "DSC-RX0" to "RX0",
        "DSC-RX0M2" to "RX0 II",
        "DSC-HX90" to "HX90",
        "DSC-HX90V" to "HX90V",
        "DSC-HX400V" to "HX400V",
        "DSC-WX500" to "WX500",
        "ZV-1" to "ZV-1",
        "ZV-1M2" to "ZV-1 II",
        "ZV-1F" to "ZV-1F",
    )

    /** Pretty → canonical reverse index (case-insensitive on lookup). */
    private val PRETTY_TO_ID: Map<String, String> =
        TABLE.entries.associate { (id, pretty) -> pretty.lowercase() to id } + mapOf(
            "a7" to "ILCE-7",
            "a7 ii" to "ILCE-7M2",
            "a7iii" to "ILCE-7M3",
            "a7 iii" to "ILCE-7M3",
            "a7 iv" to "ILCE-7M4",
            "a7r" to "ILCE-7R",
            "a7r ii" to "ILCE-7RM2",
            "a7r iii" to "ILCE-7RM3",
            "a7r iv" to "ILCE-7RM4",
            "a7r v" to "ILCE-7RM5",
            "a7s" to "ILCE-7S",
            "a7s ii" to "ILCE-7SM2",
            "a7s iii" to "ILCE-7SM3",
            "a7c" to "ILCE-7C",
            "a7c ii" to "ILCE-7CM2",
            "a7cr" to "ILCE-7CR",
            "a9" to "ILCE-9",
            "a9 ii" to "ILCE-9M2",
            "a9 iii" to "ILCE-9M3",
            "a1" to "ILCE-1",
            "a1 ii" to "ILCE-1M2",
            "alpha 7 ii" to "ILCE-7M2",
            "alpha 7 iii" to "ILCE-7M3",
            "alpha 7 iv" to "ILCE-7M4",
            "fx3" to "ILME-FX3",
            "fx30" to "ILME-FX30",
            "fx2" to "ILME-FX2",
        )

    private val CANONICAL_PREFIXES = listOf(
        "ILCE-", "ILCA-", "ILME-", "DSC-", "ZV-", "NEX-", "SLT-", "DSLR-",
    )

    /**
     * Pretty display name for a raw model ID. Trims, is case-insensitive on the
     * key, and returns the raw (trimmed) value unchanged when unknown. Blank /
     * null input yields "Camera".
     */
    fun pretty(raw: String?): String {
        val id = normalizeKey(raw) ?: return "Camera"
        return TABLE[id] ?: TABLE[id.uppercase()] ?: stripSonyPrefix(raw!!.trim()).ifEmpty { id }
    }

    /**
     * Camera-and-lens-profile / LibRaw database key. Strips a leading "Sony "
     * maker prefix, maps marketing / alias names back to ILCE-… product IDs, and
     * leaves already-canonical IDs unchanged. Blank / null → "".
     */
    fun canonicalForLensfun(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return ""
        val stripped = stripSonyPrefix(trimmed)
        val key = stripped.uppercase()
        if (CANONICAL_PREFIXES.any { key.startsWith(it) }) {
            return TABLE.keys.firstOrNull { it.equals(stripped, ignoreCase = true) } ?: stripped
        }
        return PRETTY_TO_ID[stripped.lowercase()]
            ?: PRETTY_TO_ID[key.lowercase()]
            ?: stripped
    }

    /** Software EXIF value that keeps app identity + optional camera firmware. */
    fun softwareTag(firmwareVersion: String? = null): String {
        val fw = firmwareVersion?.trim().orEmpty()
        return if (fw.isEmpty()) "RAZStudio Room" else "RAZStudio Room | FW $fw"
    }

    private fun normalizeKey(raw: String?): String? {
        val t = raw?.trim().orEmpty()
        if (t.isEmpty()) return null
        return stripSonyPrefix(t).uppercase()
    }

    private fun stripSonyPrefix(s: String): String =
        if (s.startsWith("Sony ", ignoreCase = true)) s.substring(5).trim() else s
}
