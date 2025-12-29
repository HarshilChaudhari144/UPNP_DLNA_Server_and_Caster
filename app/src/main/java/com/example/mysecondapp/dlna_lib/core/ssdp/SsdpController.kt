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

    // M-SEARCH Packet as a String (strictly using CRLF)
    private val mSearchPacket = """
        M-SEARCH * HTTP/1.1
        HOST: 239.255.255.250:1900
        MAN: "ssdp:discover"
        MX: 3
        ST: ssdp:all
        
    """.trimIndent().replace("\n", "\r\n")

    fun start() {
        DlnaLogger.d(tag, "Starting SSDP Controller")

        // 1. Start Cache cleanup loop
        cache.start()

        // 2. Start listening on Platform Transport
        // The callback provides 'data' (String) and 'remoteAddress' (String)
        transport.listen { data, _ ->
            // Processing text headers is fast, so we do it directly.
            // If this becomes heavy, wrap in dlnaScope.launch { ... }
            handlePacket(data)
        }

        // 3. Send discovery packets (burst of 3 for reliability)
        dlnaScope.launch(Dispatchers.IO) {
            repeat(3) {
                try {
                    transport.send(mSearchPacket)
                } catch (e: Exception) {
                    DlnaLogger.w(tag, "Failed to send M-SEARCH: ${e.message}")
                }
                delay(200)
            }
        }
    }

    fun stop() {
        DlnaLogger.d(tag, "Stopping SSDP Controller")
        cache.stop()
        transport.stop()
    }

    private fun handlePacket(text: String) {
        try {
            // We handle both NOTIFY (Announcements) and HTTP 200 OK (Search Responses)
            // Note: Some devices might send leading whitespace, so trimStart is safer.
            val cleanText = text.trimStart()
            val firstLine = cleanText.substringBefore("\r\n").uppercase()

            val isNotify = firstLine.startsWith("NOTIFY")
            val isResponse = firstLine.startsWith("HTTP/1.1 200")

            if (!isNotify && !isResponse) return

            val headers = parseHeaders(cleanText)

            val udn = extractUdn(headers["USN"]) ?: return
            val location = headers["LOCATION"]
            val nts = headers["NTS"] // ssdp:alive or ssdp:byebye

            // Check for ByeBye (Explicit disconnect)
            if (nts == "ssdp:byebye") {
                cache.recordByeBye(udn)
                stateMachine.onSsdpByeBye(udn)
                return
            }

            // Alive or Search Response
            if (location != null) {
                val maxAge = parseCacheControl(headers["CACHE-CONTROL"])

                // Update Cache (Keep Alive)
                cache.recordAlive(udn, maxAge)

                // Tell State Machine to fetch XML (if new)
                stateMachine.onSsdpAlive(location, maxAge)
            }

        } catch (e: Exception) {
            DlnaLogger.w(tag, "Error parsing packet: ${e.message}")
        }
    }

    private fun parseHeaders(text: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val reader = BufferedReader(StringReader(text))

        // Skip first line (method/status)
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
        // Format often: uuid:device-UUID::urn:service-type
        // We want: uuid:device-UUID
        return if (usn.startsWith("uuid:")) {
            val parts = usn.split("::")
            parts[0] // Returns uuid:xxxx-xxxx
        } else {
            usn // Fallback
        }
    }

    private fun parseCacheControl(cc: String?): Int {
        if (cc == null) return 1800 // Default 30 mins
        // Format: max-age=1800
        return try {
            val part = cc.split(",").find { it.trim().lowercase().startsWith("max-age") }
            part?.substringAfter("=")?.trim()?.toInt() ?: 1800
        } catch (e: Exception) {
            1800
        }
    }
}