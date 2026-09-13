/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */

package com.RAZStudio.StudioRoom.core.settings.domain

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistence for the one-time Online AI Editing consent dialog.
 *
 * Lives in `core/settings` (not `feature/photo-editor`) following the same
 * cross-module-sharing rationale as [RawBatchPrefs] — consent is a per-
 * install fact, not a per-photo-editor-session one, so it belongs alongside
 * other durable settings rather than in the feature module that happens to
 * be the first (and, for now, only) consumer.
 *
 * `hasSeenOnlineAiConsent` gates the Consent_Dialog shown before the first
 * anonymous Supabase session is ever established (Requirement 7.4) — once
 * `true`, the dialog is never shown again for this install.
 */
class OnlineAiConsentPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var hasSeenOnlineAiConsent: Boolean
        get() = prefs.getBoolean(KEY_HAS_SEEN_CONSENT, false)
        set(value) = prefs.edit().putBoolean(KEY_HAS_SEEN_CONSENT, value).apply()

    companion object {
        private const val PREFS_NAME = "online_ai_consent_prefs"
        private const val KEY_HAS_SEEN_CONSENT = "has_seen_online_ai_consent"
    }
}
