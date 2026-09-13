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

package com.RAZStudio.StudioRoom.core.crash.presentation.components

import android.content.Context
import android.content.Intent
import com.RAZStudio.StudioRoom.core.crash.di.CrashModule
import com.RAZStudio.StudioRoom.core.crash.presentation.CrashActivity
import com.RAZStudio.StudioRoom.core.domain.remote.AnalyticsManager
import com.RAZStudio.StudioRoom.core.utils.makeLog
import kotlin.system.exitProcess

private class GlobalExceptionHandler<T : CrashHandler> private constructor(
    private val applicationContext: Context,
    private val defaultHandler: Thread.UncaughtExceptionHandler?,
    private val activityToBeLaunched: Class<T>
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(
        p0: Thread,
        p1: Throwable
    ) {
        // Android 14+ delivers a delayed `ForegroundServiceDidNotStopInTime`
        // RemoteServiceException when a `dataSync` FGS runs past its 6-hour
        // budget. The exception is queued by the framework and dispatched
        // on a later process start — long after the service actually
        // stopped. Treating it as a fatal crash is misleading (the user
        // sees the crash screen on next launch even though everything is
        // fine). Swallow it after a quiet log line and let the process
        // continue normally.
        if (isHarmlessFgsTimeout(p1)) {
            p1.makeLog("FGS_TIMEOUT_SWALLOWED")
            return
        }

        sendReport(p1)

        runCatching {
            p1.makeLog("FATAL_EXCEPTION")

            applicationContext.launchActivity(activityToBeLaunched, p1)
            exitProcess(0)
        }.getOrElse {
            defaultHandler?.uncaughtException(p0, p1)
        }
    }

    private fun isHarmlessFgsTimeout(t: Throwable): Boolean {
        // Match by class name to avoid hard-linking against the inner
        // class which only exists on API 34+.
        val name = t.javaClass.name
        return name.contains("ForegroundServiceDidNotStopInTimeException") ||
            name.contains("ForegroundServiceTypeAttachedAfterStop") ||
            (t.message ?: "").contains("did not stop within its timeout")
    }

    private fun Context.launchActivity(
        activity: Class<*>,
        throwable: Throwable
    ) = applicationContext.startActivity(
        Intent(applicationContext, activity).putExtra(
            CrashHandler.EXCEPTION_EXTRA,
            CrashHandler.getCrashInfoAsExtra(throwable)
        ).addFlags(defFlags)
    )

    companion object : AnalyticsManager by CrashModule.analyticsManager() {

        fun <T : CrashHandler> initialize(
            applicationContext: Context,
            activityToBeLaunched: Class<T>,
        ) = Thread.setDefaultUncaughtExceptionHandler(
            GlobalExceptionHandler(
                applicationContext = applicationContext,
                defaultHandler = Thread.getDefaultUncaughtExceptionHandler()!!,
                activityToBeLaunched = activityToBeLaunched
            )
        )

    }
}

private const val defFlags = Intent.FLAG_ACTIVITY_CLEAR_TOP or
        Intent.FLAG_ACTIVITY_NEW_TASK or
        Intent.FLAG_ACTIVITY_CLEAR_TASK

fun Context.applyGlobalExceptionHandler() = GlobalExceptionHandler.initialize(
    applicationContext = applicationContext,
    activityToBeLaunched = CrashActivity::class.java,
)