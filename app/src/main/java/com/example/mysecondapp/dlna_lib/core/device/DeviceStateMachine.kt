package com.example.mysecondapp.dlna_lib.core.device

import com.example.mysecondapp.dlna_lib.core.lifecycle.dlnaScope
import com.example.mysecondapp.dlna_lib.core.logging.DlnaLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

internal class DeviceStateMachine(
    private val repository: DeviceRepository,
    private val parser: DeviceDescriptorParser
) {
    private val tag = "DeviceStateMachine"

    // Track pending downloads to avoid spamming the same URL
    private val pendingDownloads = mutableSetOf<String>()

    fun onSsdpAlive(location: String, maxAge: Int) {
        // If we already have this device from this specific location,
        // effectively we might just update the cache expiry (handled by SsdpCache later).
        // But if it's new, we must download the XML.

        // Simple check: do we assume location maps to UDN? Not always, but for fetching XML it's unique.
        if (pendingDownloads.contains(location)) return

        dlnaScope.launch(Dispatchers.IO) {
            try {
                pendingDownloads.add(location)

                // 1. Download XML
                val xml = downloadXml(location)

                // 2. Parse
                val device = parser.parse(xml, location)

                // 3. Store
                repository.upsert(device)
                DlnaLogger.d(tag, "Device added/updated: ${device.friendlyName} [${device.deviceType}]")

            } catch (e: Exception) {
                DlnaLogger.e(tag, "Failed to load device description from $location: ${e.message}")
            } finally {
                pendingDownloads.remove(location)
            }
        }
    }

    fun onSsdpByeBye(udn: String) {
        DlnaLogger.d(tag, "Device ByeBye: $udn")
        repository.remove(udn)
    }

    private fun downloadXml(urlString: String): String {
        val url = URL(urlString)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 5000
        conn.readTimeout = 5000

        try {
            if (conn.responseCode != 200) {
                throw Exception("HTTP ${conn.responseCode}")
            }

            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            return reader.readText().also { reader.close() }
        } finally {
            conn.disconnect()
        }
    }
}