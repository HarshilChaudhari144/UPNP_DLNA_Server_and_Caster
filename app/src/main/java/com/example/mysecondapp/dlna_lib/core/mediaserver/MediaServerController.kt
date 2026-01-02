package com.example.mysecondapp.dlna_lib.core.mediaserver

import android.util.Log
import com.example.mysecondapp.dlna_lib.api.DlnaConfig
import com.example.mysecondapp.dlna_lib.api.media.*
import com.example.mysecondapp.dlna_lib.core.lifecycle.dlnaScope
import com.example.mysecondapp.dlna_lib.core.logging.DlnaLogger
import com.example.mysecondapp.dlna_lib.core.ssdp.SsdpController // NEW IMPORT
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
    private val ssdpController: SsdpController // <--- ADDED THIS
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
//            httpServer.start(0, ServerRouter())
            httpServer.start(8300, ServerRouter())
            boundPort = httpServer.getPort()
            isRunning = true

            DlnaLogger.d(tag, "Media Server started on port $boundPort with UDN: $serverUuid")

            // 2. FIX: Register with SSDP Controller so it can respond to M-SEARCH requests
            val ip = networkInfo.getCurrentIpAddress() ?: "127.0.0.1"
            val location = "http://$ip:$boundPort/description.xml"
            ssdpController.setServerInfo(serverUuid, location) // <--- ADDED THIS

            // 3. Start SSDP Advertising Loop (for NOTIFY messages)
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

                // SCPD (Service Description)
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
            <specVersion>
                <major>1</major>
                <minor>0</minor>
            </specVersion>

            <actionList>
                <action>
                    <name>Browse</name>
                    <argumentList>
                        <argument>
                            <name>ObjectID</name>
                            <direction>in</direction>
                            <relatedStateVariable>A_ARG_TYPE_ObjectID</relatedStateVariable>
                        </argument>
                        <argument>
                            <name>BrowseFlag</name>
                            <direction>in</direction>
                            <relatedStateVariable>A_ARG_TYPE_BrowseFlag</relatedStateVariable>
                        </argument>
                        <argument>
                            <name>Filter</name>
                            <direction>in</direction>
                            <relatedStateVariable>A_ARG_TYPE_Filter</relatedStateVariable>
                        </argument>
                        <argument>
                            <name>StartingIndex</name>
                            <direction>in</direction>
                            <relatedStateVariable>A_ARG_TYPE_Index</relatedStateVariable>
                        </argument>
                        <argument>
                            <name>RequestedCount</name>
                            <direction>in</direction>
                            <relatedStateVariable>A_ARG_TYPE_Count</relatedStateVariable>
                        </argument>
                        <argument>
                            <name>SortCriteria</name>
                            <direction>in</direction>
                            <relatedStateVariable>A_ARG_TYPE_SortCriteria</relatedStateVariable>
                        </argument>

                        <argument>
                            <name>Result</name>
                            <direction>out</direction>
                            <relatedStateVariable>A_ARG_TYPE_Result</relatedStateVariable>
                        </argument>
                        <argument>
                            <name>NumberReturned</name>
                            <direction>out</direction>
                            <relatedStateVariable>A_ARG_TYPE_Count</relatedStateVariable>
                        </argument>
                        <argument>
                            <name>TotalMatches</name>
                            <direction>out</direction>
                            <relatedStateVariable>A_ARG_TYPE_Count</relatedStateVariable>
                        </argument>
                        <argument>
                            <name>UpdateID</name>
                            <direction>out</direction>
                            <relatedStateVariable>A_ARG_TYPE_UpdateID</relatedStateVariable>
                        </argument>
                    </argumentList>
                </action>
            </actionList>

            <serviceStateTable>
                <stateVariable sendEvents="no">
                    <name>A_ARG_TYPE_ObjectID</name>
                    <dataType>string</dataType>
                </stateVariable>
                <stateVariable sendEvents="no">
                    <name>A_ARG_TYPE_BrowseFlag</name>
                    <dataType>string</dataType>
                </stateVariable>
                <stateVariable sendEvents="no">
                    <name>A_ARG_TYPE_Filter</name>
                    <dataType>string</dataType>
                </stateVariable>
                <stateVariable sendEvents="no">
                    <name>A_ARG_TYPE_Index</name>
                    <dataType>ui4</dataType>
                </stateVariable>
                <stateVariable sendEvents="no">
                    <name>A_ARG_TYPE_Count</name>
                    <dataType>ui4</dataType>
                </stateVariable>
                <stateVariable sendEvents="no">
                    <name>A_ARG_TYPE_SortCriteria</name>
                    <dataType>string</dataType>
                </stateVariable>
                <stateVariable sendEvents="no">
                    <name>A_ARG_TYPE_Result</name>
                    <dataType>string</dataType>
                </stateVariable>
                <stateVariable sendEvents="no">
                    <name>A_ARG_TYPE_UpdateID</name>
                    <dataType>ui4</dataType>
                </stateVariable>
            </serviceStateTable>
        </scpd>
    """.trimIndent()

        return HttpResponse(
            statusCode = 200,
            mimeType = "text/xml",
            body = xml
        )
    }

    private fun serveConnectionManagerScpd(): HttpResponse {
        val xml = """
        <?xml version="1.0"?>
        <scpd xmlns="urn:schemas-upnp-org:service-1-0">
            <specVersion>
                <major>1</major>
                <minor>0</minor>
            </specVersion>

            <actionList>
                <action>
                    <name>GetProtocolInfo</name>
                    <argumentList>
                        <argument>
                            <name>Source</name>
                            <direction>out</direction>
                            <relatedStateVariable>SourceProtocolInfo</relatedStateVariable>
                        </argument>
                        <argument>
                            <name>Sink</name>
                            <direction>out</direction>
                            <relatedStateVariable>SinkProtocolInfo</relatedStateVariable>
                        </argument>
                    </argumentList>
                </action>
            </actionList>

            <serviceStateTable>
                <stateVariable sendEvents="no">
                    <name>SourceProtocolInfo</name>
                    <dataType>string</dataType>
                </stateVariable>
                <stateVariable sendEvents="no">
                    <name>SinkProtocolInfo</name>
                    <dataType>string</dataType>
                </stateVariable>
            </serviceStateTable>
        </scpd>
    """.trimIndent()

        return HttpResponse(
            statusCode = 200,
            mimeType = "text/xml",
            body = xml
        )
    }


    // --- 2. DEVICE DESCRIPTION ---

    private fun serveDescription(): HttpResponse {
        val ip = networkInfo.getCurrentIpAddress() ?: "127.0.0.1"

        // FIX: Only show icon block if we have a provider
        val iconXml = if (config.thumbnailProvider != null) """
            <iconList>
                <icon>
                    <mimetype>image/png</mimetype>
                    <width>48</width><height>48</height><depth>24</depth>
                    <url>/thumb/app_icon</url>
                </icon>
            </iconList>
        """.trimIndent() else ""

        val xml = """
            <?xml version="1.0"?>
            <root xmlns="urn:schemas-upnp-org:device-1-0" xmlns:dlna="urn:schemas-dlna-org:device-1-0">
                <specVersion><major>1</major><minor>0</minor></specVersion>
                <device>
                    <deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>
                    <friendlyName>${config.serverName}</friendlyName>
                    <manufacturer>DLNA Lib</manufacturer>
                    <modelName>Kotlin Media Server</modelName>
                    <UDN>$serverUuid</UDN>
                    <dlna:X_DLNADOC>DMS-1.50</dlna:X_DLNADOC>
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

        val args = parseSoapBody(body)
        val objectId = args["ObjectID"] ?: "0"
        val browseFlag = args["BrowseFlag"] ?: "BrowseDirectChildren" // Extract Flag
        val startIndex = args["StartingIndex"]?.toIntOrNull() ?: 0
        val count = args["RequestedCount"]?.toIntOrNull() ?: 20

        try {
            val list: List<MediaObject>

            // FIX: Handle Metadata vs Children request
            if (browseFlag == "BrowseMetadata") {
                // TV wants info about THIS folder, not what's inside it
                val metadata = config.contentProvider?.getMetadata(objectId)
                list = if (metadata != null) listOf(metadata) else emptyList()
            } else {
                // TV wants content inside the folder
                list = config.contentProvider?.list(objectId) ?: emptyList()
            }

            val totalMatches = list.size

            // Slice only for DirectChildren; Metadata is always 1 item (no pagination needed)
            val slicedList = if (browseFlag == "BrowseMetadata" || count == 0) {
                list
            } else {
                list.drop(startIndex).take(count)
            }

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

    // --- 4. DIDL GENERATION (FIXED URL LOGIC) ---

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
                // FIX: Add childCount if available (some TVs hide folders without this)
                val childCountAttr = if (obj.childCount != null) " childCount=\"${obj.childCount}\"" else ""
                // FIX: Write searchable flag (static 0 or 1 based on obj.searchable)
                val isSearchable = if (obj.searchable) "1" else "0"
                sb.append("""<container id="$id" parentID="$parent" restricted="1" searchable="$isSearchable"$childCountAttr>""")
                sb.append("<dc:title>$title</dc:title>")
                // FIX: Use standard storageFolder class
                sb.append("<upnp:class>object.container.storageFolder</upnp:class>")
                sb.append("</container>")
            } else if (obj is MediaItem) {
                val upnpClass = obj.upnpClass
                val resource = obj.resources.firstOrNull()
                val mime = resource?.mimeType ?: "application/octet-stream"
                // 1. FORMAT SIZE & DURATION
                // The TV needs these attributes inside the <res> tag to show info
                val sizeAttr = if (resource?.size != null && resource.size > 0) " size=\"${resource.size}\"" else ""

                val durationAttr = if (resource?.duration != null) {
                    val durStr = formatDuration(resource.duration.inWholeMilliseconds)
                    " duration=\"$durStr\""
                } else ""
                // FIX: Add Resolution Attribute
                val resAttr = if (resource?.resolution != null) " resolution=\"${resource.resolution}\"" else ""

                // FIX: Trust existing extension if present, otherwise append based on mime
                val safeTitle = obj.title.replace("[^a-zA-Z0-9.-]".toRegex(), "_")
                val finalName = if (safeTitle.contains(".")) {
                    // Title already has an extension (e.g., "Movie.mkv")
                    safeTitle
                } else {
                    // Append extension based on mime type
                    val ext = when {
                        mime.startsWith("video") -> ".mp4" // Safest fallback for video
                        mime.startsWith("audio") -> ".mp3"
                        mime.startsWith("image") -> ".jpg"
                        else -> "" // No extension
                    }
                    "$safeTitle$ext"
                }

                val url = "$baseUrl/content/$id/$finalName"

                sb.append("""<item id="$id" parentID="$parent" restricted="1">""")
                sb.append("<dc:title>$title</dc:title>")
                // FIX: Add Date Tag
                if (obj.date != null) {
                    val dateStr = formatDate(obj.date)
                    sb.append("<dc:date>$dateStr</dc:date>")
                }
                sb.append("<upnp:class>$upnpClass</upnp:class>")

                // Flags: OP=01 (Byte Seek) for better TV compatibility
                val dlnaFlags = "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"

//                sb.append("""<res protocolInfo="http-get:*:$mime:$dlnaFlags">$url</res>""")
                sb.append("""<res protocolInfo="http-get:*:$mime:$dlnaFlags"$sizeAttr$durationAttr$resAttr>$url</res>""")

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

    private fun formatDuration(millis: Long): String {
        val seconds = millis / 1000
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return String.format("%d:%02d:%02d.000", h, m, s)
    }

    private fun formatDate(millis: Long): String {
        // Simple YYYY-MM-DD format
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        return sdf.format(java.util.Date(millis))
    }

    // --- 5. SSDP ADVERTISING (for NOTIFY) ---

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
//        val msg = "IP from startAdvertising(): $ip"
//        msg.forEachIndexed { index, ch ->
//            Log.d("MediaServerController", "[$index] '$ch'")
//        }

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
        val usn = if (nt == usnUuid) usnUuid else "$usnUuid::$nt"

        // FIX: Explicitly format with \r\n to ensure strict compliance.
        // Kotlin's trimIndent() + replace() can be risky for the final double CRLF.
        return StringBuilder()
            .append("NOTIFY * HTTP/1.1\r\n")
            .append("HOST: 239.255.255.250:1900\r\n")
            .append("CACHE-CONTROL: max-age=1800\r\n")
            .append("LOCATION: $location\r\n")
            .append("NT: $nt\r\n")
            .append("NTS: $nts\r\n")
            .append("SERVER: Android/1.0 DLNA-Lib/1.0 UPnP/1.0\r\n")
            .append("USN: $usn\r\n")
            .append("\r\n") // The second CRLF that TVs require
            .toString()
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

            val body = doc.getElementsByTagNameNS("*", "Body").item(0) as? Element

            // FIX: Find the first ELEMENT child (Action), ignoring whitespace/text nodes
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
                    if (node is Element) {
                        map[node.localName] = node.textContent
                    }
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