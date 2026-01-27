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
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.Date

internal class SsdpController(
    private val transport: SsdpTransport,
    private val stateMachine: DeviceStateMachine,
    private val cache: SsdpCache
) {
    private val tag = "SsdpController"

    data class ServerInfo(val usn: String, val location: String)
    private var serverInfo: ServerInfo? = null

    fun setServerInfo(usn: String, location: String) {
        this.serverInfo = ServerInfo(usn, location)
    }

    private val searchTargets = listOf(
        "upnp:rootdevice",
        "urn:schemas-upnp-org:device:MediaRenderer:1",
        "urn:schemas-upnp-org:service:AVTransport:1",
        "ssdp:all"
    )

    fun start() {
        DlnaLogger.d(tag, "Starting SSDP Controller")
        cache.start()

        transport.listen { data, address, port ->
            handlePacket(data, address, port)
        }

        dlnaScope.launch(Dispatchers.IO) {
            repeat(3) {
                searchTargets.forEach { target ->
                    try { transport.send(buildSearchPacket(target)); delay(100) } catch (e: Exception) { }
                }
                delay(1000)
            }
        }
    }

    fun stop() {
        cache.stop()
        transport.stop()
    }

    private fun handlePacket(text: String, address: String, port: Int) {
        try {
            val cleanText = text.trimStart()
            val firstLine = cleanText.substringBefore("\r\n").uppercase()
            val headers = parseHeaders(cleanText)

            if (firstLine.startsWith("M-SEARCH")) {
                DlnaLogger.d(tag, "Received M-SEARCH from $address:$port | ST=${headers["ST"]}")
                handleSearch(headers, address, port)
                return
            }

            // ... (Rest of client logic is silent to reduce noise) ...
            val isNotify = firstLine.startsWith("NOTIFY")
            val isResponse = firstLine.startsWith("HTTP/1.1 200")
            if (!isNotify && !isResponse) return

            val usn = headers["USN"]
            val udn = extractUdn(usn) ?: return
            val location = headers["LOCATION"]
            val nts = headers["NTS"]

            if (nts == "ssdp:byebye") {
                cache.recordByeBye(udn)
                stateMachine.onSsdpByeBye(udn)
            } else if (location != null) {
                val maxAge = parseCacheControl(headers["CACHE-CONTROL"])
                cache.recordAlive(udn, maxAge)
                stateMachine.onSsdpAlive(location, maxAge)
            }
        } catch (e: Exception) {
            DlnaLogger.w(tag, "Error parsing packet: ${e.message}")
        }
    }

    private fun handleSearch(headers: Map<String, String>, address: String, port: Int) {
        val server = serverInfo
        if (server == null) {
            DlnaLogger.w(tag, "Ignoring Search: ServerInfo not set yet")
            return
        }

        val st = headers["ST"] ?: return

        val myTargets = listOf(
            "upnp:rootdevice",
            "urn:schemas-upnp-org:device:MediaServer:1",
            server.usn
        )

        if (st == "ssdp:all") {
            DlnaLogger.d(tag, "Broadcasting ALL identities to $address")
            myTargets.forEach { target ->
                val response = buildSearchResponse(target, server)
                transport.sendTo(response, address, port)
            }
        } else if (myTargets.contains(st)) {
            DlnaLogger.d(tag, "Matched Target '$st'. Replying to $address")
            val response = buildSearchResponse(st, server)
            transport.sendTo(response, address, port)
        } else {
            DlnaLogger.d(tag, "Ignoring ST '$st' (Not me)")
        }
    }

    private fun buildSearchResponse(st: String, server: ServerInfo): String {
        val date = SimpleDateFormat("E, dd MMM yyyy HH:mm:ss z", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }.format(Date())

        val usn = if (st == server.usn) server.usn else "${server.usn}::$st"

        return "HTTP/1.1 200 OK\r\n" +
                "CACHE-CONTROL: max-age=1800\r\n" +
                "DATE: $date\r\n" +
                "EXT:\r\n" +
                "LOCATION: ${server.location}\r\n" +
                "SERVER: Android/1.0 DLNA-Lib/1.0 UPnP/1.0\r\n" +
                "ST: $st\r\n" +
                "USN: $usn\r\n" +
                "BOOTID.UPNP.ORG: 1\r\n" +
                "\r\n"
    }

    private fun buildSearchPacket(st: String): String {
        return "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 3\r\nST: $st\r\nUSER-AGENT: Android/1.0 DLNA-Lib/1.0 UPnP/1.1\r\n\r\n"
    }

    private fun parseHeaders(text: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val reader = BufferedReader(StringReader(text))
        reader.readLine()
        var line = reader.readLine()
        while (line != null) {
            if (line.isNotBlank()) {
                val parts = line.split(":", limit = 2)
                if (parts.size == 2) map[parts[0].trim().uppercase()] = parts[1].trim()
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
        val part = cc.split(",").find { it.trim().lowercase().startsWith("max-age") }
        return part?.substringAfter("=")?.trim()?.toIntOrNull() ?: 1800
    }

    fun discover() {
        dlnaScope.launch(Dispatchers.IO) {
            searchTargets.forEach { target ->
                try {
                    transport.send(buildSearchPacket(target))
                } catch (e: Exception) {
                    DlnaLogger.w(tag, "Failed to send M-SEARCH for $target")
                }
            }
        }
    }
}