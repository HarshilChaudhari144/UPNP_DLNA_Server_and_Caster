package com.example.mysecondapp.dlna_lib.core.mediaserver

import com.example.mysecondapp.dlna_lib.api.DlnaConfig
import com.example.mysecondapp.dlna_lib.api.media.*
import com.example.mysecondapp.dlna_lib.core.lifecycle.dlnaScope
import com.example.mysecondapp.dlna_lib.core.logging.DlnaLogger
import com.example.mysecondapp.dlna_lib.platform.*
import kotlinx.coroutines.*
import java.net.InetAddress
import java.util.UUID
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.time.Duration

internal class MediaServerController(
    private val config: DlnaConfig,
    private val httpServer: HttpServer,
    private val mimeResolver: MimeTypeResolver,
    private val networkInfo: NetworkInfoProvider,
    private val ssdpTransport: SsdpTransport
) {
    private val tag = "MediaServerController"
    // Helper to format Duration for <res> tag attribute
    private fun formatDuration(duration: Duration): String {
        val totalSeconds = duration.inWholeSeconds
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return String.format("%02d:%02d:%02d", h, m, s)
    }

    private val mediaHandler = MediaHttpHandler(config.contentProvider!!, config.thumbnailProvider, mimeResolver)
    private var isRunning = false
    private var boundPort = 0
    private var serverUuid = "uuid:" + UUID.randomUUID().toString()
    private var advJob: Job? = null

    fun start() {
        if (isRunning) return
        try {
            httpServer.start(0, ServerRouter())
            boundPort = httpServer.getPort()
            isRunning = true
            DlnaLogger.d(tag, "Media Server started on port $boundPort")
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

    fun respondToSearch(packet: String, address: InetAddress, port: Int) {
        if (!isRunning) return

        val ip = networkInfo.getCurrentIpAddress() ?: return
        val location = "http://$ip:$boundPort/description.xml"

        val targets = listOf(
            "upnp:rootdevice",
            serverUuid,
            "urn:schemas-upnp-org:device:MediaServer:1"
        )

        dlnaScope.launch(Dispatchers.IO) {
            targets.forEach { st ->
                val response = """
                    HTTP/1.1 200 OK
                    CACHE-CONTROL: max-age=1800
                    DATE: ${java.util.Date()}
                    EXT:
                    LOCATION: $location
                    SERVER: Android/1.0 DLNA-Lib/1.0 UPnP/1.0
                    ST: $st
                    USN: ${if(st == serverUuid) st else "$serverUuid::$st"}
                    
                """.trimIndent().replace("\n", "\r\n") + "\r\n"

                ssdpTransport.sendDirect(response, address, port)
                delay(50)
            }
        }
    }

    private inner class ServerRouter : HttpHandler {
        override suspend fun handle(request: HttpRequest): HttpResponse {
            val path = request.path
            return when {
                path == "/description.xml" -> serveDescription()

                // FIX 1: Handle SCPD Requests (ContentDirectory)
                path == "/scpd/ContentDirectory.xml" -> serveScpd("ContentDirectory")

                // FIX 1: Handle SCPD Requests (ConnectionManager)
                path == "/scpd/ConnectionManager.xml" -> serveScpd("ConnectionManager")

                path == "/soap/ContentDirectory" -> handleContentDirectory(request)
                path == "/soap/ConnectionManager" -> handleConnectionManager(request)
                path.startsWith("/content/") || path.startsWith("/thumb/") -> mediaHandler.handle(request)
                else -> HttpResponse(404)
            }
        }
    }

    // FIX 1: New function to serve minimal SCPD XML
    private fun serveScpd(serviceName: String): HttpResponse {
        val serviceType = "urn:schemas-upnp-org:service:$serviceName:1"

        // Minimal valid SCPD XML with only mandatory tags
        val xml = "<?xml version=\"1.0\"?>\n" +
                """
            <scpd xmlns="urn:schemas-upnp-org:service-1-0">
                <specVersion>
                    <major>1</major>
                    <minor>0</minor>
                </specVersion>
                <actionList>
                    ${if (serviceName == "ContentDirectory") """
                        <action><name>Browse</name></action>
                        <action><name>GetSearchCapabilities</name></action>
                        <action><name>GetSortCapabilities</name></action>
                    """ else ""}
                </actionList>
                <serviceStateTable>
                    <stateVariable sendEvents="yes">
                        <name>LastChange</name>
                        <dataType>string</dataType>
                    </stateVariable>
                </serviceStateTable>
            </scpd>
        """.trimIndent()

        return HttpResponse(200, mimeType = "text/xml", body = xml)
    }

    private fun serveDescription(): HttpResponse {
        val ip = networkInfo.getCurrentIpAddress() ?: "127.0.0.1"
        val xml = "<?xml version=\"1.0\"?>\n" +
                """
            <root xmlns="urn:schemas-upnp-org:device-1-0">
                <specVersion><major>1</major><minor>0</minor></specVersion>
                <device>
                    <deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>
                    <friendlyName>${config.serverName}</friendlyName>
                    <manufacturer>DLNA Lib</manufacturer>
                    <modelName>Kotlin Media Server</modelName>
                    <UDN>$serverUuid</UDN>
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

        return HttpResponse(200, mimeType = "text/xml", body = xml)
    }

    private suspend fun handleContentDirectory(request: HttpRequest): HttpResponse {
        if (request.method != HttpMethod.POST) return HttpResponse(405)
        val soapAction = request.headers.entries.find { it.key.equals("SOAPAction", ignoreCase = true) }?.value
            ?.replace("\"", "") ?: return HttpResponse(400)

        if (soapAction.endsWith("Browse")) return handleBrowse(request)
        return HttpResponse(500, body = soapError("InvalidAction"))
    }

    private suspend fun handleBrowse(request: HttpRequest): HttpResponse {
        val body = request.body ?: return HttpResponse(400)
        val args = parseSoapBody(body)
        val objectId = args["ObjectID"] ?: "0"
        val startIndex = args["StartingIndex"]?.toIntOrNull() ?: 0
        val count = args["RequestedCount"]?.toIntOrNull() ?: 20

        try {
            val list = config.contentProvider?.list(objectId) ?: emptyList()
            val totalMatches = list.size
            val slicedList = if (count == 0) list else list.drop(startIndex).take(count)
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
        val body = """
            <u:GetProtocolInfoResponse xmlns:u="urn:schemas-upnp-org:service:ConnectionManager:1">
                <Source>http-get:*:*:*</Source>
                <Sink></Sink>
            </u:GetProtocolInfoResponse>
        """.trimIndent()
        return HttpResponse(200, mimeType = "text/xml", body = wrapSoap(body))
    }

    // FIX 2: Generate metadata with DLNA Flags and ALL <res> ATTRIBUTES
    private fun generateDidl(items: List<MediaObject>): String {
        val ip = networkInfo.getCurrentIpAddress() ?: "127.0.0.1"
        val baseUrl = "http://$ip:$boundPort"
        val sb = StringBuilder()
        sb.append("""<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">""")

        items.forEach { obj ->
            val id = escapeXml(obj.id)
            val parent = escapeXml(obj.parentId ?: "-1")
            val title = escapeXml(obj.title)

            if (obj is MediaContainer) {
                sb.append("""<container id="$id" parentID="$parent" restricted="1" searchable="0">""")
                sb.append("<dc:title>$title</dc:title>")
                sb.append("<upnp:class>object.container</upnp:class>")
                sb.append("</container>")
            } else if (obj is MediaItem) {
                val upnpClass = obj.upnpClass
                val res = obj.resources.firstOrNull()
                val mime = res?.mimeType ?: "application/octet-stream"
                val url = "$baseUrl/content/${id}"

                val sizeAttr = if (res?.size != null) """ size="${res.size}"""" else ""
                val durAttr = if (res?.duration != null) """ duration="${formatDuration(res.duration)}"""" else ""
                val resAttr = if (res?.resolution != null) """ resolution="${res.resolution}"""" else ""

                val flags = "DLNA.ORG_PN=*;DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
                val protocolInfo = "http-get:*:$mime:$flags"

                sb.append("""<item id="$id" parentID="$parent" restricted="1">""")
                sb.append("<dc:title>$title</dc:title>")
                sb.append("<upnp:class>$upnpClass</upnp:class>")

                sb.append("""<res protocolInfo="$protocolInfo"$sizeAttr$durAttr$resAttr>$url</res>""")

                if (config.thumbnailProvider != null) {
                    val thumbUrl = "$baseUrl/thumb/${id}"
                    sb.append("<upnp:albumArtURI>$thumbUrl</upnp:albumArtURI>")
                }
                sb.append("</item>")
            }
        }
        sb.append("</DIDL-Lite>")
        return sb.toString()
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
            val usn = if (nt == serverUuid) serverUuid else "$serverUuid::$nt"
            val packet = """
                NOTIFY * HTTP/1.1
                HOST: 239.255.255.250:1900
                CACHE-CONTROL: max-age=1800
                LOCATION: $location
                NT: $nt
                NTS: $nts
                SERVER: Android/1.0 DLNA-Lib/1.0 UPnP/1.0
                USN: $usn
                
            """.trimIndent().replace("\n", "\r\n")
            try { ssdpTransport.send(packet) } catch (e: Exception) {}
        }
    }

    private fun wrapSoap(innerXml: String): String {
        return "<?xml version=\"1.0\"?>\n" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\n" +
                "    <s:Body>$innerXml</s:Body>\n" +
                "</s:Envelope>"
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
            val action = body?.childNodes?.item(1) as? Element
            if (action != null) {
                val children = action.childNodes
                for (i in 0 until children.length) {
                    val node = children.item(i)
                    if (node is Element) map[node.localName] = node.textContent
                }
            }
        } catch (e: Exception) { }
        return map
    }

    private fun escapeXml(input: String): String {
        return input.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
    }
}