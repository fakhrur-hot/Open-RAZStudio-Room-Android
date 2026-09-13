/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log

/**
 * Looped MediaPlayer for the batch screensaver. Picks a RANDOM bundled track
 * from `res/raw` on each start. Safe no-op if none are bundled.
 *
 * Failure-mode visibility
 * -----------------------
 * The previous implementation set only an `OnPreparedListener` and called
 * `prepareAsync()`. Any prepare or playback error was logged by the
 * framework but not surfaced anywhere our code could see — the
 * symptom on the user side was "screensaver shows but no music" with
 * no debugging trail.
 *
 * This version wires the full listener set: `OnPreparedListener`,
 * `OnErrorListener`, `OnCompletionListener`. Every failure path
 * logs to the `BatchAudioPlayer` tag with the framework's `what` and
 * `extra` codes so we can identify the root cause via logcat:
 *   what:  MEDIA_ERROR_UNKNOWN(1) / MEDIA_ERROR_SERVER_DIED(100)
 *   extra: MEDIA_ERROR_IO(-1004) / MEDIA_ERROR_MALFORMED(-1007) /
 *          MEDIA_ERROR_UNSUPPORTED(-1010) / MEDIA_ERROR_TIMED_OUT(-110)
 *
 * The current `res/raw` tracks are MIDI (.mid). Some OEM firmwares
 * (notably MediaTek-based Transsion / Infinix builds) have a quirky
 * Sonivox EAS path under MediaPlayer that fails silently on certain
 * MIDI Type 1 files. If the OnErrorListener fires with -1010
 * (UNSUPPORTED) consistently on this device, the right fix is to
 * re-render the .mid files to .ogg or .mp3 and bundle those instead.
 */
class BatchAudioPlayer(private val context: Context) {

    private var player: MediaPlayer? = null

    fun start(volumeGain: Float) {
        if (player != null) return
        val available = TRACKS.mapNotNull { name ->
            context.resources.getIdentifier(name, "raw", context.packageName)
                .takeIf { it != 0 }
                ?.let { resId -> name to resId }
        }
        if (available.isEmpty()) {
            Log.w(TAG, "no res/raw tracks bundled — music disabled")
            return
        }
        val (trackName, resId) = available.random()
        Log.i(TAG, "start: track=$trackName resId=$resId volume=$volumeGain")
        runCatching {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                val afd = context.resources.openRawResourceFd(resId)
                    ?: throw IllegalStateException("openRawResourceFd null for $trackName")
                afd.use { setDataSource(it.fileDescriptor, it.startOffset, it.length) }
                isLooping = true
                setVolume(volumeGain, volumeGain)
                setOnPreparedListener {
                    Log.i(TAG, "MediaPlayer prepared for $trackName, starting")
                    runCatching { it.start() }.onFailure { t ->
                        Log.e(TAG, "MediaPlayer.start threw for $trackName", t)
                        releaseMediaPlayer()
                    }
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG,
                        "MediaPlayer.onError what=$what extra=$extra track=$trackName" +
                            " — music is silent. ${decodeError(what, extra)}")
                    releaseMediaPlayer()
                    true  // consumed
                }
                setOnCompletionListener {
                    // Shouldn't fire with isLooping=true; if it does, the loop
                    // flag isn't being honoured on this OEM firmware.
                    Log.w(TAG, "MediaPlayer.onCompletion fired despite isLooping=true ($trackName)")
                }
                prepareAsync()
            }
        }.onFailure {
            Log.e(TAG, "MediaPlayer setup failed for $trackName: ${it.message}", it)
            releaseMediaPlayer()
        }
    }

    fun setVolume(gain: Float) {
        player?.runCatching { setVolume(gain, gain) }
    }

    fun stop() {
        releaseMediaPlayer()
    }

    private fun releaseMediaPlayer() {
        player?.runCatching {
            if (isPlaying) stop()
            release()
        }
        player = null
    }

    /** Decode MediaPlayer error codes into a human hint. */
    private fun decodeError(what: Int, extra: Int): String {
        val whatLabel = when (what) {
            MediaPlayer.MEDIA_ERROR_UNKNOWN -> "UNKNOWN"
            MediaPlayer.MEDIA_ERROR_SERVER_DIED -> "SERVER_DIED"
            else -> "?"
        }
        val extraLabel = when (extra) {
            MediaPlayer.MEDIA_ERROR_IO -> "IO"
            MediaPlayer.MEDIA_ERROR_MALFORMED -> "MALFORMED"
            MediaPlayer.MEDIA_ERROR_UNSUPPORTED -> "UNSUPPORTED"
            MediaPlayer.MEDIA_ERROR_TIMED_OUT -> "TIMED_OUT"
            else -> "?"
        }
        return "(what=$whatLabel, extra=$extraLabel) — if UNSUPPORTED, re-render the .mid to .ogg/.mp3"
    }

    private companion object {
        private const val TAG = "BatchAudioPlayer"
        // res/raw track names (no extension). One is chosen at random per start.
        val TRACKS = listOf(
            "aladdin", "cukupmasin", "gemuruh", "ilhamku", "impisyakilla",
            "jesnita", "kupujuk", "mmgbetul", "realitifantasi", "sendiri",
            "takkanmungkin", "warisanwanita",
        )
    }
}
