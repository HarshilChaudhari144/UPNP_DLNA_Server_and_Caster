package com.example.mysecondapp.dlna_lib.core.ssdp

import com.example.mysecondapp.dlna_lib.core.device.DeviceStateMachine
import com.example.mysecondapp.dlna_lib.core.lifecycle.dlnaScope
import com.example.mysecondapp.dlna_lib.core.logging.DlnaLogger
import com.example.mysecondapp.dlna_lib.platform.SsdpTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.StringReader
import java.net.InetAddress

internal class SsdpController(
    private val transport: SsdpTransport,
    private val stateMachine: DeviceStateMachine,
    private val cache: SsdpCache
) {
    private val tag = "SsdpController"

    // Callback for external component (MediaServer) to handle searches
    var onSearchReceived: ((String, InetAddress, Int) -> Unit)? = null

    private val searchTargets = listOf(
        "upnp:rootdevice",
        "urn:schemas-upnp-org:device:MediaRenderer:1",
        "urn:schemas-upnp-org:service:AVTransport:1",
        "ssdp:all"
    )

    fun start() {
        DlnaLogger.d(tag, "Starting SSDP Controller")
        cache.start()

        // Updated listener signature
        transport.listen { data, address, port ->
            handlePacket(data, address, port)
        }

        dlnaScope.launch(Dispatchers.IO) {
            repeat(3) {
                searchTargets.forEach { target ->
                    try {
                        val packet = buildSearchPacket(target)
                        transport.send(packet)
                        delay(100)
                    } catch (e: Exception) { DlnaLogger.w(tag, "Send failed: ${e.message}") }
                }
                delay(1000)
            }
        }
    }

    fun stop() {
        cache.stop()
        transport.stop()
    }

    private fun buildSearchPacket(st: String): String {
        return "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 3\r\nST: $st\r\nUSER-AGENT: Android/1.0 DLNA-Lib/1.0 UPnP/1.1\r\n\r\n"
    }

    private fun handlePacket(text: String, address: InetAddress, port: Int) {
        try {
            val cleanText = text.trimStart()
            val firstLine = cleanText.substringBefore("\r\n").uppercase()

            // 1. Handle Search Requests (From TV)
            if (firstLine.startsWith("M-SEARCH")) {
                onSearchReceived?.invoke(cleanText, address, port)
                return
            }

            // 2. Handle Responses (From Devices)
            val isNotify = firstLine.startsWith("NOTIFY")
            val isResponse = firstLine.startsWith("HTTP/1.1 200")
            if (!isNotify && !isResponse) return

            val headers = parseHeaders(cleanText)
            val udn = extractUdn(headers["USN"]) ?: return
            val location = headers["LOCATION"]
            val nts = headers["NTS"]

            if (nts == "ssdp:byebye") {
                cache.recordByeBye(udn)
                stateMachine.onSsdpByeBye(udn)
                return
            }

            if (location != null) {
                val maxAge = parseCacheControl(headers["CACHE-CONTROL"])
                cache.recordAlive(udn, maxAge)
                stateMachine.onSsdpAlive(location, maxAge)
            }
        } catch (e: Exception) {
            DlnaLogger.w(tag, "Error parsing packet: ${e.message}")
        }
    }

    private fun parseHeaders(text: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val reader = BufferedReader(StringReader(text))
        reader.readLine() // Skip first line
        var line = reader.readLine()
        while (line != null) {
            if (line.isNotBlank()) {
                val parts = line.split(":", limit = 2)
                if (parts.size == 2) {
                    map[parts[0].trim().uppercase()] = parts[1].trim()
                }
            }
            line = reader.readLine()
        }
        return map
    }

    private fun extractUdn(usn: String?): String? {
        if (usn == null) return null
        return if (usn.startsWith("uuid:")) usn.split("::")[0] else usn
    }

    private fun parseCacheControl(cc: String?): Int {
        if (cc == null) return 1800
        return try {
            cc.split(",").find { it.trim().lowercase().startsWith("max-age") }
                ?.substringAfter("=")?.trim()?.toInt() ?: 1800
        } catch (e: Exception) { 1800 }
    }
}