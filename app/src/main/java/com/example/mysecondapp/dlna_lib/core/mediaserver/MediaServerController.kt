package com.example.mysecondapp.dlna_lib.core.mediaserver

import android.util.Log
import com.example.mysecondapp.dlna_lib.api.DlnaConfig
import com.example.mysecondapp.dlna_lib.api.media.*
import com.example.mysecondapp.dlna_lib.core.lifecycle.dlnaScope
import com.example.mysecondapp.dlna_lib.core.logging.DlnaLogger
import com.example.mysecondapp.dlna_lib.core.ssdp.SsdpController
import com.example.mysecondapp.dlna_lib.platform.*
import kotlinx.coroutines.*
import java.util.UUID
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

internal class MediaServerController(
    private val config: DlnaConfig,
    private val httpServer: HttpServer,
    private val mimeResolver: MimeTypeResolver,
    private val networkInfo: NetworkInfoProvider,
    private val ssdpTransport: SsdpTransport,
    private val ssdpController: SsdpController
) {
    private val tag = "MediaServerController"

    // Functional Handler for streaming (Byte-Range support)
    private val mediaHandler = MediaHttpHandler(
        config.contentProvider!!,
        config.thumbnailProvider
    )

    // State
    private var isRunning = false
    private var boundPort = 0

    private val serverUuid = if (config.serverUdn.startsWith("uuid:")) config.serverUdn else "uuid:${config.serverUdn}"

    private var advJob: Job? = null

    // Constants for DLNA Flags used by Samsung
    private companion object {
        const val VIDEO_FLAGS = "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
        const val THUMB_FLAGS = "DLNA.ORG_PN=JPEG_TN;DLNA.ORG_OP=01;DLNA.ORG_CI=1;DLNA.ORG_FLAGS=00D00000000000000000000000000000"
        const val THUMB_SM_FLAGS = "DLNA.ORG_PN=JPEG_SM;DLNA.ORG_OP=01;DLNA.ORG_CI=1;DLNA.ORG_FLAGS=00D00000000000000000000000000000"
    }

    fun start() {
        if (isRunning) return
        try {
            httpServer.start(8300, ServerRouter())
            boundPort = httpServer.getPort()
            isRunning = true
            DlnaLogger.d(tag, "Media Server started on port $boundPort with UDN: $serverUuid")
            val ip = networkInfo.getCurrentIpAddress() ?: "127.0.0.1"
            val location = "http://$ip:$boundPort/description.xml"
            ssdpController.setServerInfo(serverUuid, location)
            startAdvertising()
        } catch (e: Exception) {
            DlnaLogger.e(tag, "Failed to start Media Server", e)
            stop()
        }
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        runBlocking { sendSsdp(alive = false) }
        advJob?.cancel()
        httpServer.stop()
        DlnaLogger.d(tag, "Media Server stopped")
    }

    private inner class ServerRouter : HttpHandler {
        override suspend fun handle(request: HttpRequest): HttpResponse {
            val path = request.path
            return when {
                path == "/description.xml" -> serveDescription()
                path == "/soap/ContentDirectory" -> handleContentDirectory(request)
                path == "/soap/ConnectionManager" -> handleConnectionManager(request)
                path.startsWith("/content/") || path.startsWith("/thumb/") -> mediaHandler.handle(request)
                path == "/scpd/ContentDirectory.xml" -> serveContentDirectoryScpd()
                path == "/scpd/ConnectionManager.xml" -> serveConnectionManagerScpd()
                else -> HttpResponse(404)
            }
        }
    }

    private fun serveContentDirectoryScpd(): HttpResponse {
        val xml = """
        <?xml version="1.0"?>
        <scpd xmlns="urn:schemas-upnp-org:service-1-0">
            <specVersion><major>1</major><minor>0</minor></specVersion>
            <actionList>
                <action>
                    <name>Browse</name>
                    <argumentList>
                        <argument><name>ObjectID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_ObjectID</relatedStateVariable></argument>
                        <argument><name>BrowseFlag</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_BrowseFlag</relatedStateVariable></argument>
                        <argument><name>Filter</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Filter</relatedStateVariable></argument>
                        <argument><name>StartingIndex</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Index</relatedStateVariable></argument>
                        <argument><name>RequestedCount</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Count</relatedStateVariable></argument>
                        <argument><name>SortCriteria</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_SortCriteria</relatedStateVariable></argument>
                        <argument><name>Result</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_Result</relatedStateVariable></argument>
                        <argument><name>NumberReturned</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_Count</relatedStateVariable></argument>
                        <argument><name>TotalMatches</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_Count</relatedStateVariable></argument>
                        <argument><name>UpdateID</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_UpdateID</relatedStateVariable></argument>
                    </argumentList>
                </action>
            </actionList>
            <serviceStateTable>
                <stateVariable sendEvents="no"><name>A_ARG_TYPE_ObjectID</name><dataType>string</dataType></stateVariable>
                <stateVariable sendEvents="no"><name>A_ARG_TYPE_BrowseFlag</name><dataType>string</dataType></stateVariable>
                <stateVariable sendEvents="no"><name>A_ARG_TYPE_Filter</name><dataType>string</dataType></stateVariable>
                <stateVariable sendEvents="no"><name>A_ARG_TYPE_Index</name><dataType>ui4</dataType></stateVariable>
                <stateVariable sendEvents="no"><name>A_ARG_TYPE_Count</name><dataType>ui4</dataType></stateVariable>
                <stateVariable sendEvents="no"><name>A_ARG_TYPE_SortCriteria</name><dataType>string</dataType></stateVariable>
                <stateVariable sendEvents="no"><name>A_ARG_TYPE_Result</name><dataType>string</dataType></stateVariable>
                <stateVariable sendEvents="no"><name>A_ARG_TYPE_UpdateID</name><dataType>ui4</dataType></stateVariable>
            </serviceStateTable>
        </scpd>
    """.trimIndent()
        return HttpResponse(statusCode = 200, mimeType = "text/xml", body = xml)
    }

    private fun serveConnectionManagerScpd(): HttpResponse {
        val xml = """
        <?xml version="1.0"?>
        <scpd xmlns="urn:schemas-upnp-org:service-1-0">
            <specVersion><major>1</major><minor>0</minor></specVersion>
            <actionList>
                <action>
                    <name>GetProtocolInfo</name>
                    <argumentList>
                        <argument><name>Source</name><direction>out</direction><relatedStateVariable>SourceProtocolInfo</relatedStateVariable></argument>
                        <argument><name>Sink</name><direction>out</direction><relatedStateVariable>SinkProtocolInfo</relatedStateVariable></argument>
                    </argumentList>
                </action>
            </actionList>
            <serviceStateTable>
                <stateVariable sendEvents="no"><name>SourceProtocolInfo</name><dataType>string</dataType></stateVariable>
                <stateVariable sendEvents="no"><name>SinkProtocolInfo</name><dataType>string</dataType></stateVariable>
            </serviceStateTable>
        </scpd>
    """.trimIndent()
        return HttpResponse(statusCode = 200, mimeType = "text/xml", body = xml)
    }

    private fun serveDescription(): HttpResponse {
        val ip = networkInfo.getCurrentIpAddress() ?: "127.0.0.1"
        val iconXml = if (config.thumbnailProvider != null) """
            <iconList>
                <icon>
                    <mimetype>image/png</mimetype>
                    <width>48</width><height>48</height><depth>24</depth>
                    <url>/thumb/app_icon</url>
                </icon>
            </iconList>
        """.trimIndent() else ""

        val xml = """<?xml version="1.0"?>
            <root xmlns="urn:schemas-upnp-org:device-1-0" 
                  xmlns:dlna="urn:schemas-dlna-org:device-1-0" 
                  xmlns:sec="http://www.sec.co.kr/dlna">
                <specVersion><major>1</major><minor>0</minor></specVersion>
                <device>
                    <deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>
                    <friendlyName>${config.serverName}</friendlyName>
                    <manufacturer>DLNA Lib</manufacturer>
                    <modelName>Kotlin Media Server</modelName>
                    <UDN>$serverUuid</UDN>
                    <dlna:X_DLNADOC>DMS-1.50</dlna:X_DLNADOC>
                    <dlna:X_DLNADOC>M-DMS-1.50</dlna:X_DLNADOC>
                    <sec:ProductCap>smi,getCaptionInfo.sec</sec:ProductCap>
                    <sec:X_ProductCap>smi,getCaptionInfo.sec</sec:X_ProductCap>
                    $iconXml
                    <serviceList>
                        <service>
                            <serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType>
                            <serviceId>urn:upnp-org:serviceId:ContentDirectory</serviceId>
                            <controlURL>/soap/ContentDirectory</controlURL>
                            <eventSubURL>/soap/ContentDirectory/Event</eventSubURL>
                            <SCPDURL>/scpd/ContentDirectory.xml</SCPDURL>
                        </service>
                        <service>
                            <serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
                            <serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId>
                            <controlURL>/soap/ConnectionManager</controlURL>
                            <eventSubURL>/soap/ConnectionManager/Event</eventSubURL>
                            <SCPDURL>/scpd/ConnectionManager.xml</SCPDURL>
                        </service>
                    </serviceList>
                </device>
            </root>
        """.trimIndent()
        return HttpResponse(200, "text/xml", body = xml)
    }

    private suspend fun handleContentDirectory(request: HttpRequest): HttpResponse {
        if (request.method != HttpMethod.POST) return HttpResponse(405)
        val soapAction = request.headers.entries.find { it.key.equals("SOAPAction", ignoreCase = true) }?.value
            ?.replace("\"", "") ?: return HttpResponse(400)
        if (soapAction.endsWith("Browse")) {
            return handleBrowse(request)
        }
        return HttpResponse(500, body = soapError("InvalidAction"))
    }

    private suspend fun handleBrowse(request: HttpRequest): HttpResponse {
        val body = request.body ?: return HttpResponse(400)
        val args = parseSoapBody(body)
        val rawObjectId = args["ObjectID"] ?: "0"
        val objectId = try { java.net.URLDecoder.decode(rawObjectId, "UTF-8") } catch (e: Exception) { rawObjectId }
        val browseFlag = args["BrowseFlag"] ?: "BrowseDirectChildren"
        val startIndex = args["StartingIndex"]?.toIntOrNull() ?: 0
        val count = args["RequestedCount"]?.toIntOrNull() ?: 20

        try {
            val list: List<MediaObject>
            if (browseFlag == "BrowseMetadata") {
                val metadata = config.contentProvider?.getMetadata(objectId)
                list = if (metadata != null) listOf(metadata) else emptyList()
            } else {
                list = config.contentProvider?.list(objectId) ?: emptyList()
            }
            val totalMatches = list.size
            val slicedList = if (browseFlag == "BrowseMetadata" || count == 0) list else list.drop(startIndex).take(count)
            val didlXml = generateDidl(slicedList)
            val numberReturned = slicedList.size

            val responseBody = """
                <u:BrowseResponse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1">
                    <Result>${escapeXml(didlXml)}</Result>
                    <NumberReturned>$numberReturned</NumberReturned>
                    <TotalMatches>$totalMatches</TotalMatches>
                    <UpdateID>1</UpdateID>
                </u:BrowseResponse>
            """.trimIndent()
            return HttpResponse(200, mimeType = "text/xml", body = wrapSoap(responseBody))
        } catch (e: Exception) {
            DlnaLogger.e(tag, "Browse Error", e)
            return HttpResponse(500, body = soapError("ActionFailed"))
        }
    }

    private fun handleConnectionManager(request: HttpRequest): HttpResponse {
        // SourceProtocolInfo MUST match the strings used in generateDidl
        val protocols = listOf(
            "http-get:*:image/jpeg:$THUMB_FLAGS",
            "http-get:*:image/jpeg:$THUMB_SM_FLAGS",
            "http-get:*:video/mp4:*",
            "http-get:*:video/x-matroska:*",
            "http-get:*:video/mpeg:*",
            "http-get:*:text/srt:*"
        ).joinToString(",")

        val body = """
            <u:GetProtocolInfoResponse xmlns:u="urn:schemas-upnp-org:service:ConnectionManager:1">
                <Source>$protocols</Source>
                <Sink></Sink>
            </u:GetProtocolInfoResponse>
        """.trimIndent()
        return HttpResponse(200, mimeType = "text/xml", body = wrapSoap(body))
    }

    private fun encodeId(id: String): String {
        return if (id == "0" || id == "-1") id
        else java.net.URLEncoder.encode(id, "UTF-8")
    }

    private fun generateDidl(items: List<MediaObject>): String {
        val ip = networkInfo.getCurrentIpAddress() ?: "127.0.0.1"
        val baseUrl = "http://$ip:$boundPort"

        val sb = StringBuilder()
        sb.append("<DIDL-Lite ")
        sb.append("xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" ")
        sb.append("xmlns:dc=\"http://purl.org/dc/elements/1.1/\" ")
        sb.append("xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" ")
        sb.append("xmlns:dlna=\"urn:schemas-dlna-org:metadata-1-0/\" ")
        sb.append("xmlns:sec=\"http://www.sec.co.kr/dlna\">") // Corrected Samsung Namespace

        items.forEach { obj ->
            val id = encodeId(obj.id)
            val parent = encodeId(obj.parentId ?: "-1")
            val title = escapeXml(obj.title)

            if (obj is MediaContainer) {
                val childCountAttr = if (obj.childCount != null) " childCount=\"${obj.childCount}\"" else ""
                val isSearchable = if (obj.searchable) "1" else "0"
                sb.append("<container id=\"$id\" parentID=\"$parent\" restricted=\"1\" searchable=\"$isSearchable\"$childCountAttr>")
                sb.append("<dc:title>$title</dc:title>")
                sb.append("<upnp:class>object.container.storageFolder</upnp:class>")
                sb.append("</container>")
            } else if (obj is MediaItem) {
                val upnpClass = obj.upnpClass
                val rawDateAttr = if (obj.date != null) " rawDate=\"${obj.date / 1000}\"" else ""

                sb.append("<item id=\"$id\" parentID=\"$parent\" restricted=\"1\"$rawDateAttr>")
                sb.append("<dc:title>$title</dc:title>")
                if (obj.date != null) sb.append("<dc:date>${formatDate(obj.date)}</dc:date>")
                sb.append("<upnp:class>$upnpClass</upnp:class>")

                val videoRes = obj.resources.firstOrNull()
                val subtitleRes = obj.resources.find { it.mimeType == "text/srt" || it.mimeType == "text/vtt" }

                // 1. Video Resource
                videoRes?.let { res ->
                    val mime = res.mimeType
                    val url = "$baseUrl${res.uri}"
                    val sizeAttr = if (res.size != null && res.size > 0) " size=\"${res.size}\"" else ""
                    val durationAttr = if (res.duration != null) " duration=\"${formatDuration(res.duration.inWholeMilliseconds)}\"" else ""
                    val resAttr = if (res.resolution != null) " resolution=\"${res.resolution}\"" else ""
                    sb.append("<res protocolInfo=\"http-get:*:$mime:$VIDEO_FLAGS\"$sizeAttr$durationAttr$resAttr>$url</res>")
                }

                // 2. Subtitle Resource & Samsung-specific Tag
                subtitleRes?.let { sub ->
                    val url = "$baseUrl${sub.uri}"
                    sb.append("<res protocolInfo=\"http-get:*:text/srt:*\">$url</res>")
                    sb.append("<sec:CaptionInfoEx sec:type=\"srt\">$url</sec:CaptionInfoEx>")
                }

                // 3. Thumbnail Resources (Updated with Flags and correct upnp:albumArtURI)
                obj.thumbnail?.let { thumb ->
                    val thumbUrl = "$baseUrl${thumb.uri}"

                    // Main thumbnail tag (Corrected Casing and No Nesting)
                    sb.append("<upnp:albumArtURI dlna:profileID=\"JPEG_SM\">$thumbUrl</upnp:albumArtURI>")

                    // Resource 1: JPEG_TN with Samsung Flags
                    sb.append("<res protocolInfo=\"http-get:*:image/jpeg:$THUMB_FLAGS\">$thumbUrl</res>")

                    // Resource 2: JPEG_SM with Samsung Flags and Resolution (Matches MiniDLNA Detail view)
                    sb.append("<res dlna:profileID=\"JPEG_SM\" protocolInfo=\"http-get:*:image/jpeg:$THUMB_SM_FLAGS\" resolution=\"320x320\">$thumbUrl</res>")
                }

                sb.append("</item>")
            }
        }
        sb.append("</DIDL-Lite>")
        Log.d("MediaServerController", "GENERATED DIDL>>>")
        Log.d("MediaServerController", sb.toString().toCharArray().joinToString(" "))
        return sb.toString()
    }

    private fun formatDuration(millis: Long): String {
        val seconds = millis / 1000
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return String.format("%d:%02d:%02d.000", h, m, s)
    }

    private fun formatDate(millis: Long): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        return sdf.format(java.util.Date(millis))
    }

    private fun startAdvertising() {
        advJob = dlnaScope.launch(Dispatchers.IO) {
            while (isActive) {
                sendSsdp(alive = true)
                delay(10_000)
            }
        }
    }

    private suspend fun sendSsdp(alive: Boolean) {
        val ip = networkInfo.getCurrentIpAddress() ?: return
        val location = "http://$ip:$boundPort/description.xml"
        val nts = if (alive) "ssdp:alive" else "ssdp:byebye"
        val targets = listOf("upnp:rootdevice", serverUuid, "urn:schemas-upnp-org:device:MediaServer:1")
        targets.forEach { nt ->
            val packet = buildSsdpPacket(nt, serverUuid, location, nts)
            try { ssdpTransport.send(packet) } catch (e: Exception) {}
        }
    }

    private fun buildSsdpPacket(nt: String, usnUuid: String, location: String, nts: String): String {
        val usn = if (nt == usnUuid) usnUuid else "$usnUuid::$nt"
        return StringBuilder()
            .append("NOTIFY * HTTP/1.1\r\n")
            .append("HOST: 239.255.255.250:1900\r\n")
            .append("CACHE-CONTROL: max-age=1800\r\n")
            .append("LOCATION: $location\r\n")
            .append("NT: $nt\r\n")
            .append("NTS: $nts\r\n")
            .append("SERVER: Android/1.0 DLNA-Lib/1.0 UPnP/1.0\r\n")
            .append("USN: $usn\r\n")
            .append("\r\n")
            .toString()
    }

    private fun wrapSoap(innerXml: String): String {
        return """<?xml version="1.0"?>
                  <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                    <s:Body>$innerXml</s:Body>
                  </s:Envelope>""".trimIndent()
    }

    private fun soapError(code: String): String {
        return wrapSoap("""
            <s:Fault>
                <faultcode>s:Client</faultcode>
                <faultstring>UPnPError</faultstring>
                <detail>
                    <UPnPError xmlns="urn:schemas-upnp-org:control-1-0">
                        <errorCode>501</errorCode>
                        <errorDescription>$code</errorDescription>
                    </UPnPError>
                </detail>
            </s:Fault>
        """)
    }

    private fun parseSoapBody(xml: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(InputSource(StringReader(xml)))
            doc.documentElement.normalize()
            val body = doc.getElementsByTagNameNS("*", "Body").item(0) as? Element
            var action: Element? = null
            if (body != null) {
                val children = body.childNodes
                for (i in 0 until children.length) {
                    val node = children.item(i)
                    if (node.nodeType == org.w3c.dom.Node.ELEMENT_NODE) {
                        action = node as Element
                        break
                    }
                }
            }
            if (action != null) {
                val children = action.childNodes
                for (i in 0 until children.length) {
                    val node = children.item(i)
                    if (node is Element) map[node.localName] = node.textContent
                }
            }
        } catch (e: Exception) {
            DlnaLogger.w(tag, "SOAP Parse Error: ${e.message}")
        }
        return map
    }

    private fun escapeXml(input: String): String {
        return input.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}