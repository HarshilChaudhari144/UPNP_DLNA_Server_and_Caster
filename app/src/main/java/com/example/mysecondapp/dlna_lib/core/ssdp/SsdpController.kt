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

internal class SsdpController(
    private val transport: SsdpTransport,
    private val stateMachine: DeviceStateMachine,
    private val cache: SsdpCache
) {
    private val tag = "SsdpController"

    // Target list covers most devices
    private val searchTargets = listOf(
        "upnp:rootdevice",
        "urn:schemas-upnp-org:device:MediaRenderer:1",
        "urn:schemas-upnp-org:service:AVTransport:1",
        "ssdp:all"
    )

    fun start() {
        DlnaLogger.d(tag, "Starting SSDP Controller")
        cache.start()

        // 1. Listen
        transport.listen { data, address ->
            handlePacket(data, address)
        }

        // 2. Send Discovery (Burst Mode)
        dlnaScope.launch(Dispatchers.IO) {
            repeat(3) {
                searchTargets.forEach { target ->
                    try {
                        val packet = buildSearchPacket(target)
                        transport.send(packet)
                        delay(100)
                    } catch (e: Exception) {
                        DlnaLogger.w(tag, "Failed to send M-SEARCH: ${e.message}")
                    }
                }
                delay(1000)
            }
        }
    }

    fun stop() {
        DlnaLogger.d(tag, "Stopping SSDP Controller")
        cache.stop()
        transport.stop()
    }

    /**
     * Constructs a strictly formatted M-SEARCH packet.
     * CRITICAL: Must use \r\n and end with a double \r\n.
     */
    private fun buildSearchPacket(st: String): String {
        val sb = StringBuilder()
        sb.append("M-SEARCH * HTTP/1.1\r\n")
        sb.append("HOST: 239.255.255.250:1900\r\n")
        sb.append("MAN: \"ssdp:discover\"\r\n")
        sb.append("MX: 3\r\n")
        sb.append("ST: $st\r\n")
        // Samsung & LG often require User-Agent to respond
        sb.append("USER-AGENT: Android/1.0 DLNA-Lib/1.0 UPnP/1.1\r\n")
        sb.append("\r\n") // Blank line to end headers
        return sb.toString()
    }

    private fun handlePacket(text: String, address: String = "") {
        try {
            // Trim leading whitespace (some devices send garbage at start)
            val cleanText = text.trimStart()

            val firstLine = cleanText.substringBefore("\r\n").uppercase()
            val isNotify = firstLine.startsWith("NOTIFY")
            val isResponse = firstLine.startsWith("HTTP/1.1 200")

            if (!isNotify && !isResponse) return

            val headers = parseHeaders(cleanText)

            val usn = headers["USN"]
            val udn = extractUdn(usn) ?: return

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

        // Skip first line
        reader.readLine()

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
        return if (usn.startsWith("uuid:")) {
            val parts = usn.split("::")
            parts[0]
        } else {
            usn
        }
    }

    private fun parseCacheControl(cc: String?): Int {
        if (cc == null) return 1800
        return try {
            val part = cc.split(",").find { it.trim().lowercase().startsWith("max-age") }
            part?.substringAfter("=")?.trim()?.toInt() ?: 1800
        } catch (e: Exception) {
            1800
        }
    }
}