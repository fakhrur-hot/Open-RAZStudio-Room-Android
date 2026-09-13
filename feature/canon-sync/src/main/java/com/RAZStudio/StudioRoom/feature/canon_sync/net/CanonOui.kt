/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.RAZStudio.StudioRoom.feature.canon_sync.net

import android.util.Log
import java.io.File
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/**
 * IEEE OUI prefixes registered to Canon Inc. (top 24 bits of the
 * MAC). Used to identify a Canon camera on the local L2 network
 * by ARP lookup BEFORE we commit to a full TCP port-15740 subnet
 * sweep. Hitting a known-Canon host first cuts discovery from up
 * to ~5 s to ~100 ms when the camera is already on the same AP.
 *
 * The list is conservative — includes every public Canon OUI we
 * know about so newer bodies (R-series, 6D Mark II, mirrorless)
 * are recognised. False positives (e.g. an old Canon printer on
 * the same Wi-Fi) just mean an extra TCP probe — harmless.
 */
object CanonOui {

    /**
     * Uppercase, colon-separated. Compared against `mac.take(8)`
     * after normalisation. Sourced from IEEE OUI registry +
     * field observations (`84:BA:3B` is on the 6D firmware-2.0.x
     * Wi-Fi MCU, missing from many third-party OUI lists).
     */
    val PREFIXES: List<String> = listOf(
        "00:00:85", // 2000-11-09
        "00:1E:8F", // 2007-12-31
        "00:80:9F",
        "00:BB:C1", // 2016-08-08
        "08:00:37",
        "18:0C:AC", // 2012-07-16
        "18:66:DA",
        "2C:9E:FC", // 2011-08-18
        "34:9F:7B", // 2019-10-01
        "40:16:3B",
        "40:F8:DF", // 2022-04-13
        "50:03:CF", // 2024-10-15
        "5C:62:5A", // 2021-02-03
        "60:12:8B", // 2014-10-25
        "6C:3C:7C", // 2021-07-15
        "6C:F2:D8", // 2024-01-23
        "74:38:B7", // 2019-03-04
        "74:BF:C0", // 2018-07-22
        "84:BA:3B", // 2015-11-17 — 6D / 6D Mark II Wi-Fi
        "88:87:17", // 2010-03-01
        "9C:32:CE", // 2017-10-11
        "A4:F0:1F", // 2025-12-23
        "D8:49:2F", // 2014-02-25
        "DC:C2:C9", // 2022-11-12
        "F4:81:39", // 2013-03-28
        "F4:92:BF",
        "F4:A9:97", // 2017-02-21
        "F8:0D:60", // 2015-11-17
        "F8:A2:6D",
    )

    /**
     * Normalise a MAC string to upper-case colon form `XX:XX:XX:XX:XX:XX`.
     * Accepts colon, hyphen, dot separators (and bare hex). Returns null
     * if the input is not 12 hex digits.
     */
    fun normalize(mac: String?): String? {
        val hex = mac?.uppercase()?.replace(Regex("[^0-9A-F]"), "") ?: return null
        if (hex.length != 12) return null
        return hex.chunked(2).joinToString(":")
    }

    /** True if [mac] starts with any [PREFIXES] entry. */
    fun isCanon(mac: String?): Boolean {
        val n = normalize(mac) ?: return false
        return PREFIXES.any { n.startsWith(it) }
    }

    /**
     * Result of scanning the kernel ARP table for a Canon device on
     * the same L2 network. [matchedMac] is the actual MAC (for the
     * "your camera is X" log line — useful when reporting bugs).
     */
    data class ArpHit(val ip: String, val matchedMac: String)

    /**
     * Read `/proc/net/arp` and return all IP/MAC pairs whose MAC starts
     * with a known Canon OUI. Empty list = no Canon device on this L2.
     * Ignores incomplete entries (flag `0x0`).
     *
     * The kernel ARP table is populated whenever any host on the
     * subnet has spoken to us (or vice versa). Android keeps entries
     * around for ~60 s after silence, so this is best-effort — if the
     * camera has been completely idle since boot it might not be in
     * the table yet. Caller should fall back to broadcast/scan.
     */
    /**
     * Prime the kernel ARP cache by fanning out a TCP-connect probe
     * to every IP on the phone's hotspot subnet. Useful before
     * [arpScan] because Android keeps `/proc/net/arp` empty until
     * the phone has actually spoken IP to a peer — even when
     * wificond logs the camera as associated to the hotspot.
     *
     * Probes port 80 with a tight 50 ms timeout per IP. Doesn't
     * matter if the camera REJECTS the connect — the SYN itself
     * triggers an ARP request which populates the cache. Returns
     * after all probes finish (cap ~5 s with 16-way fanout on
     * 253 hosts).
     */
    suspend fun primeArpCache(tag: String = "CanonOui") = kotlinx.coroutines.coroutineScope {
        // Find hotspot interface IPs (10.*, 172.*, 192.168.*).
        val ownIps = runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<java.net.Inet4Address>()
                .map { it.hostAddress.orEmpty() }
                .filter {
                    it.startsWith("192.168.") || it.startsWith("172.") || it.startsWith("10.")
                }
        }.getOrNull().orEmpty()
        if (ownIps.isEmpty()) {
            Log.w(tag, "primeArpCache: no candidate hotspot IPs — skipping")
            return@coroutineScope
        }
        Log.i(tag, "primeArpCache: own ips=$ownIps")
        val sem = kotlinx.coroutines.sync.Semaphore(16)
        val jobs = mutableListOf<kotlinx.coroutines.Job>()
        for (own in ownIps) {
            val prefix = own.substringBeforeLast('.')
            val ownLast = own.substringAfterLast('.').toIntOrNull() ?: continue
            for (last in 2..254) {
                if (last == ownLast) continue
                jobs += launch(kotlinx.coroutines.Dispatchers.IO) {
                    sem.acquire()
                    try {
                        val ip = "$prefix.$last"
                        // TCP probe — any port, just to provoke ARP.
                        // 80 is universally rejected fast on Canon
                        // (no HTTP server), but the ARP exchange
                        // happens before the RST. 50 ms is enough.
                        runCatching {
                            java.net.Socket().use { sock ->
                                sock.connect(java.net.InetSocketAddress(ip, 80), 50)
                            }
                        }
                    } finally { sem.release() }
                }
            }
        }
        jobs.joinAll()
    }

    fun arpScan(tag: String = "CanonOui"): List<ArpHit> {
        val arp = File("/proc/net/arp")
        if (!arp.exists()) {
            Log.w(tag, "arpScan: /proc/net/arp doesn't exist")
            return emptyList()
        }
        // Android 10+ blocks app-context reads of /proc/net/arp under
        // SELinux. We catch the SecurityException / FileNotFound here
        // so the caller falls back to the PTP-port scan path.
        val hits = mutableListOf<ArpHit>()
        val outcome = runCatching {
            arp.bufferedReader().useLines { lines ->
                lines.drop(1).forEach { line ->
                    // Columns: IP, HWType, Flags, MAC, Mask, Device.
                    val cols = line.trim().split(Regex("\\s+"))
                    if (cols.size < 4) return@forEach
                    val ip = cols[0]
                    val flags = cols[2]
                    val mac = cols[3]
                    // Flag 0x0 = incomplete; skip.
                    if (flags == "0x0") return@forEach
                    if (isCanon(mac)) {
                        Log.i(tag, "arpScan: Canon device ip=$ip mac=${normalize(mac)}")
                        hits += ArpHit(ip, normalize(mac) ?: mac)
                    }
                }
            }
        }
        if (outcome.isFailure) {
            Log.w(tag, "arpScan: /proc/net/arp read failed (${outcome.exceptionOrNull()?.message}) — " +
                "expected on Android 10+ (SELinux blocks app reads)")
        }
        return hits
    }

    /**
     * SELinux-safe alternative to [arpScan] on Android 10+. Fans out
     * TCP-connect probes to PTP/IP port 15740 across the phone's
     * hotspot subnet. Any host that accepts the connect within
     * [timeoutMs] is treated as a Canon EOS body (no other consumer
     * device listens on this port). Returns IPs only — MAC isn't
     * needed for camera selection.
     */
    /**
     * Informed variant — uses the DHCP-known gateway/subnet from
     * [WifiInfoProbe.Snapshot] instead of guessing per-interface
     * prefixes. Hits the gateway first (camera AP usually `x.1`),
     * then sweeps the rest of the host range in parallel. Returns
     * as soon as ALL probes finish; caller can early-exit on the
     * first hit if desired via the returned list size.
     */
    suspend fun ptpPortScan(
        snapshot: WifiInfoProbe.Snapshot,
        timeoutMs: Int = 100,
        tag: String = "CanonOui",
    ): List<String> = kotlinx.coroutines.coroutineScope {
        val prefix = snapshot.subnetPrefix
        val ownLast = snapshot.localIp.substringAfterLast('.').toIntOrNull() ?: 0
        val found = java.util.concurrent.CopyOnWriteArrayList<String>()
        val sem = kotlinx.coroutines.sync.Semaphore(24)
        val jobs = mutableListOf<kotlinx.coroutines.Job>()
        // Walk a deterministic order: gateway → low → high. Cameras almost
        // always sit at gateway±1 when they're the hotspot client.
        val ordered = sequence {
            val g = snapshot.gateway.substringAfterLast('.').toIntOrNull() ?: 1
            yield(g)
            for (n in snapshot.sweepHosts) {
                if (n != ownLast && n != g) yield(n)
            }
        }
        for (last in ordered) {
            jobs += launch(kotlinx.coroutines.Dispatchers.IO) {
                sem.acquire()
                try {
                    val ip = "$prefix.$last"
                    val ok = runCatching {
                        java.net.Socket().use { sock ->
                            sock.connect(java.net.InetSocketAddress(ip, 15740), timeoutMs)
                            true
                        }
                    }.getOrDefault(false)
                    if (ok) {
                        Log.i(tag, "ptpPortScan: Canon EOS on $ip (port 15740 ACK)")
                        found += ip
                    }
                } finally { sem.release() }
            }
        }
        jobs.joinAll()
        found.toList()
    }

    suspend fun ptpPortScan(
        timeoutMs: Int = 100,
        tag: String = "CanonOui",
    ): List<String> = kotlinx.coroutines.coroutineScope {
        val ownIps = runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<java.net.Inet4Address>()
                .map { it.hostAddress.orEmpty() }
                .filter {
                    it.startsWith("192.168.") || it.startsWith("172.") || it.startsWith("10.")
                }
        }.getOrNull().orEmpty()
        if (ownIps.isEmpty()) return@coroutineScope emptyList()
        val found = java.util.concurrent.CopyOnWriteArrayList<String>()
        val sem = kotlinx.coroutines.sync.Semaphore(16)
        val jobs = mutableListOf<kotlinx.coroutines.Job>()
        for (own in ownIps) {
            val prefix = own.substringBeforeLast('.')
            val ownLast = own.substringAfterLast('.').toIntOrNull() ?: continue
            for (last in 2..254) {
                if (last == ownLast) continue
                jobs += launch(kotlinx.coroutines.Dispatchers.IO) {
                    sem.acquire()
                    try {
                        val ip = "$prefix.$last"
                        val ok = runCatching {
                            java.net.Socket().use { sock ->
                                sock.connect(java.net.InetSocketAddress(ip, 15740), timeoutMs)
                                true
                            }
                        }.getOrDefault(false)
                        if (ok) {
                            Log.i(tag, "ptpPortScan: Canon EOS on $ip (port 15740 ACK)")
                            found += ip
                        }
                    } finally { sem.release() }
                }
            }
        }
        jobs.joinAll()
        found.toList()
    }
}
