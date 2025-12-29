package com.example.mysecondapp.dlna_lib.core.capability

import com.example.mysecondapp.dlna_lib.api.device.Device
import com.example.mysecondapp.dlna_lib.api.device.Service
import com.example.mysecondapp.dlna_lib.core.logging.DlnaLogger
import com.example.mysecondapp.dlna_lib.core.scpd.ScpdParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

internal object RendererCapabilities {

    private val tag = "RendererCapabilities"
    private val parser = ScpdParser()

    // Cache: ServiceId -> Set of Action Names
    private val capabilityCache = ConcurrentHashMap<String, Set<String>>()

    suspend fun canSeek(device: Device): Boolean {
        // 1. Find AVTransport Service
        val service = device.services.find { it.serviceType.contains("AVTransport") }
            ?: return false // No AVTransport = No playback at all

        // 2. Check strict capabilities
        return hasAction(service, "Seek")
    }

    suspend fun canControlVolume(device: Device): Boolean {
        // 1. Find RenderingControl Service
        val service = device.services.find { it.serviceType.contains("RenderingControl") }
            ?: return false

        // 2. Check strict capabilities
        return hasAction(service, "SetVolume")
    }

    suspend fun canMute(device: Device): Boolean {
        val service = device.services.find { it.serviceType.contains("RenderingControl") }
            ?: return false
        return hasAction(service, "SetMute")
    }

    /**
     * Checks if a specific action is supported by the service.
     * Uses caching to minimize network calls.
     */
    private suspend fun hasAction(service: Service, actionName: String): Boolean {
        // 1. Check Cache
        if (capabilityCache.containsKey(service.serviceId)) {
            return capabilityCache[service.serviceId]?.contains(actionName) == true
        }

        // 2. Fetch and Parse (Network Call)
        val actions = fetchAndParseScpd(service.scpdUrl)

        // 3. Update Cache
        if (actions.isNotEmpty()) {
            capabilityCache[service.serviceId] = actions
            return actions.contains(actionName)
        }

        // 4. Fallback: If SCPD fetch failed, we assume TRUE if the service exists.
        // This ensures we don't block features just because the TV's web server is slow/strict.
        DlnaLogger.w(tag, "SCPD fetch failed for ${service.serviceId}, assuming capability exists.")
        return true
    }

    private suspend fun fetchAndParseScpd(urlStr: String): Set<String> {
        if (urlStr.isBlank()) return emptySet()

        return withContext(Dispatchers.IO) {
            try {
                val xml = downloadUrl(urlStr)
                parser.parseActions(xml)
            } catch (e: Exception) {
                DlnaLogger.w(tag, "Error fetching SCPD from $urlStr: ${e.message}")
                emptySet()
            }
        }
    }

    private fun downloadUrl(urlStr: String): String {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 3000
        conn.readTimeout = 3000

        try {
            if (conn.responseCode != 200) throw Exception("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use(BufferedReader::readText)
        } finally {
            conn.disconnect()
        }
    }
}