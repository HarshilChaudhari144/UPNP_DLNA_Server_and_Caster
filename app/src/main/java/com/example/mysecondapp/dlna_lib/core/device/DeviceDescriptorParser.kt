package com.example.mysecondapp.dlna_lib.core.device

import com.example.mysecondapp.dlna_lib.api.device.Device
import com.example.mysecondapp.dlna_lib.api.device.Service
import com.example.mysecondapp.dlna_lib.core.url.UrlResolver
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory

internal class DeviceDescriptorParser {

    /**
     * Parses the device description XML and returns a Device model.
     * @param xmlStream The raw XML input stream from the device.
     * @param locationUrl The URL where this XML was fetched from (used for URL resolution).
     */
    fun parse(xmlStream: InputStream, locationUrl: String): Device? {
        return try {
            val factory = DocumentBuilderFactory.newInstance()
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(xmlStream)

            // The <device> tag is usually nested inside <root>
            val deviceNode = doc.getElementsByTagName("device").item(0) as Element

            val udn = getTagValue(deviceNode, "UDN") ?: ""
            val friendlyName = getTagValue(deviceNode, "friendlyName") ?: "Unknown Device"

            val services = mutableListOf<Service>()
            val serviceNodes = deviceNode.getElementsByTagName("service")

            for (i in 0 until serviceNodes.length) {
                val sElement = serviceNodes.item(i) as Element
                services.add(
                    Service(
                        serviceType = getTagValue(sElement, "serviceType") ?: "",
                        serviceId = getTagValue(sElement, "serviceId") ?: "",
                        controlUrl = UrlResolver.resolve(locationUrl, getTagValue(sElement, "controlURL") ?: ""),
                        eventSubUrl = UrlResolver.resolve(locationUrl, getTagValue(sElement, "eventSubURL") ?: ""),
                        scpdUrl = UrlResolver.resolve(locationUrl, getTagValue(sElement, "SCPDURL") ?: "")
                    )
                )
            }

            Device(
                deviceId = udn,
                deviceType = getTagValue(deviceNode, "deviceType") ?: "",
                friendlyName = friendlyName,
                manufacturer = getTagValue(deviceNode, "manufacturer"),
                modelName = getTagValue(deviceNode, "modelName"),
                udn = udn,
                services = services,
                presentationUrl = getTagValue(deviceNode, "presentationURL")
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun getTagValue(element: Element, tagName: String): String? {
        val list = element.getElementsByTagName(tagName)
        if (list.length > 0) {
            val node = list.item(0)
            if (node.parentNode == element) { // Ensure we don't grab tags from sub-devices
                return node.textContent
            }
        }
        return null
    }
}