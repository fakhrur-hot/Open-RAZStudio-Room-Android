/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 */

package com.RAZStudio.StudioRoom.feature.sony_sync.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import me.jahnen.libaums.core.UsbMassStorageDevice
import me.jahnen.libaums.core.fs.UsbFile
import me.jahnen.libaums.core.fs.UsbFileInputStream
import java.io.InputStream
import kotlin.coroutines.resume

/**
 * USB import path for Sony Sync (the "USB Mass Storage" connection type).
 *
 * Unlike the Wi-Fi paths (DLNA / ScalarWebAPI) — which the A7 II limits to
 * JPEG and often a 2 MP copy — a camera in **Mass Storage** USB mode exposes
 * its whole card as a FAT volume, so we can pull the ORIGINAL files including
 * RAW (.ARW). We read the block device directly with libaums (bulk-only
 * transport + FAT), the same stack the SD Card Browser uses, because most
 * phones do not auto-mount an OTG-attached camera as a StorageVolume.
 *
 * [detect] also dumps every camera feature reachable from the USB descriptors
 * (VID/PID + model, manufacturer/product/serial strings, and each interface's
 * class/subclass/protocol → Mass Storage vs PTP/Still-Image vs MTP, endpoint
 * counts). That is the same identification layer a USBPcap capture would show;
 * we read it live from [UsbManager] so no capture tooling is needed.
 */
class SonyUsbImporter(
    private val context: Context,
    private val log: (String) -> Unit,
) {
    private val usbManager get() = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var opened: UsbMassStorageDevice? = null

    /** What [detect] found: the chosen device + whether it is usable Mass Storage. */
    data class Detected(
        val device: UsbDevice,
        val isMassStorage: Boolean,
        val modeLabel: String,
        val displayName: String,
    )

    /** One image on the camera card, opened lazily so the caller can stream it. */
    data class UsbImage(val name: String, val sizeBytes: Long, val open: () -> InputStream)

    /**
     * Enumerate attached USB devices, log everything detectable, and return the
     * best camera candidate (a Mass Storage device, preferring a Sony body).
     * Returns null when nothing is attached.
     */
    fun detect(): Detected? {
        val devices = usbManager.deviceList.values.toList()
        log("USB: ${devices.size} device(s) attached.")
        if (devices.isEmpty()) {
            log("No USB device found. Connect the camera by OTG cable and set its USB")
            log("mode to \"Mass Storage\" (Menu → Setup → USB Connection), then retry.")
            return null
        }
        var best: Detected? = null
        for (d in devices) {
            val isSony = d.vendorId == SONY_VID
            val product = runCatching { d.productName }.getOrNull()
            val manufacturer = runCatching { d.manufacturerName }.getOrNull()
            log("────────────────────────────")
            log("Device: ${product ?: "(name needs permission)"}${if (isSony) "  [Sony]" else ""}")
            log("  VID:PID = ${hex4(d.vendorId)}:${hex4(d.productId)}" +
                (sonyModelFor(d.productId)?.let { "  ($it)" } ?: ""))
            manufacturer?.let { log("  Manufacturer: $it") }
            runCatching { d.serialNumber }.getOrNull()?.let { log("  Serial: $it") }
            log("  Device class ${d.deviceClass}, ${d.interfaceCount} interface(s), " +
                "USB ${runCatching { d.version }.getOrNull() ?: "?"}")

            var massStorage = false
            var stillImage = false
            var mtp = false
            for (i in 0 until d.interfaceCount) {
                val intf = d.getInterface(i)
                val cls = intf.interfaceClass
                val sub = intf.interfaceSubclass
                val proto = intf.interfaceProtocol
                val kind = usbClassLabel(cls, sub, proto, intf.name)
                log("  • iface#$i  class=$cls sub=$sub proto=$proto  →  $kind" +
                    "  (${intf.endpointCount} endpoints)")
                for (e in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(e)
                    val dir = if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"
                    log("      ep${e}: addr=${hex2(ep.address)} $dir type=${epType(ep.type)} " +
                        "maxPkt=${ep.maxPacketSize}")
                }
                when {
                    cls == UsbConstants.USB_CLASS_MASS_STORAGE -> massStorage = true
                    cls == USB_CLASS_STILL_IMAGE -> stillImage = true
                    intf.name?.contains("MTP", ignoreCase = true) == true -> mtp = true
                }
            }
            val mode = when {
                massStorage -> "Mass Storage (SCSI, Bulk-Only) — full card incl. RAW"
                stillImage  -> "PTP / Still Image — switch the camera to Mass Storage to import here"
                mtp         -> "MTP — switch the camera to Mass Storage to import here"
                else        -> "Unknown class ${d.deviceClass}"
            }
            log("  → USB mode: $mode")
            val display = product ?: sonyModelFor(d.productId) ?: "${hex4(d.vendorId)}:${hex4(d.productId)}"
            val candidate = Detected(d, massStorage, mode, display)
            // Prefer a Mass Storage device; among those prefer a Sony body.
            if (massStorage && (best == null || (isSony && best?.device?.vendorId != SONY_VID))) {
                best = candidate
            } else if (best == null) {
                best = candidate
            }
        }
        return best
    }

    /**
     * Suspend until USB access for [device] is granted (or denied). Returns
     * true when granted. Uses a one-shot receiver keyed to our private action.
     */
    suspend fun ensurePermission(device: UsbDevice): Boolean {
        if (usbManager.hasPermission(device)) return true
        log("Requesting USB permission for ${device.productName ?: "the camera"}…")
        return suspendCancellableCoroutine { cont ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    if (intent?.action != ACTION_USB_PERMISSION) return
                    runCatching { context.unregisterReceiver(this) }
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (cont.isActive) cont.resume(granted)
                }
            }
            val filter = IntentFilter(ACTION_USB_PERMISSION)
            ContextCompat.registerReceiver(
                context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(
                context, 0,
                Intent(ACTION_USB_PERMISSION).setPackage(context.packageName), flags,
            )
            usbManager.requestPermission(device, pi)
        }
    }

    /**
     * Open the Mass Storage volume for [device] and enumerate every photo under
     * DCIM (JPEG + Sony RAW). Blocking libaums I/O — call from an IO dispatcher.
     * Returns an empty list if the device can't be opened or has no DCIM.
     */
    fun openAndListImages(device: UsbDevice): List<UsbImage> {
        val msd = UsbMassStorageDevice.getMassStorageDevices(context)
            .firstOrNull { it.usbDevice.deviceId == device.deviceId }
            ?: run { log("This USB device is not a readable Mass Storage volume."); return emptyList() }
        opened = msd
        return runCatching {
            msd.init()
            val root = msd.partitions.firstOrNull()?.fileSystem?.rootDirectory
                ?: run { log("No readable FAT partition on the card."); return emptyList() }
            val dcim = root.search("DCIM") ?: root.listFiles()
                .firstOrNull { it.isDirectory && it.name.equals("DCIM", ignoreCase = true) }
            if (dcim == null) { log("No DCIM folder on the card."); return emptyList() }
            val out = ArrayList<UsbImage>()
            collectImages(dcim, out)
            log("Found ${out.size} photo(s) under DCIM.")
            out
        }.getOrElse {
            log("Could not read the card: ${it.message}")
            emptyList()
        }
    }

    private fun collectImages(dir: UsbFile, out: MutableList<UsbImage>) {
        val children = runCatching { dir.listFiles() }.getOrDefault(emptyArray())
        for (f in children) {
            if (f.isDirectory) {
                collectImages(f, out)
            } else if (isImage(f.name)) {
                out += UsbImage(
                    name = f.name,
                    sizeBytes = runCatching { f.length }.getOrDefault(0L),
                    open = { UsbFileInputStream(f) },
                )
            }
        }
    }

    fun close() {
        runCatching { opened?.close() }
        opened = null
    }

    private fun isImage(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in IMAGE_EXTS
    }

    private fun usbClassLabel(cls: Int, sub: Int, proto: Int, name: String?): String = when {
        cls == UsbConstants.USB_CLASS_MASS_STORAGE && sub == 6 && proto == 0x50 ->
            "Mass Storage (SCSI transparent, Bulk-Only)"
        cls == UsbConstants.USB_CLASS_MASS_STORAGE -> "Mass Storage"
        cls == USB_CLASS_STILL_IMAGE -> "Still Image (PTP / PictBridge)"
        name?.contains("MTP", ignoreCase = true) == true -> "MTP"
        cls == UsbConstants.USB_CLASS_VENDOR_SPEC -> "Vendor-specific${name?.let { " ($it)" } ?: ""}"
        else -> "class $cls"
    }

    private fun epType(type: Int): String = when (type) {
        UsbConstants.USB_ENDPOINT_XFER_BULK -> "bulk"
        UsbConstants.USB_ENDPOINT_XFER_INT -> "interrupt"
        UsbConstants.USB_ENDPOINT_XFER_ISOC -> "isochronous"
        UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "control"
        else -> "type$type"
    }

    private fun hex4(v: Int) = "0x%04X".format(v)
    private fun hex2(v: Int) = "0x%02X".format(v)

    /** Best-effort model name from the Sony USB product id (product string wins when present). */
    private fun sonyModelFor(pid: Int): String? =
        SONY_PIDS[pid]?.let { id -> "${SonyModelNames.pretty(id)} ($id)" }

    companion object {
        /** Sony Corporation USB vendor id. */
        const val SONY_VID = 0x054C

        /** USB base class 6 = Still Image (PTP) — not exposed as a UsbConstants field. */
        private const val USB_CLASS_STILL_IMAGE = 6

        private const val ACTION_USB_PERMISSION =
            "com.RAZStudio.StudioRoom.feature.sony_sync.USB_PERMISSION"

        private val IMAGE_EXTS = setOf(
            "jpg", "jpeg", "arw", "raw", "heif", "heic", "tif", "tiff", "png",
        )

        // A few common Sony Alpha USB product ids (Mass Storage mode). The USB
        // product string ("ILCE-7M2" etc.) is authoritative when the device
        // grants it; this table is a fallback for a nicer label pre-permission.
        //
        // NOTE: A Sony body reports a DIFFERENT USB product id per USB mode
        // (Mass Storage vs MTP vs PTP), and there is no authoritative public
        // PID→model map — pmca-re keys off the USB product STRING, not the PID,
        // for this reason. So this table is only a best-effort pre-permission
        // label for Mass-Storage-mode PIDs; the product string (SonyModelNames)
        // is always preferred once USB permission is granted. Unknown PIDs fall
        // back to the raw VID:PID until the product string is available.
        // Verified / commonly reported Mass-Storage / MTP / PC-Remote PIDs
        // (VID 0x054C). Same body uses DIFFERENT PIDs per USB mode — product
        // string always wins after permission. Values are canonical ILCE/… IDs
        // so [SonyModelNames.pretty] / [SonyModelNames.canonicalForLensfun] work.
        private val SONY_PIDS = mapOf(
            // ILCE-7 / 7R early
            0x03E2 to "ILCE-7R",
            0x079C to "ILCE-7",
            0x07C2 to "ILCE-7R",
            // ILCE-6000 family (usb-ids)
            0x07C3 to "ILCE-6000",
            0x07C4 to "ILCE-6000",
            0x08B7 to "ILCE-6000",
            0x094E to "ILCE-6000",
            0x0994 to "ILCE-6000",
            // ILCE-7M3 (usb-ids / DeviceHunt)
            0x0C02 to "ILCE-7M3",
            0x0C03 to "ILCE-7M3",
            0x0C34 to "ILCE-7M3",
            // NEX / SLT (forum captures)
            0x048E to "NEX-5",
            0x04A5 to "NEX-5",
            0x0677 to "NEX-6",
            0x0678 to "NEX-6",
            0x066B to "SLT-A37",
            0x066C to "SLT-A37",
            // Keep prior 0x0A6A as a soft label only if still observed on-device;
            // prefer product string when available.
            0x0A6A to "ILCE-7RM4",
        )
    }
}
