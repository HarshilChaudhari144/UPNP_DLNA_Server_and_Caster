package com.example.mysecondapp.dlna_lib.core.didl

import com.example.mysecondapp.dlna_lib.api.browse.BrowseResult
import com.example.mysecondapp.dlna_lib.api.media.*
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

internal class DidlLiteParser {

    fun parse(didlXml: String): BrowseResult {
        val containers = mutableListOf<MediaContainer>()
        val items = mutableListOf<MediaItem>()

        try {
            val factory = DocumentBuilderFactory.newInstance()
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(ByteArrayInputStream(didlXml.toByteArray()))

            // Parse Containers (Folders)
            val containerNodes = doc.getElementsByTagName("container")
            for (i in 0 until containerNodes.length) {
                val el = containerNodes.item(i) as Element
                containers.add(MediaContainer(
                    id = el.getAttribute("id"),
                    parentId = el.getAttribute("parentID"),
                    title = getTagValue(el, "dc:title") ?: "Unknown",
                    childCount = el.getAttribute("childCount").toIntOrNull()
                ))
            }

            // Parse Items (Files)
            val itemNodes = doc.getElementsByTagName("item")
            for (i in 0 until itemNodes.length) {
                val el = itemNodes.item(i) as Element
                val upnpClass = getTagValue(el, "upnp:class") ?: ""
                val resEl = el.getElementsByTagName("res").item(0) as? Element

                val resource = resEl?.let {
                    MediaResource(
                        uri = it.textContent,
                        protocolInfo = it.getAttribute("protocolInfo"),
                        mimeType = it.getAttribute("protocolInfo").split(":").getOrNull(2) ?: ""
                    )
                }

                items.add(MediaItem(
                    id = el.getAttribute("id"),
                    parentId = el.getAttribute("parentID"),
                    title = getTagValue(el, "dc:title") ?: "Unknown",
                    upnpClass = upnpClass,
                    // Dynamic mapping based on the UPnP class string
                    mediaType = mapUpnpClassToMediaType(upnpClass),
                    resources = if (resource != null) listOf(resource) else emptyList()
                ))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return BrowseResult(containers, items, containers.size + items.size, containers.size + items.size)
    }

    /**
     * Converts UPnP class strings (e.g., "object.item.videoItem") to our internal MediaType.
     */
    private fun mapUpnpClassToMediaType(upnpClass: String): MediaType {
        return when {
            upnpClass.contains("videoItem", ignoreCase = true) -> MediaType.VIDEO
            upnpClass.contains("audioItem", ignoreCase = true) -> MediaType.AUDIO
            upnpClass.contains("imageItem", ignoreCase = true) -> MediaType.IMAGE
            else -> MediaType.UNKNOWN
        }
    }

    private fun getTagValue(el: Element, tagName: String): String? {
        val nodes = el.getElementsByTagName(tagName)
        return if (nodes.length > 0) nodes.item(0).textContent else null
    }
}