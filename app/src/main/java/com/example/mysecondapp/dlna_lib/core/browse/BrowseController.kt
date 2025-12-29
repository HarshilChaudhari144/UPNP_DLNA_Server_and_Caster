package com.example.mysecondapp.dlna_lib.core.browse

import com.example.mysecondapp.dlna_lib.api.browse.BrowseApi
import com.example.mysecondapp.dlna_lib.api.browse.BrowseResult
import com.example.mysecondapp.dlna_lib.api.device.DeviceId
import com.example.mysecondapp.dlna_lib.api.media.ContainerId
import com.example.mysecondapp.dlna_lib.core.device.DeviceRepository
import com.example.mysecondapp.dlna_lib.core.didl.DidlLiteParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

internal class BrowseController(
    private val deviceRepository: DeviceRepository,
    private val didlParser: DidlLiteParser
) : BrowseApi {

    override suspend fun browse(
        deviceId: DeviceId,
        containerId: ContainerId,
        startIndex: Int,
        count: Int
    ): BrowseResult = withContext(Dispatchers.IO) {
        val device = deviceRepository.getDevice(deviceId)
            ?: throw Exception("Device not found")

        val service = device.services.find { it.serviceType.contains("ContentDirectory") }
            ?: throw Exception("Device is not a Media Server")

        val soapBody = """
            <u:Browse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1">
                <ObjectID>$containerId</ObjectID>
                <BrowseFlag>BrowseDirectChildren</BrowseFlag>
                <Filter>*</Filter>
                <StartingIndex>$startIndex</StartingIndex>
                <RequestedCount>$count</RequestedCount>
                <SortCriteria></SortCriteria>
            </u:Browse>
        """.trimIndent()

        val responseXml = executeSoapAction(service.controlUrl, "ContentDirectory", "Browse", soapBody)

        // Extract the Result tag content (which is the DIDL XML)
        val didlData = responseXml.substringAfter("<Result>").substringBefore("</Result>")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

        return@withContext didlParser.parse(didlData)
    }

    override suspend fun browseRoot(deviceId: DeviceId): BrowseResult {
        return browse(deviceId, "0", 0, 20)
    }

    private fun executeSoapAction(controlUrl: String, serviceType: String, actionName: String, body: String): String {
        val connection = URL(controlUrl).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
        connection.setRequestProperty("SOAPACTION", "\"urn:schemas-upnp-org:service:$serviceType:1#$actionName\"")
        connection.doOutput = true

        val envelope = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                <s:Body>$body</s:Body>
            </s:Envelope>
        """.trimIndent()

        connection.outputStream.use { it.write(envelope.toByteArray()) }

        return connection.inputStream.bufferedReader().use { it.readText() }
    }
}