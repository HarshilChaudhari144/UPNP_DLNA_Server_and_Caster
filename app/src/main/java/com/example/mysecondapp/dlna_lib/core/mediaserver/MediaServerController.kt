package com.example.mysecondapp.dlna_lib.core.mediaserver

import android.util.Log
import com.example.mysecondapp.dlna_lib.api.DlnaConfig
import com.example.mysecondapp.dlna_lib.api.media.*
import com.example.mysecondapp.dlna_lib.core.lifecycle.dlnaScope
import com.example.mysecondapp.dlna_lib.core.logging.DlnaLogger
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
    private val ssdpTransport: SsdpTransport
) {
    private val tag = "MediaServerController"

    // Functional Handler for streaming (Byte-Range support)
    private val mediaHandler = MediaHttpHandler(
        config.contentProvider!!, // Guaranteed by config check in Engine
        config.thumbnailProvider
    )

    // State
    private var isRunning = false
    private var boundPort = 0

    // CHANGE: Use UDN from config. Ensure it starts with "uuid:"
    private val serverUuid = if (config.serverUdn.startsWith("uuid:")) config.serverUdn else "uuid:${config.serverUdn}"

    private var advJob: Job? = null

    fun start() {
        if (isRunning) return

        try {
            // 1. Start HTTP Server on random port (0)
            httpServer.start(0, ServerRouter())
            boundPort = httpServer.getPort()
            isRunning = true

            DlnaLogger.d(tag, "Media Server started on port $boundPort with UDN: $serverUuid")

            // 2. Start SSDP Advertising Loop
            startAdvertising()

        } catch (e: Exception) {
            DlnaLogger.e(tag, "Failed to start Media Server", e)
            stop()
        }
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false

        // Send ByeBye before killing transport
        runBlocking { sendSsdp(alive = false) }

        advJob?.cancel()
        httpServer.stop()
        DlnaLogger.d(tag, "Media Server stopped")
    }

    // --- 1. ROUTING LOGIC ---

    private inner class ServerRouter : HttpHandler {
        override suspend fun handle(request: HttpRequest): HttpResponse {
            val path = request.path

            return when {
                // Device Description
                path == "/description.xml" -> serveDescription()

                // ContentDirectory Service (Browsing)
                path == "/soap/ContentDirectory" -> handleContentDirectory(request)

                // ConnectionManager Service (Minimal stub)
                path == "/soap/ConnectionManager" -> handleConnectionManager(request)

                // Media Streaming & Thumbnails (Delegate)
                path.startsWith("/content/") || path.startsWith("/thumb/") -> mediaHandler.handle(request)

                else -> HttpResponse(404)
            }
        }
    }

    // --- 2. DEVICE DESCRIPTION ---

    private fun serveDescription(): HttpResponse {
        val ip = networkInfo.getCurrentIpAddress() ?: "127.0.0.1"
        // Note: Using the actual UUID for the device ensures consistency

        val xml = """
            <?xml version="1.0"?>
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

        return HttpResponse(
            statusCode = 200,
            mimeType = "text/xml",
            body = xml
        )
    }

    // --- 3. SOAP HANDLERS ---

    private suspend fun handleContentDirectory(request: HttpRequest): HttpResponse {
        if (request.method != HttpMethod.POST) return HttpResponse(405)

        val soapAction = request.headers.entries.find { it.key.equals("SOAPAction", ignoreCase = true) }?.value
            ?.replace("\"", "") // Remove quotes: "urn:..." -> urn:...
            ?: return HttpResponse(400)

        // We only really support Browse
        if (soapAction.endsWith("Browse")) {
            return handleBrowse(request)
        }

        return HttpResponse(500, body = soapError("InvalidAction"))
    }

    private suspend fun handleBrowse(request: HttpRequest): HttpResponse {
        val body = request.body ?: return HttpResponse(400)

        // Parse Arguments
        val args = parseSoapBody(body)
        val objectId = args["ObjectID"] ?: "0"
        val startIndex = args["StartingIndex"]?.toIntOrNull() ?: 0
        val count = args["RequestedCount"]?.toIntOrNull() ?: 20

        try {
            // Fetch from App Provider
            val list = config.contentProvider?.list(objectId) ?: emptyList()

            // Pagination logic
            val totalMatches = list.size
            val slicedList = if (count == 0) list else list.drop(startIndex).take(count) // 0 means all

            // Convert to DIDL
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
        // Just return minimal valid info for GetProtocolInfo
        val body = """
            <u:GetProtocolInfoResponse xmlns:u="urn:schemas-upnp-org:service:ConnectionManager:1">
                <Source>http-get:*:*:*</Source>
                <Sink></Sink>
            </u:GetProtocolInfoResponse>
        """.trimIndent()
        return HttpResponse(200, mimeType = "text/xml", body = wrapSoap(body))
    }

    // --- 4. DIDL GENERATION ---

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
                val resource = obj.resources.firstOrNull()
                val mime = resource?.mimeType ?: "application/octet-stream"

                // CHANGE 1: Append the actual filename (or safe title) to the URL.
                // This matches your Reference File logic (Line 173).
                // Result: http://.../content/123/MyMovie.mp4
                Log.d("MediaServerController", "from generateDidl(), mime: ${mime}")
                val safeTitle = obj.title.replace("[^a-zA-Z0-9.-]".toRegex(), "_")
                Log.d("MediaServerController", "from generateDidl(), safeTitle: ${safeTitle}")
                val extension = if (mime.contains("video")) ".mp4" else if (mime.contains("audio")) ".mp3" else ".jpg"
//                val finalName = if (safeTitle.endsWith(extension)) safeTitle else "$safeTitle$extension"
                val finalName = safeTitle
                Log.d("MediaServerController", "from generateDidl(), finalName: ${finalName}")

                val url = "$baseUrl/content/$id/$finalName"

                sb.append("""<item id="$id" parentID="$parent" restricted="1">""")
                sb.append("<dc:title>$title</dc:title>")
                sb.append("<upnp:class>$upnpClass</upnp:class>")

                // CHANGE 2: Use OP=01 (Byte Seek) to match Reference File Line 99
                val dlnaFlags = "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"

                sb.append("""<res protocolInfo="http-get:*:$mime:$dlnaFlags">$url</res>""")

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

    // --- 5. SSDP ADVERTISING ---

    private fun startAdvertising() {
        advJob = dlnaScope.launch(Dispatchers.IO) {
            while (isActive) {
                sendSsdp(alive = true)
                delay(10_000) // Announce every 10 seconds (aggressive for discovery)
            }
        }
    }

    private suspend fun sendSsdp(alive: Boolean) {
        val ip = networkInfo.getCurrentIpAddress() ?: return
        val location = "http://$ip:$boundPort/description.xml"
        val nts = if (alive) "ssdp:alive" else "ssdp:byebye"

        // We must announce 3 targets: Root, DeviceUUID, DeviceType
        val targets = listOf(
            "upnp:rootdevice",
            serverUuid,
            "urn:schemas-upnp-org:device:MediaServer:1"
        )

        targets.forEach { nt ->
            val packet = buildSsdpPacket(nt, serverUuid, location, nts)
            try {
                ssdpTransport.send(packet)
            } catch (e: Exception) {
                // Ignore send errors
            }
        }
    }

    private fun buildSsdpPacket(nt: String, usnUuid: String, location: String, nts: String): String {
        // USN format: uuid:device-UUID::urn:device-type... or just uuid:device-UUID
        val usn = if (nt == usnUuid) usnUuid else "$usnUuid::$nt"

        return """
            NOTIFY * HTTP/1.1
            HOST: 239.255.255.250:1900
            CACHE-CONTROL: max-age=1800
            LOCATION: $location
            NT: $nt
            NTS: $nts
            SERVER: Android/1.0 DLNA-Lib/1.0 UPnP/1.0
            USN: $usn
            
        """.trimIndent().replace("\n", "\r\n")
    }

    // --- HELPERS ---

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

            // Look for specific arguments we know (ObjectID, etc) inside Body
            // A generic traversal is safer
            val body = doc.getElementsByTagNameNS("*", "Body").item(0) as? Element
            val action = body?.childNodes?.item(1) as? Element // First child is the Action

            if (action != null) {
                val children = action.childNodes
                for (i in 0 until children.length) {
                    val node = children.item(i)
                    if (node is Element) {
                        map[node.localName] = node.textContent
                    }
                }
            }
        } catch (e: Exception) { /* ignore */ }
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