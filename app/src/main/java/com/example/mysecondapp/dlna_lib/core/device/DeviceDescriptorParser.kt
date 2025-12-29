package com.example.mysecondapp.dlna_lib.core.device

import com.example.mysecondapp.dlna_lib.api.device.Device
import com.example.mysecondapp.dlna_lib.api.device.Service
import com.example.mysecondapp.dlna_lib.core.logging.DlnaLogger
import com.example.mysecondapp.dlna_lib.core.url.UrlResolver
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

internal class DeviceDescriptorParser {

    private val tag = "DeviceDescriptorParser"

    /**
     * Parses the Device Description XML.
     *
     * @param xml The raw XML string returned from the HTTP GET.
     * @param locationUrl The URL where this XML was downloaded from (used as base for relative URLs).
     * @return A parsed Device object, or throws an Exception if parsing fails.
     */
    fun parse(xml: String, locationUrl: String): Device {
        try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true // Set true to handle xmlns correctly, or false to ignore
            val builder = factory.newDocumentBuilder()
            val inputSource = InputSource(StringReader(xml))
            val doc = builder.parse(inputSource)

            doc.documentElement.normalize()

            // 1. Check for URLBase (UPnP 1.0 specific, overrides locationUrl)
            val urlBaseNode = doc.getElementsByTagName("URLBase").item(0)
            val effectiveBaseUrl = if (urlBaseNode != null && urlBaseNode.textContent.isNotBlank()) {
                urlBaseNode.textContent.trim()
            } else {
                locationUrl
            }

            // 2. Find the root <device> tag
            // Note: The root element is <root>, we need the first child <device>
            val deviceList = doc.getElementsByTagName("device")
            if (deviceList.length == 0) {
                throw Exception("No <device> tag found in description XML")
            }

            // We parse the first device found (Root Device)
            val deviceElement = deviceList.item(0) as Element
            return parseDeviceElement(deviceElement, effectiveBaseUrl)

        } catch (e: Exception) {
            DlnaLogger.e(tag, "Failed to parse device description: ${e.message}", e)
            throw e
        }
    }

    private fun parseDeviceElement(element: Element, baseUrl: String): Device {
        val deviceType = getTagValue(element, "deviceType") ?: "unknown"
        val friendlyName = getTagValue(element, "friendlyName") ?: "Unknown Device"
        val manufacturer = getTagValue(element, "manufacturer")
        val modelName = getTagValue(element, "modelName")
        val modelDescription = getTagValue(element, "modelDescription")
        val udn = getTagValue(element, "UDN") ?: throw Exception("Device missing UDN")
        val presentationUrlRaw = getTagValue(element, "presentationURL")

        // Resolve Presentation URL if it exists
        val presentationUrl = presentationUrlRaw?.let { UrlResolver.resolve(baseUrl, it) }

        // Parse Services
        val services = ArrayList<Service>()
        val serviceListNodes = element.getElementsByTagName("serviceList")

        if (serviceListNodes.length > 0) {
            val serviceListElement = serviceListNodes.item(0) as Element
            val serviceNodes = serviceListElement.getElementsByTagName("service")

            for (i in 0 until serviceNodes.length) {
                val serviceNode = serviceNodes.item(i)
                if (serviceNode.nodeType == Node.ELEMENT_NODE) {
                    val serviceObj = parseServiceElement(serviceNode as Element, baseUrl)
                    if (serviceObj != null) {
                        services.add(serviceObj)
                    }
                }
            }
        }

        return Device(
            deviceId = udn, // UDN is the unique ID
            deviceType = deviceType,
            friendlyName = friendlyName,
            manufacturer = manufacturer,
            modelName = modelName,
//            modelDescription = modelDescription,
            udn = udn,
            services = services,
            presentationUrl = presentationUrl
        )
    }

    private fun parseServiceElement(element: Element, baseUrl: String): Service? {
        val serviceType = getTagValue(element, "serviceType") ?: return null
        val serviceId = getTagValue(element, "serviceId") ?: return null

        val controlUrlRaw = getTagValue(element, "controlURL") ?: return null
        val eventSubUrlRaw = getTagValue(element, "eventSubURL")
        val scpdUrlRaw = getTagValue(element, "SCPDURL") ?: ""

        return Service(
            serviceType = serviceType,
            serviceId = serviceId,
            // Crucial: Resolve relative paths to absolute URLs
            controlUrl = UrlResolver.resolve(baseUrl, controlUrlRaw),
            eventSubUrl = eventSubUrlRaw?.let { UrlResolver.resolve(baseUrl, it) },
            scpdUrl = UrlResolver.resolve(baseUrl, scpdUrlRaw)
        )
    }

    private fun getTagValue(element: Element, tagName: String): String? {
        val nodeList = element.getElementsByTagName(tagName)
        if (nodeList.length > 0) {
            return nodeList.item(0).textContent?.trim()
        }
        return null
    }
}