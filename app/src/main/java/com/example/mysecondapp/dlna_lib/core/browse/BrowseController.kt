package com.example.mysecondapp.dlna_lib.core.browse

import com.example.mysecondapp.dlna_lib.api.browse.BrowseApi
import com.example.mysecondapp.dlna_lib.api.browse.BrowseResult
import com.example.mysecondapp.dlna_lib.api.device.Device
import com.example.mysecondapp.dlna_lib.api.device.Service
import com.example.mysecondapp.dlna_lib.api.errors.DlnaError
import com.example.mysecondapp.dlna_lib.core.device.DeviceRepository
import com.example.mysecondapp.dlna_lib.core.didl.DidlLiteParser
import com.example.mysecondapp.dlna_lib.core.error.PublicErrorMapper
import com.example.mysecondapp.dlna_lib.core.soap.SoapClient
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

internal class BrowseController(
    private val deviceRepository: DeviceRepository,
    private val soapClient: SoapClient
) : BrowseApi {

    private val didlParser = DidlLiteParser()

    override suspend fun browse(
        deviceId: String,
        containerId: String,
        startIndex: Int,
        count: Int
    ): BrowseResult {

        val device = deviceRepository.getDevice(deviceId)
            ?: throw DlnaError.Discovery("Device not found: $deviceId")

        val service = findContentDirectory(device)
            ?: throw DlnaError.Browse("Device does not support ContentDirectory")

        val args = mapOf(
            "ObjectID" to containerId,
            "BrowseFlag" to "BrowseDirectChildren",
            "Filter" to "*",
            "StartingIndex" to startIndex.toString(),
            "RequestedCount" to count.toString(),
            "SortCriteria" to ""
        )

        try {
            // 1. Send SOAP Action
            val responseXml = soapClient.sendAction(
                controlUrl = service.controlUrl,
                serviceType = service.serviceType,
                actionName = "Browse",
                arguments = args
            )

            // 2. Parse Outer SOAP to get the <Result> string
            return parseBrowseResponse(responseXml)

        } catch (t: Throwable) {
            throw PublicErrorMapper.mapToBrowseError(t, containerId)
        }
    }

    override suspend fun browseRoot(deviceId: String): BrowseResult {
        // "0" is the standard Root ID in DLNA
        return browse(deviceId, "0", 0, 100)
    }

    private fun findContentDirectory(device: Device): Service? {
        return device.services.find {
            it.serviceType.contains("ContentDirectory", ignoreCase = true)
        }
    }

    private fun parseBrowseResponse(soapXml: String): BrowseResult {
        // We need to extract: Result, TotalMatches, NumberReturned
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(InputSource(StringReader(soapXml)))
        doc.documentElement.normalize()

        // Helper to find text inside <Result> or <TotalMatches>
        // These tags are usually inside <u:BrowseResponse>
        // We scan all tags because namespaces vary

        val resultStr = getTagContent(doc.documentElement, "Result") ?: ""
        val totalMatchesStr = getTagContent(doc.documentElement, "TotalMatches") ?: "0"
        val numberReturnedStr = getTagContent(doc.documentElement, "NumberReturned") ?: "0"

        // 3. Parse the Inner DIDL-Lite XML
        val baseResult = didlParser.parse(resultStr)

        // 4. Combine with metadata from outer envelope
        return baseResult.copy(
            totalMatches = totalMatchesStr.toIntOrNull() ?: 0,
            numberReturned = numberReturnedStr.toIntOrNull() ?: 0
        )
    }

    private fun getTagContent(element: Element, localName: String): String? {
        val nodes = element.getElementsByTagName("*")
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node.localName == localName) {
                return node.textContent
            }
        }
        return null
    }
}