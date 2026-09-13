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

package com.RAZStudio.StudioRoom.feature.sd_card_browser.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.RAZStudio.StudioRoom.core.domain.coroutines.DispatchersHolder
import com.RAZStudio.StudioRoom.core.utils.AppLog
import com.RAZStudio.StudioRoom.feature.sd_card_browser.domain.FileHandle
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.jahnen.libaums.core.UsbMassStorageDevice
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detects and opens a USB Mass Storage device (OTG card reader) directly via
 * libaums (bulk-only transport + FAT), for hardware where Android doesn't
 * auto-mount the reader as a native StorageVolume — see the USB chooser
 * investigation ("CX File Explorer took over the OTG device") this class was
 * added to resolve. StudioRoom competes for the same USB_DEVICE_ATTACHED
 * chooser other card-reader apps use (see usb_device_filter.xml) rather than
 * relying on StorageManager, which never sees a device this way.
 *
 * [UsbMassStorageDevice.init] performs blocking USB bulk-transfer I/O (SCSI
 * partition-table reads). The collector (Decompose's [ComponentContext]
 * coroutine scope) runs on `Dispatchers.Main`, so every call into libaums
 * must be explicitly moved onto [DispatchersHolder.ioDispatcher] — running it
 * inline froze the main thread silently until the reader was unplugged
 * (confirmed: permission was already granted, the MAX-LUN control transfer
 * logged successfully, then zero further log output for ~8.5s until a
 * DETACHED broadcast). [OPEN_TIMEOUT_MS] guards against a hardware/driver
 * hang on this same call so a stuck reader surfaces an error instead of an
 * indefinitely "loading" screen.
 */
@Singleton
class UsbMsdVolumeDetector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatchersHolder,
) {
    private val usbManager get() = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var openedDevice: UsbMassStorageDevice? = null

    /**
     * Observes USB attach/detach and permission-grant events, emitting the
     * current [UsbMsdState]. Requests permission automatically when a mass
     * storage device is present but not yet authorized.
     */
    fun observe(): Flow<UsbMsdState> = callbackFlow {
        suspend fun tryOpen(device: UsbMassStorageDevice) {
            val hasPermission = usbManager.hasPermission(device.usbDevice)
            AppLog.i(TAG, "tryOpen: device=${device.usbDevice.productName} hasPermission=$hasPermission")
            if (!hasPermission) {
                trySend(UsbMsdState.PermissionRequested)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val pi = PendingIntent.getBroadcast(
                    context, 0, Intent(ACTION_USB_PERMISSION).setPackage(context.packageName), flags
                )
                AppLog.i(TAG, "tryOpen: requesting permission for ${device.usbDevice.productName}")
                usbManager.requestPermission(device.usbDevice, pi)
                return
            }

            val result = withTimeoutOrNull(OPEN_TIMEOUT_MS) {
                runCatching {
                    device.init()
                    device.partitions.firstOrNull()?.fileSystem?.rootDirectory
                }
            }
            when {
                result == null -> {
                    AppLog.e(TAG, "USB MSD init timed out after ${OPEN_TIMEOUT_MS}ms — reader may be stuck")
                    trySend(UsbMsdState.Error("USB device did not respond in time"))
                }

                result.isFailure -> {
                    val error = result.exceptionOrNull()
                    if (error != null) AppLog.e(TAG, "USB MSD init failed", error) else AppLog.e(TAG, "USB MSD init failed")
                    trySend(UsbMsdState.Error(error?.message ?: "USB init failed"))
                }

                result.getOrNull() == null -> {
                    trySend(UsbMsdState.Error("No readable partition on USB device"))
                }

                else -> {
                    openedDevice?.close()
                    openedDevice = device
                    AppLog.i(TAG, "USB MSD device opened: ${device.usbDevice.productName}")
                    trySend(UsbMsdState.Ready(FileHandle.Usb(result.getOrThrow()!!), device.usbDevice.productName ?: "USB drive"))
                }
            }
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                AppLog.i(TAG, "onReceive: action=${intent?.action}")
                when (intent?.action) {
                    ACTION_USB_PERMISSION -> {
                        @Suppress("DEPRECATION")
                        val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        AppLog.i(TAG, "ACTION_USB_PERMISSION: device=${device?.productName} granted=$granted")
                        if (granted && device != null) {
                            val msd = UsbMassStorageDevice.getMassStorageDevices(context)
                                .firstOrNull { it.usbDevice.deviceId == device.deviceId }
                            if (msd != null) launch(dispatchers.ioDispatcher) { tryOpen(msd) }
                        } else {
                            AppLog.w(TAG, "USB permission denied for ${device?.productName}")
                            trySend(UsbMsdState.PermissionDenied(device?.productName ?: "USB device"))
                        }
                    }

                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        val msd = UsbMassStorageDevice.getMassStorageDevices(context).firstOrNull()
                        if (msd != null) launch(dispatchers.ioDispatcher) { tryOpen(msd) }
                    }

                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        openedDevice?.close()
                        openedDevice = null
                        trySend(UsbMsdState.NoDevice)
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        AppLog.i(TAG, "observe: flow started, registering receiver")

        val devices = UsbMassStorageDevice.getMassStorageDevices(context)
        AppLog.i(TAG, "observe: initial scan found ${devices.size} MSD device(s)")
        if (devices.isEmpty()) {
            trySend(UsbMsdState.NoDevice)
        } else {
            launch(dispatchers.ioDispatcher) { tryOpen(devices.first()) }
        }

        awaitClose {
            AppLog.i(TAG, "observe: flow closed, unregistering receiver")
            context.unregisterReceiver(receiver)
            openedDevice?.close()
            openedDevice = null
        }
    }

    companion object {
        private const val TAG = "UsbMsdVolumeDetector"
        private const val OPEN_TIMEOUT_MS = 10_000L
        private const val ACTION_USB_PERMISSION =
            "com.RAZStudio.StudioRoom.feature.sd_card_browser.USB_PERMISSION"
    }
}

/** State of raw USB Mass Storage device detection/opening. */
sealed interface UsbMsdState {
    data object NoDevice : UsbMsdState
    data object PermissionRequested : UsbMsdState
    data class PermissionDenied(val deviceName: String) : UsbMsdState
    data class Ready(val root: FileHandle.Usb, val deviceName: String) : UsbMsdState
    data class Error(val message: String) : UsbMsdState
}
