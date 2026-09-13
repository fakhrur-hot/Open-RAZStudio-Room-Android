/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.RAZStudio.StudioRoom.feature.canon_sync.domain.models

/**
 * One row per Canon device detected on the local Wi-Fi via ARP +
 * SSDP. Surfaced to the UI so the user can pick which camera to
 * connect to when the AP hosts more than one. Refreshed every
 * 30 s while the phone hotspot is active and we're not already
 * connected.
 *
 * `friendlyName` populates from SSDP advertisement when available;
 * falls back to a generic "Canon camera" otherwise.
 */
data class DetectedCamera(
    val ip: String,
    val mac: String,
    val friendlyName: String,
    val seenAtMs: Long,
)
