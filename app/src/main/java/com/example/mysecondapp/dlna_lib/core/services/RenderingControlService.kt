package com.example.mysecondapp.dlna_lib.core.services

import android.util.Log
import com.example.mysecondapp.dlna_lib.core.models.Service
import com.example.mysecondapp.dlna_lib.core.soap.SoapClient

class RenderingControlService(
    service: Service,
    soapClient: SoapClient,
    private val onVolumeChanged: (Int, Boolean) -> Unit = { _, _ -> }
) : BaseService(service, soapClient) {

    // Cache current values to avoid sending redundant updates
    private var currentVolume = 0
    private var currentMute = false

    suspend fun setVolume(volume: Int) {
        // Volume is usually 0-100
        val safeVolume = volume.coerceIn(0, 100)
        action("SetVolume", mapOf(
            "InstanceID" to "0",
            "Channel" to "Master",
            "DesiredVolume" to safeVolume.toString()
        ))
    }

    suspend fun setMute(mute: Boolean) {
        action("SetMute", mapOf(
            "InstanceID" to "0",
            "Channel" to "Master",
            "DesiredMute" to if (mute) "1" else "0"
        ))
    }

    /**
     * Phase 8: Handle incoming GENA events for Volume and Mute.
     */
    override suspend fun handleEvent(xmlBody: String) {
        try {
            // 1. Extract LastChange
            val lastChangeStart = xmlBody.indexOf("<LastChange>")
            val lastChangeEnd = xmlBody.indexOf("</LastChange>")

            if (lastChangeStart != -1 && lastChangeEnd != -1) {
                // 2. Decode inner XML
                val encodedXml = xmlBody.substring(lastChangeStart + 12, lastChangeEnd)
                val innerXml = encodedXml
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&quot;", "\"")

                // 3. Parse Volume
                // Looks like: <Volume channel="Master" val="25"/>
                val volMatch = Regex("Volume.*?val=\"(\\d+)\"").find(innerXml)
                if (volMatch != null) {
                    currentVolume = volMatch.groupValues[1].toInt()
                }

                // 4. Parse Mute
                // Looks like: <Mute channel="Master" val="0"/>
                val muteMatch = Regex("Mute.*?val=\"([01])\"").find(innerXml)
                if (muteMatch != null) {
                    currentMute = (muteMatch.groupValues[1] == "1")
                }

                Log.d("RenderingControl", "Event: Vol=$currentVolume, Mute=$currentMute")
                onVolumeChanged(currentVolume, currentMute)
            }
        } catch (e: Exception) {
            Log.e("RenderingControl", "Error parsing event", e)
        }
    }
}