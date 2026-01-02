package com.example.mysecondapp.dlna_lib.core.didl

import com.example.mysecondapp.dlna_lib.api.browse.BrowseResult
import com.example.mysecondapp.dlna_lib.api.media.*
import com.example.mysecondapp.dlna_lib.core.logging.DlnaLogger
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.milliseconds

internal class DidlLiteParser {

    private val tag = "DidlLiteParser"

    fun parse(xml: String): BrowseResult {
        if (xml.isBlank()) {
            return BrowseResult(emptyList(), emptyList(), 0, 0)
        }

        // 1. Prepare XML (Handle fragments, unescaping)
        val validXml = prepareXml(xml)

        val containers = ArrayList<MediaContainer>()
        val items = ArrayList<MediaItem>()

        try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            val builder = factory.newDocumentBuilder()
            val inputSource = InputSource(StringReader(validXml))
            val doc = builder.parse(inputSource)

            doc.documentElement.normalize()

            val root = doc.documentElement

            val childNodes = root.childNodes
            for (i in 0 until childNodes.length) {
                val node = childNodes.item(i)
                if (node.nodeType == Node.ELEMENT_NODE) {
                    val element = node as Element
                    // Match purely on local name to avoid namespace headaches
                    when (element.localName) {
                        "container" -> parseContainer(element)?.let { containers.add(it) }
                        "item" -> parseItem(element)?.let { items.add(it) }
                    }
                }
            }

        } catch (e: Exception) {
            DlnaLogger.e(tag, "Failed to parse DIDL-Lite XML. Content snippet: ${validXml.take(100)}", e)
        }

        return BrowseResult(containers, items, 0, 0)
    }

    /**
     * Sanitizes the input string to ensure it is valid XML.
     * 1. Unescapes &lt; if the string looks encoded.
     * 2. Wraps in <DIDL-Lite> if it's a fragment.
     */
    private fun prepareXml(raw: String): String {
        // TRIM FIRST
        var xml = raw.trim()

        // Step 1: Check if double-escaped (common in GetMediaInfo responses)
        if (xml.startsWith("&lt;")) {
            xml = unescapeXml(xml)
        }

        // TRIM AGAIN (Vital fix: unescaping might leave whitespace before <?xml)
        xml = xml.trim()

        // Step 2: Remove <?xml ... ?> declaration if it exists inside the fragment
        // (Nested declarations cause parser errors)
        if (xml.startsWith("<?xml")) {
            val endDecl = xml.indexOf("?>")
            if (endDecl != -1) {
                xml = xml.substring(endDecl + 2).trim()
            }
        }

        // Step 3: Check for Root Element
        if (!xml.contains("<DIDL-Lite", ignoreCase = true) && !xml.contains(":DIDL-Lite", ignoreCase = true)) {
            return """
                <DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" 
                           xmlns:dc="http://purl.org/dc/elements/1.1/" 
                           xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">
                $xml
                </DIDL-Lite>
            """.trimIndent()
        }

        return xml
    }

    private fun unescapeXml(input: String): String {
        // We do NOT replace &amp; here.
        // If the content is "Tom &amp; Jerry", we want to keep "&amp;"
        // so the XML parser reads it as "Tom & Jerry".
        // Converting it to "Tom & Jerry" (raw &) breaks XML parsing.
        return input.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
    }

    private fun parseContainer(element: Element): MediaContainer? {
        val id = element.getAttribute("id")
        val parentId = element.getAttribute("parentID")
        val title = getTagValue(element, "title") ?: "Unknown Folder"
        val childCountStr = element.getAttribute("childCount")
        val childCount = childCountStr.toIntOrNull()

        val searchable = element.getAttribute("searchable") == "1" || element.getAttribute("searchable") == "true"

        if (id.isEmpty()) return null

        return MediaContainer(
            id = id,
            parentId = parentId.ifEmpty { null },
            title = title,
            childCount = childCount,
            searchable = searchable
        )
    }

    private fun parseItem(element: Element): MediaItem? {
        val id = element.getAttribute("id")
        val parentId = element.getAttribute("parentID")
        val title = getTagValue(element, "title") ?: "Unknown Item"
        val upnpClass = getTagValue(element, "class") ?: "object.item"

        if (id.isEmpty()) return null

        // FIX: Parse Date (dc:date)
        val dateStr = getTagValue(element, "date")
        val date = parseDateToMillis(dateStr)

        val resources = parseResources(element)
        val mediaType = determineMediaType(upnpClass, resources)

        val albumArtUri = getTagValue(element, "albumArtURI")
        val thumbnail = if (albumArtUri != null) {
            MediaThumbnail(uri = albumArtUri, mimeType = "image/jpeg", width = null, height = null)
        } else null

        return MediaItem(
            id = id,
            parentId = parentId.ifEmpty { "0" },
            title = title,
            upnpClass = upnpClass,
            mediaType = mediaType,
            resources = resources,
            thumbnail = thumbnail,
            date = date // <--- Pass the parsed date
        )
    }

    // Add this helper function
    private fun parseDateToMillis(dateStr: String?): Long? {
        if (dateStr.isNullOrBlank()) return null
        return try {
            // Try ISO 8601 (YYYY-MM-DD)
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            sdf.parse(dateStr)?.time
        } catch (e: Exception) {
            null
        }
    }

    private fun parseResources(itemElement: Element): List<MediaResource> {
        val resList = ArrayList<MediaResource>()
        // Use "*" to ignore namespace prefixes (dlna:res, upnp:res, or just res)
        val resNodes = itemElement.getElementsByTagNameNS("*", "res")

        for (i in 0 until resNodes.length) {
            val resNode = resNodes.item(i) as Element
            val uri = resNode.textContent?.trim() ?: continue
            val protocolInfo = resNode.getAttribute("protocolInfo") ?: "*:*:*:*"
            val size = resNode.getAttribute("size").toLongOrNull()
            val durationStr = resNode.getAttribute("duration")
            val resolution = resNode.getAttribute("resolution")

            val parts = protocolInfo.split(":")
            val mimeType = if (parts.size >= 3) parts[2] else "application/octet-stream"

            val duration = parseDuration(durationStr)

            resList.add(MediaResource(
                uri = uri,
                protocolInfo = protocolInfo,
                mimeType = mimeType,
                size = size,
                duration = duration,
                resolution = resolution.ifEmpty { null }
            ))
        }
        return resList
    }

    private fun determineMediaType(upnpClass: String, resources: List<MediaResource>): MediaType {
        if (upnpClass.contains("video", ignoreCase = true)) return MediaType.VIDEO
        if (upnpClass.contains("audio", ignoreCase = true) || upnpClass.contains("music", ignoreCase = true)) return MediaType.AUDIO
        if (upnpClass.contains("image", ignoreCase = true) || upnpClass.contains("photo", ignoreCase = true)) return MediaType.IMAGE

        val mime = resources.firstOrNull()?.mimeType?.lowercase() ?: ""
        return when {
            mime.startsWith("video") -> MediaType.VIDEO
            mime.startsWith("audio") -> MediaType.AUDIO
            mime.startsWith("image") -> MediaType.IMAGE
            else -> MediaType.UNKNOWN
        }
    }

    private fun getTagValue(element: Element, tagName: String): String? {
        val namespaces = listOf("http://purl.org/dc/elements/1.1/", "urn:schemas-upnp-org:metadata-1-0/upnp/", "*")

        for (ns in namespaces) {
            val list = element.getElementsByTagNameNS(ns, tagName)
            if (list.length > 0) return list.item(0).textContent
        }
        val allNodes = element.getElementsByTagName("*")
        for (i in 0 until allNodes.length) {
            val node = allNodes.item(i)
            if (node.localName == tagName) return node.textContent
        }
        return null
    }

    private fun parseDuration(timestamp: String?): Duration? {
        if (timestamp.isNullOrBlank()) return null
        try {
            val parts = timestamp.split(":")
            if (parts.size != 3) return null

            val h = parts[0].toLong()
            val m = parts[1].toLong()

            val secondsParts = parts[2].split(".")
            val s = secondsParts[0].toLong()
            val ms = if (secondsParts.size > 1) {
                secondsParts[1].take(3).padEnd(3, '0').toLong()
            } else 0L

            return h.hours + m.minutes + s.seconds + ms.milliseconds
        } catch (e: Exception) {
            return null
        }
    }
}