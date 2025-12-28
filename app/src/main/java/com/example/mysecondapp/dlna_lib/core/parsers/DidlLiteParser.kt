package com.example.mysecondapp.dlna_lib.core.parsers

import android.util.Log
import com.example.mysecondapp.dlna_lib.api.BrowseResult
import com.example.mysecondapp.dlna_lib.core.models.*
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import kotlin.time.Duration.Companion.seconds

class DidlLiteParser {

    private val factory = XmlPullParserFactory.newInstance().apply {
        isNamespaceAware = true
    }

    fun parse(xml: String): BrowseResult {
        val containers = mutableListOf<MediaContainer>()
        val items = mutableListOf<MediaItem>()

        try {
            val xpp = factory.newPullParser()
            xpp.setInput(StringReader(xml))

            var eventType = xpp.eventType

            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    when (xpp.name) {
                        "container" -> containers.add(parseContainer(xpp))
                        "item" -> items.add(parseItem(xpp))
                    }
                }
                eventType = xpp.next()
            }
        } catch (e: Exception) {
            Log.e("DidlLiteParser", "Error parsing DIDL XML", e)
        }

        return BrowseResult(
            containers = containers,
            items = items,
            totalMatches = containers.size + items.size,
            numberReturned = containers.size + items.size
        )
    }

    private fun parseContainer(xpp: XmlPullParser): MediaContainer {
        val id = xpp.getAttributeValue(null, "id") ?: ""
        val parentId = xpp.getAttributeValue(null, "parentID")
        val childCount = xpp.getAttributeValue(null, "childCount")?.toIntOrNull()
        val searchable = xpp.getAttributeValue(null, "searchable") == "1"
        var title = ""

        while (!(xpp.eventType == XmlPullParser.END_TAG && xpp.name == "container")) {
            if (xpp.eventType == XmlPullParser.START_TAG) {
                if (xpp.name == "title") {
                    title = safeNextText(xpp)
                }
            }
            xpp.next()
        }

        return MediaContainer(
            id = id,
            parentId = parentId,
            title = title,
            childCount = childCount,
            searchable = searchable
        )
    }

    private fun parseItem(xpp: XmlPullParser): MediaItem {
        val id = xpp.getAttributeValue(null, "id") ?: ""
        val parentId = xpp.getAttributeValue(null, "parentID") ?: ""
        var title = ""
        var upnpClass = ""
        var albumArtUri: String? = null
        val resources = mutableListOf<MediaResource>()

        while (!(xpp.eventType == XmlPullParser.END_TAG && xpp.name == "item")) {
            if (xpp.eventType == XmlPullParser.START_TAG) {
                when (xpp.name) {
                    "title" -> title = safeNextText(xpp)
                    "class" -> upnpClass = safeNextText(xpp)
                    "albumArtURI" -> albumArtUri = safeNextText(xpp)
                    "res" -> resources.add(parseResource(xpp))
                }
            }
            xpp.next()
        }

        // --- FIX START: Fallback to finding image in resources ---
        if (albumArtUri == null) {
            // Check if any resource is an image (based on MIME type from protocolInfo)
            val imageRes = resources.find { it.mimeType.startsWith("image/", ignoreCase = true) }
            if (imageRes != null) {
                albumArtUri = imageRes.uri
            }
        }
        // --- FIX END ---

        val type = determineMediaType(upnpClass, resources)
        val thumb = if (albumArtUri != null) MediaThumbnail(albumArtUri, "", null, null) else null

        // Find the main media resource (video/audio) to get duration/size
        // We exclude images from being the "main" resource if it's a video item
        val mainRes = resources.firstOrNull {
            !it.mimeType.startsWith("image/") && !it.mimeType.startsWith("text/")
        } ?: resources.firstOrNull()

        val duration = mainRes?.duration
        val size = mainRes?.size

        return MediaItem(
            id = id,
            parentId = parentId,
            title = title,
            upnpClass = upnpClass,
            mediaType = type,
            duration = duration,
            size = size,
            resources = resources,
            thumbnail = thumb
        )
    }

    private fun parseResource(xpp: XmlPullParser): MediaResource {
        val protocolInfo = xpp.getAttributeValue(null, "protocolInfo") ?: ""
        val durationStr = xpp.getAttributeValue(null, "duration")
        val size = xpp.getAttributeValue(null, "size")?.toLongOrNull()
        val url = safeNextText(xpp)

        val duration = parseDuration(durationStr)
        // Extract MIME type from 3rd field of protocolInfo
        // Format: protocol:network:contentFormat:additionalInfo
        val mime = protocolInfo.split(":").getOrNull(2) ?: "application/octet-stream"

        return MediaResource(
            uri = url,
            protocolInfo = protocolInfo,
            mimeType = mime,
            dlnaProfile = null,
            flags = null,
            resolution = null,
            duration = duration,
            size = size
        )
    }

    private fun parseDuration(str: String?): kotlin.time.Duration? {
        if (str.isNullOrEmpty()) return null
        return try {
            val parts = str.split(":").map { it.toDouble().toLong() }
            when (parts.size) {
                3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]).seconds
                else -> null
            }
        } catch (e: Exception) { null }
    }

    private fun determineMediaType(upnpClass: String, res: List<MediaResource>): MediaType {
        if (upnpClass.contains("video")) return MediaType.VIDEO
        if (upnpClass.contains("audio")) return MediaType.AUDIO
        if (upnpClass.contains("image")) return MediaType.IMAGE

        val mime = res.firstOrNull()?.mimeType ?: ""
        return when {
            mime.startsWith("video/") -> MediaType.VIDEO
            mime.startsWith("audio/") -> MediaType.AUDIO
            mime.startsWith("image/") -> MediaType.IMAGE
            else -> MediaType.UNKNOWN
        }
    }

    private fun safeNextText(xpp: XmlPullParser): String {
        return if (xpp.next() == XmlPullParser.TEXT) {
            xpp.text.also { xpp.next() }
        } else ""
    }
}