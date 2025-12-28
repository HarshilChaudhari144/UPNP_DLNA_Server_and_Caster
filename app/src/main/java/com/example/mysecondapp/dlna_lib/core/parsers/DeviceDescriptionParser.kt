package com.example.mysecondapp.dlna_lib.core.parsers

import com.example.mysecondapp.dlna_lib.core.models.Device
import com.example.mysecondapp.dlna_lib.core.models.Service
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

class DeviceDescriptionParser {

    private val factory = XmlPullParserFactory.newInstance().apply {
        isNamespaceAware = true
    }

    fun parse(xml: String, locationUrl: String): Device? {
        val xpp = factory.newPullParser()
        xpp.setInput(StringReader(xml))

        var eventType = xpp.eventType

        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                if (xpp.name == "device") {
                    // Pass locationUrl down to the parsing function
                    return parseDevice(xpp, locationUrl)
                }
            }
            eventType = xpp.next()
        }
        return null
    }

    private fun parseDevice(xpp: XmlPullParser, locationUrl: String): Device {
        var deviceType = ""
        var friendlyName = ""
        var manufacturer: String? = null
        var modelName: String? = null
        var modelDescription: String? = null
        var udn = ""
        var presentationUrl: String? = null
        val services = mutableListOf<Service>()

        while (!(xpp.eventType == XmlPullParser.END_TAG && xpp.name == "device")) {
            if (xpp.eventType == XmlPullParser.START_TAG) {
                when (xpp.name) {
                    "deviceType" -> deviceType = safeNextText(xpp)
                    "friendlyName" -> friendlyName = safeNextText(xpp)
                    "manufacturer" -> manufacturer = safeNextText(xpp)
                    "modelName" -> modelName = safeNextText(xpp)
                    "modelDescription" -> modelDescription = safeNextText(xpp)
                    "UDN" -> udn = safeNextText(xpp)

                    // FIX: Actually parse the presentation URL
                    "presentationURL" -> presentationUrl = resolveUrl(locationUrl, safeNextText(xpp))

                    "serviceList" -> services.addAll(parseServiceList(xpp, locationUrl))
                }
            }
            xpp.next()
        }

        return Device(
            deviceId = udn,
            deviceType = deviceType,
            friendlyName = friendlyName,
            manufacturer = manufacturer,
            modelName = modelName,
            modelDescription = modelDescription,
            udn = udn,
            services = services,
            presentationUrl = presentationUrl,
            locationUrl = locationUrl // <--- FIX: Save the Location URL
        )
    }

    private fun parseServiceList(xpp: XmlPullParser, baseUrl: String): List<Service> {
        val services = mutableListOf<Service>()
        while (!(xpp.eventType == XmlPullParser.END_TAG && xpp.name == "serviceList")) {
            if (xpp.eventType == XmlPullParser.START_TAG && xpp.name == "service") {
                services.add(parseService(xpp, baseUrl))
            }
            xpp.next()
        }
        return services
    }

    private fun parseService(xpp: XmlPullParser, baseUrl: String): Service {
        var serviceType = ""
        var serviceId = ""
        var controlUrl = ""
        var eventSubUrl = ""
        var scpdUrl = ""

        while (!(xpp.eventType == XmlPullParser.END_TAG && xpp.name == "service")) {
            if (xpp.eventType == XmlPullParser.START_TAG) {
                when (xpp.name) {
                    "serviceType" -> serviceType = safeNextText(xpp)
                    "serviceId" -> serviceId = safeNextText(xpp)
                    "controlURL" -> controlUrl = resolveUrl(baseUrl, safeNextText(xpp))
                    "eventSubURL" -> eventSubUrl = resolveUrl(baseUrl, safeNextText(xpp))
                    "SCPDURL" -> scpdUrl = resolveUrl(baseUrl, safeNextText(xpp))
                }
            }
            xpp.next()
        }
        return Service(serviceType, serviceId, controlUrl, eventSubUrl, scpdUrl)
    }

    private fun resolveUrl(base: String, path: String): String {
        if (path.isEmpty()) return ""
        if (path.startsWith("http")) return path
        return try {
            val uri = java.net.URI(base)
            uri.resolve(path).toString()
        } catch (e: Exception) {
            path
        }
    }

    private fun safeNextText(xpp: XmlPullParser): String {
        return if (xpp.next() == XmlPullParser.TEXT) {
            xpp.text.also { xpp.next() }
        } else ""
    }
}