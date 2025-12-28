package com.example.mysecondapp.dlna_lib

import android.util.Log
import com.example.mysecondapp.dlna_lib.api.*
import com.example.mysecondapp.dlna_lib.android.server.NanoHttpServerWrapper
import com.example.mysecondapp.dlna_lib.core.discovery.SsdpHandler
import com.example.mysecondapp.dlna_lib.core.models.*
import com.example.mysecondapp.dlna_lib.core.services.*
import com.example.mysecondapp.dlna_lib.core.soap.SoapClient
import com.example.mysecondapp.dlna_lib.platform.*
import com.example.mysecondapp.dlna_lib.platform.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.ByteArrayInputStream
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

object DlnaManager {

    private var impl: Impl? = null

    fun start(config: DlnaConfig, platform: DlnaPlatform) {
        if (impl == null) impl = Impl(config, platform).also { it.start() }
    }

    fun stop() {
        impl?.stop()
        impl = null
    }

    val devices get() = impl!!.deviceRegistry
    val browser get() = impl!!.browseApi
    val playback get() = impl!!.playbackApi
    val mediaServer get() = impl!!.mediaServerApi

    // ---------------------------------------------------------------------

    private class Impl(
        val config: DlnaConfig,
        val platform: DlnaPlatform
    ) {
        private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        private val ssdp = SsdpHandler(platform.ssdpTransport, scope)
        private val soapClient = SoapClient()

        private val SERVER_UUID = "uuid:${UUID.randomUUID()}"

        private var avTransport: AvTransportService? = null
        private var renderingControl: RenderingControlService? = null

        // ================= DEVICE REGISTRY =================

        val deviceRegistry = object : DeviceRegistry {
            override val devices: Flow<List<Device>> = ssdp.devices
            override fun getDevice(deviceId: String) =
                ssdp.devices.value.find { it.udn == deviceId }

            override fun getMediaServers() =
                ssdp.devices.value.filter { it.deviceType.contains("MediaServer") }

            override fun getMediaRenderers() =
                ssdp.devices.value.filter { it.deviceType.contains("MediaRenderer") }
        }

        // ================= BROWSE API (CLIENT) =================

        val browseApi = object : BrowseApi {
            override suspend fun browse(
                deviceId: String,
                containerId: String,
                startIndex: Int,
                count: Int
            ): BrowseResult {
                val device = deviceRegistry.getDevice(deviceId) ?: return empty()
                val svc = device.services.find { it.serviceType.contains("ContentDirectory") } ?: return empty()
                return ContentDirectoryService(svc, soapClient).browse(containerId, startIndex, count)
            }

            override suspend fun browseRoot(deviceId: String) =
                browse(deviceId, "0", 0, 100)

            private fun empty() = BrowseResult(emptyList(), emptyList(), 0, 0)
        }

        // ================= MEDIA SERVER API =================

        val mediaServerApi = object : MediaServerApi {
            override fun isRunning() = config.enableMediaServer
            override fun getServerDevice(): Device? = null
            override fun refreshContent() {}
        }

        // ================= PLAYBACK API =================

        val playbackApi = object : PlaybackApi {
            override val playbackState = MutableStateFlow(
                PlaybackState(TransportState.STOPPED, null, null, null, null, null, null)
            )

            override suspend fun setRenderer(deviceId: String) {
                val device = deviceRegistry.getDevice(deviceId) ?: return
                val avt = device.services.find { it.serviceType.contains("AVTransport") }
                val rc = device.services.find { it.serviceType.contains("RenderingControl") }

                avt?.let { avTransport = AvTransportService(it, soapClient) {} }
                rc?.let { renderingControl = RenderingControlService(it, soapClient) { _, _ -> } }
            }

            override suspend fun play(mediaItem: MediaItem, speed: String) {
                val uri = mediaItem.resources.firstOrNull()?.uri ?: return
                avTransport?.setAvTransportUri(uri, mediaItem.title, "video/mp4")
                avTransport?.play()
            }

            override suspend fun pause() = avTransport?.pause() ?: Unit
            override suspend fun stop() = avTransport?.stop() ?: Unit
            override suspend fun seek(position: Duration) {}
            override suspend fun seekToByte(byteOffset: Long) {}
            override suspend fun setVolume(volume: Int) = renderingControl?.setVolume(volume) ?: Unit
            override suspend fun setMute(muted: Boolean) = renderingControl?.setMute(muted) ?: Unit
        }

        // ================= START / STOP =================

        fun start() {
            platform.httpServer.start()

            // SOAP control
            platform.httpServer.registerHandler("/ctl/ContentDir", object : HttpHandler {
                override suspend fun handle(req: HttpRequest): HttpResponse {
                    val body = req.body?.bufferedReader()?.readText() ?: ""
                    return handleBrowse(body)
                }
            })

            // SCPD
            platform.httpServer.registerHandler("/ContentDirectory.xml", object : HttpHandler {
                override suspend fun handle(req: HttpRequest) =
                    xmlResponse(serviceDesc())
            })

            // Root device
            platform.httpServer.registerHandler("/rootDesc.xml", object : HttpHandler {
                override suspend fun handle(req: HttpRequest) =
                    xmlResponse(rootDesc())
            })

            // Content streaming
            platform.httpServer.registerHandler("/content/", contentHandler())

            ssdp.enableServer(getBaseUrl(), SERVER_UUID)
            ssdp.start()
        }

        fun stop() {
            ssdp.stop()
            scope.cancel()
            platform.httpServer.stop()
        }

        // ================= SOAP BROWSE (SERVER) =================

        private suspend fun handleBrowse(body: String): HttpResponse {
            val objectId = extract(body, "ObjectID") ?: "0"
            val flag = extract(body, "BrowseFlag") ?: "BrowseDirectChildren"
            Log.d("DlnaManager", "DLNA handleBrowse: objectId=$objectId, flag=$flag")

            val provider = config.contentProvider

            val objects: List<MediaObject> = when (flag) {

                // ===== METADATA =====
                "BrowseMetadata" -> {
                    if (objectId == "0") {
                        listOf(
                            MediaContainer(
                                id = "0",
                                parentId = "0",
                                title = config.serverName,
                                childCount = provider?.listBlocking("0")?.size ?: 0, // <-- use listBlocking
                                searchable = true
                            )
                        )
                    } else {
                        provider?.listBlocking(objectId)
                            ?.filterIsInstance<MediaContainer>()
                            ?.firstOrNull { it.id == objectId }
                            ?.let { listOf(it) }
                            ?: emptyList()
                    }
                }

                // ===== CHILDREN =====
                else -> {
                    when (objectId) {
                        "0" -> provider?.listBlocking("0") ?: emptyList()           // <-- use listBlocking
                        "video-root" -> provider?.listBlocking("video-root") ?: emptyList() // <-- use listBlocking
                        else -> provider?.listBlocking(objectId) ?: emptyList()     // <-- use listBlocking
                    }
                }
            }


            val didlXml = escapeXml(didl(objects))
//            val didlXml = didl(objects)
            val xml = """
                <?xml version="1.0" encoding="utf-8"?>
                <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
                  <s:Body>
                    <u:BrowseResponse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1">
                      <Result>$didlXml</Result>
                      <NumberReturned>${objects.size}</NumberReturned>
                      <TotalMatches>${objects.size}</TotalMatches>
                      <UpdateID>1</UpdateID>
                    </u:BrowseResponse>
                  </s:Body>
                </s:Envelope>
            """.trimIndent()

            Log.d("DLNA", "Browse($objectId) -> ${objects.size} objects")
            objects.forEach {
                Log.d("DLNA", "  ${it::class.simpleName} id=${it.id} parent=${it.parentId}")
            }


            return xmlResponse(xml)
        }




        // ================= HELPERS =================

        private fun contentHandler() = object : HttpHandler {
            override suspend fun handle(req: HttpRequest): HttpResponse {
                val id = req.path.substringAfter("/content/").substringBefore(".")
                val src = config.contentProvider?.openMedia(id) ?: return HttpResponse(404, emptyMap(), null)
                return HttpResponse(
                    200,
                    mapOf("Content-Type" to "video/mp4", "Content-Length" to src.size.toString()),
                    src.openFull()
                )
            }
        }

        private fun rootDesc(): String {
            val base = getBaseUrl()
            return """
                <root xmlns="urn:schemas-upnp-org:device-1-0">
                  <device>
                    <deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>
                    <friendlyName>${config.serverName}</friendlyName>
                    <UDN>$SERVER_UUID</UDN>
                    <serviceList>
                      <service>
                        <serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType>
                        <serviceId>urn:upnp-org:serviceId:ContentDirectory</serviceId>
                        <SCPDURL>/ContentDirectory.xml</SCPDURL>
                        <controlURL>/ctl/ContentDir</controlURL>
                        <eventSubURL>/evt/ContentDir</eventSubURL>
                      </service>
                    </serviceList>
                  </device>
                  <URLBase>$base</URLBase>
                </root>
            """.trimIndent()
        }

        private fun serviceDesc(): String = """
            <scpd xmlns="urn:schemas-upnp-org:service-1-0">
              <actionList>
                <action>
                  <name>Browse</name>
                </action>
              </actionList>
            </scpd>
        """.trimIndent()

        private fun didl(items: List<MediaObject>): String {
            val base = getBaseUrl()

            return buildString {
                append(
                    """<DIDL-Lite
                        xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"
                        xmlns:dc="http://purl.org/dc/elements/1.1/"
                        xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">
                    """.trimIndent()
                )

                items.forEach { obj ->
                    when (obj) {
                        is MediaContainer -> {
                            append(
                                """
                                <container id="${obj.id}" parentID="${obj.parentId}" restricted="1">
                                  <dc:title>${obj.title}</dc:title>
                                  <upnp:class>object.container.storageFolder</upnp:class>
                                  <childCount>${obj.childCount}</childCount>
                                </container>
                                """.trimIndent()
                            )
                        }

                        is MediaItem -> {
                            val protocolInfo =
                                "http-get:*:video/mp4:DLNA.ORG_OP=01;" +
                                        "DLNA.ORG_CI=0;" +
                                        "DLNA.ORG_FLAGS=01700000000000000000000000000000"

                            append(
                                """
                                <item id="${obj.id}" parentID="${obj.parentId}" restricted="1">
                                  <dc:title>${obj.title}</dc:title>
                                  <upnp:class>object.item.videoItem</upnp:class>
                                  <res protocolInfo="$protocolInfo">
                                    $base/content/${obj.id}
                                  </res>
                                </item>
                                """.trimIndent()
                            )
                        }
                    }
                }

                append("</DIDL-Lite>")
            }
        }


        private fun xmlResponse(xml: String): HttpResponse {
            val bytes = xml.toByteArray()
            return HttpResponse(
                200,
                mapOf("Content-Type" to "text/xml; charset=\"utf-8\"", "Content-Length" to bytes.size.toString()),
                ByteArrayInputStream(bytes)
            )
        }

        private fun extract(xml: String, tag: String): String? =
            xml.substringAfter("<$tag>", "").substringBefore("</$tag>", "").ifEmpty { null }

        private fun escapeXml(s: String) =
            s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        private fun getBaseUrl() =
            (platform.httpServer as NanoHttpServerWrapper).getBaseUrl()
    }
}
