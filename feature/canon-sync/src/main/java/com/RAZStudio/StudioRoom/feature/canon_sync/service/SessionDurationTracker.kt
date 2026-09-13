/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * You should have received a copy of the Apache License
 * along with this program.  If not, see <http://www.apache.org/licenses/LICENSE-2.0>.
 */

package com.RAZStudio.StudioRoom.feature.canon_sync.service

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks how much `dataSync` foreground-service runtime the app has consumed
 * inside its current Android 15 / API 35+ budget window.
 *
 * The budget is **6 hours cumulative** of `dataSync` FGS time, and the **only
 * documented way to reset it is to bring the app to the foreground**. The
 * system never publishes a query API for the remaining time, so we track it
 * ourselves and persist across process death.
 *
 * Tracking model:
 *   - When the FGS starts, [noteSessionStart] records the current
 *     `SystemClock.elapsedRealtime()` (immune to wall-clock changes) and
 *     accumulates against [usedMillis]. While the service runs,
 *     [snapshotNow] returns the total time including the current in-flight
 *     interval.
 *   - When the FGS stops, [noteSessionStop] freezes the accumulated value.
 *   - When the app comes to the foreground (`ProcessLifecycleOwner` →
 *     `ON_RESUME`), [resetBudget] zeroes the counter — matching the
 *     system's reset trigger.
 *
 * Note: this is best-effort accounting. We can't observe the system's
 * internal counter, so our number is what the *system* thinks ours is only
 * if we never miss a start/stop event. In practice the FGS lifecycle is
 * straightforward enough that drift is negligible.
 */
@Singleton
internal class SessionDurationTracker @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME, Context.MODE_PRIVATE,
    )

    private val _state = MutableStateFlow(loadState())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        // Observe app-foreground transitions so we can reset the budget when
        // the user re-enters the app — that's the precise trigger the system
        // uses to refill the dataSync FGS budget. We use ActivityLifecycle
        // Callbacks (counting started Activities) rather than ProcessLifecycle
        // Owner to avoid pulling in androidx.lifecycle:lifecycle-process; the
        // counting model is equivalent for our purpose (transition from 0→1
        // started activities = app coming to foreground).
        (context.applicationContext as? Application)?.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                private var startedActivities = 0

                override fun onActivityStarted(activity: Activity) {
                    if (startedActivities == 0) resetBudget()
                    startedActivities++
                }
                override fun onActivityStopped(activity: Activity) {
                    if (startedActivities > 0) startedActivities--
                }
                override fun onActivityCreated(a: Activity, b: Bundle?) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            }
        )
    }

    /**
     * Snapshot of the budget. [usedMillis] is the persisted total; if the
     * service is currently running, [currentSessionStartElapsed] is non-null
     * and the live total is `usedMillis + (elapsedRealtime - sessionStart)`.
     */
    data class State(
        val usedMillis: Long,
        val currentSessionStartElapsed: Long?,
    ) {
        fun totalMillisAt(nowElapsed: Long): Long =
            usedMillis + (currentSessionStartElapsed?.let { nowElapsed - it } ?: 0L)

        fun remainingMillisAt(nowElapsed: Long): Long =
            (BUDGET_MILLIS - totalMillisAt(nowElapsed)).coerceAtLeast(0L)
    }

    fun noteSessionStart() {
        val now = SystemClock.elapsedRealtime()
        val current = _state.value
        // If we already have an in-flight session marker, fold its elapsed
        // time into usedMillis first so the new marker doesn't double-count.
        val foldedUsed = current.usedMillis +
            (current.currentSessionStartElapsed?.let { now - it } ?: 0L)
        val next = State(usedMillis = foldedUsed, currentSessionStartElapsed = now)
        persistAndPublish(next)
    }

    fun noteSessionStop() {
        val now = SystemClock.elapsedRealtime()
        val current = _state.value
        val frozen = current.usedMillis +
            (current.currentSessionStartElapsed?.let { now - it } ?: 0L)
        val next = State(usedMillis = frozen, currentSessionStartElapsed = null)
        persistAndPublish(next)
    }

    fun resetBudget() {
        val next = State(usedMillis = 0L, currentSessionStartElapsed = null)
        persistAndPublish(next)
    }

    fun snapshotNow(): State = _state.value

    private fun persistAndPublish(next: State) {
        prefs.edit().apply {
            putLong(KEY_USED_MILLIS, next.usedMillis)
            if (next.currentSessionStartElapsed != null) {
                putLong(KEY_SESSION_START, next.currentSessionStartElapsed)
            } else {
                remove(KEY_SESSION_START)
            }
        }.apply()
        _state.value = next
    }

    private fun loadState(): State {
        val used = prefs.getLong(KEY_USED_MILLIS, 0L)
        val sessionStart = if (prefs.contains(KEY_SESSION_START)) {
            prefs.getLong(KEY_SESSION_START, 0L)
        } else null
        // A session-start marker that survived process death is stale —
        // SystemClock.elapsedRealtime() resets on reboot, and even within a
        // single boot a stored marker from a previous process would
        // mis-attribute idle time as session time. Discard it on load.
        return State(usedMillis = used, currentSessionStartElapsed = sessionStart?.let { null })
    }

    companion object {
        /** Android 15 documented cumulative cap. */
        const val BUDGET_MILLIS: Long = 6L * 60 * 60 * 1000

        /** Warning fires at 5h of cumulative use. */
        const val WARN_THRESHOLD_MILLIS: Long = 5L * 60 * 60 * 1000

        /**
         * Soft-stop at 5h50m so we don't trip the OS's `onTimeout` deadline,
         * which only gives "a few seconds" before
         * `ForegroundServiceDidNotStopInTimeException`.
         */
        const val SOFT_STOP_THRESHOLD_MILLIS: Long = (5L * 60 + 50) * 60 * 1000

        private const val PREFS_NAME = "canon_sync_session_duration"
        private const val KEY_USED_MILLIS = "used_millis"
        private const val KEY_SESSION_START = "session_start_elapsed"
    }
}
