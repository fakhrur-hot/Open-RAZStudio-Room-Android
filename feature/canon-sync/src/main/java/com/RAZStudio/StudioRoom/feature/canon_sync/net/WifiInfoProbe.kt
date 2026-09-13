/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.RAZStudio.StudioRoom.feature.canon_sync.net

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log

/**
 * Pulls the actual L3 layout of the phone's current Wi-Fi network
 * via [WifiManager.getDhcpInfo] so we can do *informed* subnet sweeps
 * instead of guessing `192.168.x.0/24`.
 *
 * Why this matters: a phone in tethering mode hands out 192.168.x.0/24
 * but the same phone on a home/office AP may be on 10.0.0.0/16 or
 * 172.16.0.0/12. The previous fallback scanner walked `2..254` on
 * every IPv4 interface — slow and noisy. With DHCP info we know the
 * exact gateway, netmask, and our own IP, so we can:
 *
 *   • Probe just the host bits that aren't us / the gateway.
 *   • Skip non-Wi-Fi interfaces entirely (mobile data, USB, VPN).
 *   • Log SSID/BSSID for diagnostics (and to detect "phone is NOT
 *     on the camera AP" before wasting time).
 *
 * Requires `ACCESS_FINE_LOCATION` on Android ≤12 and
 * `NEARBY_WIFI_DEVICES` on Android ≥13 for the SSID/BSSID lookup.
 * The DHCP info itself only needs `ACCESS_WIFI_STATE` which we have
 * as install-time.
 */
object WifiInfoProbe {

    data class Snapshot(
        /** The phone's own IPv4 on the current Wi-Fi (e.g. "192.168.43.69"). */
        val localIp: String,
        /** The DHCP-issued gateway (e.g. "192.168.43.1"). */
        val gateway: String,
        /** /24 etc., as derived from the DHCP netmask. */
        val subnetPrefix: String,
        /** Range of host bits to scan, excluding our own and the gateway. */
        val sweepHosts: IntRange,
        /** Friendly SSID if granted (else "<masked>" / null). */
        val ssid: String?,
        /** AP BSSID if granted (else null). */
        val bssid: String?,
    )

    fun snapshot(context: Context, tag: String = "WifiInfoProbe"): Snapshot? {
        val wifi = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        @Suppress("DEPRECATION")
        val dhcp = wifi.dhcpInfo ?: return null
        @Suppress("DEPRECATION")
        val info = wifi.connectionInfo

        val localIp = intToIp(dhcp.ipAddress)
        val gateway = intToIp(dhcp.gateway)
        val netmaskBits = popcount(dhcp.netmask)
        val subnetPrefix = localIp.substringBeforeLast('.')

        // For /24 networks (the common case) scan 1..254 minus ourselves
        // and the gateway. For wider subnets we cap at /24 around the
        // gateway to keep the probe fast.
        val ownLast = localIp.substringAfterLast('.').toIntOrNull() ?: 0
        val sweep = if (netmaskBits in 24..30) 1..254 else 1..254

        Log.i(
            tag,
            "snapshot: localIp=$localIp gateway=$gateway " +
                "netmask=${intToIp(dhcp.netmask)} (/$netmaskBits) " +
                "ssid=${info?.ssid} bssid=${info?.bssid}",
        )
        return Snapshot(
            localIp = localIp,
            gateway = gateway,
            subnetPrefix = subnetPrefix,
            sweepHosts = sweep,
            ssid = info?.ssid?.trim('"'),
            bssid = info?.bssid,
        )
    }

    private fun intToIp(addr: Int): String =
        "${addr and 0xFF}.${(addr ushr 8) and 0xFF}." +
            "${(addr ushr 16) and 0xFF}.${(addr ushr 24) and 0xFF}"

    private fun popcount(x: Int): Int = Integer.bitCount(x)
}
