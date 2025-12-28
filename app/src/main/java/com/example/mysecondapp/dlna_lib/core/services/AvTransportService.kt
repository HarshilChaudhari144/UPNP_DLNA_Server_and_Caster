package com.example.mysecondapp.dlna_lib.core.services

import android.util.Log
import com.example.mysecondapp.dlna_lib.core.models.Service
import com.example.mysecondapp.dlna_lib.core.soap.SoapClient

class AvTransportService(
    service: Service,
    soapClient: SoapClient,
    private val onTransportStateChanged: (String) -> Unit = {}
) : BaseService(service, soapClient) {

    suspend fun setAvTransportUri(
        uri: String,
        title: String = "Unknown Title",
        mimeType: String = "video/mp4"
    ) {
        // FIX: Add DLNA flags.
        // DLNA.ORG_OP=01 means "Range/Seek supported".
        // DLNA.ORG_FLAGS=... is a standard bitmask for streaming support.
        val protocolInfo = "http-get:*:$mimeType:DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"

        val metadata = """
            &lt;DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"&gt;
            &lt;item id="1" parentID="0" restricted="1"&gt;
            &lt;dc:title&gt;$title&lt;/dc:title&gt;
            &lt;upnp:class&gt;object.item.videoItem&lt;/upnp:class&gt;
            &lt;res protocolInfo="$protocolInfo"&gt;$uri&lt;/res&gt;
            &lt;/item&gt;
            &lt;/DIDL-Lite&gt;
        """.trimIndent()

        action("SetAVTransportURI", mapOf(
            "InstanceID" to "0",
            "CurrentURI" to uri,
            "CurrentURIMetaData" to metadata
        ))
    }

    suspend fun play() {
        action("Play", mapOf("InstanceID" to "0", "Speed" to "1"))
    }

    suspend fun pause() {
        action("Pause", mapOf("InstanceID" to "0"))
    }

    suspend fun stop() {
        action("Stop", mapOf("InstanceID" to "0"))
    }

    suspend fun seek(target: String) {
        action("Seek", mapOf(
            "InstanceID" to "0",
            "Unit" to "REL_TIME",
            "Target" to target
        ))
    }

    suspend fun getPositionInfo(): Map<String, String> {
        val responseXml = action("GetPositionInfo", mapOf("InstanceID" to "0"))
        val result = mutableMapOf<String, String>()

        val tagsToExtract = listOf(
            "Track", "TrackDuration", "TrackURI",
            "RelTime", "AbsTime", "RelCount", "AbsCount"
        )

        for (tag in tagsToExtract) {
            val value = Regex("<$tag>(.*?)</$tag>").find(responseXml)?.groupValues?.get(1)
            if (value != null) result[tag] = value
        }

        val metaMatch = Regex("<TrackMetaData>(.*?)</TrackMetaData>").find(responseXml)
        if (metaMatch != null) {
            val rawMeta = metaMatch.groupValues[1]
            result["TrackMetaData"] = rawMeta

            if (rawMeta.length > 20 && rawMeta != "NOT_IMPLEMENTED") {
                val didl = rawMeta
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&quot;", "\"")
                    .replace("&amp;", "&")

                val title = Regex("<dc:title>(.*?)</dc:title>").find(didl)?.groupValues?.get(1)
                val artist = Regex("<upnp:artist>(.*?)</upnp:artist>").find(didl)?.groupValues?.get(1)
                val artUri = Regex("<upnp:albumArtURI>(.*?)</upnp:albumArtURI>").find(didl)?.groupValues?.get(1)
                val upnpClass = Regex("<upnp:class>(.*?)</upnp:class>").find(didl)?.groupValues?.get(1)

                if (title != null) result["Title"] = title
                if (artist != null) result["Artist"] = artist
                if (artUri != null) result["AlbumArtURI"] = artUri
                if (upnpClass != null) result["UpnpClass"] = upnpClass
            }
        }
        return result
    }

    suspend fun getTransportInfo(): String? {
        val responseXml = action("GetTransportInfo", mapOf("InstanceID" to "0"))
        return Regex("<CurrentTransportState>(.*?)</CurrentTransportState>").find(responseXml)?.groupValues?.get(1)
    }

    override suspend fun handleEvent(xmlBody: String) {
        try {
            val lastChangeStart = xmlBody.indexOf("<LastChange>")
            val lastChangeEnd = xmlBody.indexOf("</LastChange>")

            if (lastChangeStart != -1 && lastChangeEnd != -1) {
                val encodedXml = xmlBody.substring(lastChangeStart + 12, lastChangeEnd)
                val innerXml = encodedXml
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&quot;", "\"")
                val stateMatch = Regex("TransportState val=\"(\\w+)\"").find(innerXml)
                if (stateMatch != null) {
                    val state = stateMatch.groupValues[1]
                    Log.d("AvTransportService", "Event Received: TransportState = $state")
                    onTransportStateChanged(state)
                }
            }
        } catch (e: Exception) {
            Log.e("AvTransportService", "Error parsing event", e)
        }
    }
}