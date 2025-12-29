package com.example.mysecondapp.dlna_lib.core.playback

import com.example.mysecondapp.dlna_lib.api.device.Device
import com.example.mysecondapp.dlna_lib.api.device.Service
import com.example.mysecondapp.dlna_lib.api.errors.DlnaError
import com.example.mysecondapp.dlna_lib.api.media.MediaItem
import com.example.mysecondapp.dlna_lib.api.playback.PlaybackApi
import com.example.mysecondapp.dlna_lib.api.playback.PlaybackState
import com.example.mysecondapp.dlna_lib.api.playback.TransportState
import com.example.mysecondapp.dlna_lib.core.capability.RendererCapabilities
import com.example.mysecondapp.dlna_lib.core.device.DeviceRepository
import com.example.mysecondapp.dlna_lib.core.error.PublicErrorMapper
import com.example.mysecondapp.dlna_lib.core.eventing.SubscriptionManager
import com.example.mysecondapp.dlna_lib.core.logging.DlnaLogger
import com.example.mysecondapp.dlna_lib.core.soap.SoapClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal class PlaybackController(
    private val deviceRepository: DeviceRepository,
    private val soapClient: SoapClient,
    private val subscriptionManager: SubscriptionManager
) : PlaybackApi {

    private val tag = "PlaybackController"

    // The currently selected renderer device ID
    private var currentDeviceId: String? = null

    // Track subscribed services to unsubscribe later
    private var subscribedServices = mutableListOf<Service>()

    // Internal State
    private val _playbackState = MutableStateFlow(
        PlaybackState(
            transportState = TransportState.UNKNOWN,
            position = null,
            duration = null,
            speed = "1",
            volume = null,
            muted = false,
            mediaItem = null
        )
    )
    override val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    override suspend fun setRenderer(deviceId: String) {
        val device = deviceRepository.getDevice(deviceId)
            ?: throw DlnaError.Discovery("Renderer not found: $deviceId")

        // 1. Unsubscribe from previous device
        cleanupSubscriptions()

        if (!RendererCapabilities.canSeek(device)) {
            DlnaLogger.w(tag, "Selected renderer might not support playback (Missing AVTransport)")
        }

        currentDeviceId = deviceId

        // 2. Subscribe to new services (AVTransport & RenderingControl)
        subscribeToDevice(device)

        // Reset state
        _playbackState.update { it.copy(transportState = TransportState.STOPPED, mediaItem = null) }
        DlnaLogger.d(tag, "Renderer selected: ${device.friendlyName}")
    }

    override suspend fun play(mediaItem: MediaItem, speed: String) {
        val device = getActiveDevice()
        val avTransport = getAVTransport(device)

        // 1. Stop current
        try { stop() } catch (e: Exception) { /* Ignore */ }

        // 2. Resolve Resource
        val resource = mediaItem.resources.firstOrNull()
            ?: throw DlnaError.Playback("MediaItem has no playable resources")

        val metadata = buildDidlMetadata(mediaItem)

        // 3. SetAVTransportURI
        executeSoap(avTransport, "SetAVTransportURI", mapOf(
            "InstanceID" to "0",
            "CurrentURI" to resource.uri,
            "CurrentURIMetaData" to metadata
        ))

        // 4. Play
        executeSoap(avTransport, "Play", mapOf(
            "InstanceID" to "0",
            "Speed" to speed
        ))

        // Immediate optimistic update (Real state comes via Eventing)
        _playbackState.update {
            it.copy(
                transportState = TransportState.TRANSITIONING,
                mediaItem = mediaItem
            )
        }
    }

    override suspend fun pause() {
        val device = getActiveDevice()
        executeSoap(getAVTransport(device), "Pause", mapOf("InstanceID" to "0"))
        // Optimistic update
        _playbackState.update { it.copy(transportState = TransportState.PAUSED_PLAYBACK) }
    }

    override suspend fun stop() {
        if (currentDeviceId == null) return
        val device = getActiveDevice()
        executeSoap(getAVTransport(device), "Stop", mapOf("InstanceID" to "0"))
        _playbackState.update { it.copy(transportState = TransportState.STOPPED) }
    }

    override suspend fun seek(position: Duration) {
        val device = getActiveDevice()
        val target = formatDuration(position)

        executeSoap(getAVTransport(device), "Seek", mapOf(
            "InstanceID" to "0",
            "Unit" to "REL_TIME",
            "Target" to target
        ))

        _playbackState.update { it.copy(position = position) }
    }

    override suspend fun seekToByte(byteOffset: Long) {
        val device = getActiveDevice()
        executeSoap(getAVTransport(device), "Seek", mapOf(
            "InstanceID" to "0",
            "Unit" to "ABS_COUNT",
            "Target" to byteOffset.toString()
        ))
    }

    override suspend fun setVolume(volume: Int) {
        val device = getActiveDevice()
        val rc = getRenderingControl(device)
        executeSoap(rc, "SetVolume", mapOf(
            "InstanceID" to "0",
            "Channel" to "Master",
            "DesiredVolume" to volume.toString()
        ))
        _playbackState.update { it.copy(volume = volume) }
    }

    override suspend fun setMute(muted: Boolean) {
        val device = getActiveDevice()
        val rc = getRenderingControl(device)
        executeSoap(rc, "SetMute", mapOf(
            "InstanceID" to "0",
            "Channel" to "Master",
            "DesiredMute" to if (muted) "1" else "0"
        ))
        _playbackState.update { it.copy(muted = muted) }
    }

    // --- Subscription Logic ---

    private suspend fun cleanupSubscriptions() {
        subscribedServices.forEach { subscriptionManager.unsubscribe(it) }
        subscribedServices.clear()
    }

    private suspend fun subscribeToDevice(device: Device) {
        // AVTransport (TransportState, Playback Status)
        device.services.find { it.serviceType.contains("AVTransport") }?.let { service ->
            subscribedServices.add(service)
            subscriptionManager.subscribe(service) { props ->
                handleAvTransportEvent(props)
            }
        }

        // RenderingControl (Volume, Mute)
        device.services.find { it.serviceType.contains("RenderingControl") }?.let { service ->
            subscribedServices.add(service)
            subscriptionManager.subscribe(service) { props ->
                handleRenderingControlEvent(props)
            }
        }
    }

    private fun handleAvTransportEvent(properties: Map<String, String>) {
        // UPnP AVTransport events usually come in a 'LastChange' variable containing XML
        val lastChange = properties["LastChange"]
        if (!lastChange.isNullOrBlank()) {
            val changes = parseLastChange(lastChange)

            _playbackState.update { current ->
                var next = current

                changes["TransportState"]?.let { valStr ->
                    next = next.copy(transportState = mapTransportState(valStr))
                }

                // Note: CurrentTrackDuration / CurrentTrackTime might not be sent in all events
                // For a robust implementation, you might poll GetPositionInfo occasionally too.

                next
            }
        }
    }

    private fun handleRenderingControlEvent(properties: Map<String, String>) {
        val lastChange = properties["LastChange"]
        if (!lastChange.isNullOrBlank()) {
            val changes = parseLastChange(lastChange)

            _playbackState.update { current ->
                var next = current

                changes["Volume"]?.toIntOrNull()?.let {
                    next = next.copy(volume = it)
                }

                changes["Mute"]?.let {
                    next = next.copy(muted = (it == "1" || it.equals("true", true)))
                }

                next
            }
        }
    }

    /**
     * Parses the inner XML found in "LastChange".
     * Example: <Event ...><InstanceID val="0"><TransportState val="PLAYING"/></InstanceID></Event>
     */
    private fun parseLastChange(xml: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        try {
            // LastChange XML often has escaped characters (e.g. &lt;Event...) if it was embedded
            // but here it comes from the parser which already unescaped the property value.
            // However, it is an XML fragment.

            val factory = DocumentBuilderFactory.newInstance()
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(InputSource(StringReader(xml)))
            doc.documentElement.normalize()

            // We look for InstanceID val="0" children
            val instanceIds = doc.getElementsByTagName("InstanceID")
            if (instanceIds.length > 0) {
                val zeroNode = instanceIds.item(0) as Element
                val children = zeroNode.childNodes
                for (i in 0 until children.length) {
                    val node = children.item(i)
                    if (node is Element) {
                        // <TransportState val="PLAYING"/>
                        val value = node.getAttribute("val")
                        if (value.isNotBlank()) {
                            map[node.localName] = value
                        }
                    }
                }
            }
        } catch (e: Exception) {
            DlnaLogger.w(tag, "Failed to parse LastChange: ${e.message}")
        }
        return map
    }

    private fun mapTransportState(state: String): TransportState {
        return when (state.uppercase()) {
            "PLAYING" -> TransportState.PLAYING
            "PAUSED_PLAYBACK", "PAUSED" -> TransportState.PAUSED_PLAYBACK
            "STOPPED" -> TransportState.STOPPED
            "TRANSITIONING" -> TransportState.TRANSITIONING
            "NO_MEDIA_PRESENT" -> TransportState.NO_MEDIA_PRESENT
            else -> TransportState.UNKNOWN
        }
    }

    // --- Helpers ---

    private fun getActiveDevice(): Device {
        val id = currentDeviceId ?: throw DlnaError.Playback("No renderer selected")
        return deviceRepository.getDevice(id)
            ?: throw DlnaError.Discovery("Renderer disconnected")
    }

    private fun getAVTransport(device: Device): Service {
        return device.services.find { it.serviceType.contains("AVTransport") }
            ?: throw DlnaError.Playback("Device lacks AVTransport service")
    }

    private fun getRenderingControl(device: Device): Service {
        return device.services.find { it.serviceType.contains("RenderingControl") }
            ?: throw DlnaError.Playback("Device lacks RenderingControl service")
    }

    private suspend fun executeSoap(service: Service, action: String, args: Map<String, String>) {
        try {
            soapClient.sendAction(service.controlUrl, service.serviceType, action, args)
        } catch (t: Throwable) {
            throw PublicErrorMapper.mapToPlaybackError(t, action)
        }
    }

    private fun buildDidlMetadata(item: MediaItem): String {
        val title = escapeXml(item.title)
        val id = escapeXml(item.id)
        val parent = escapeXml(item.parentId)
        val upnpClass = item.upnpClass
        val protocolInfo = item.resources.firstOrNull()?.protocolInfo ?: "*:*:*:*"
        val uri = item.resources.firstOrNull()?.uri ?: ""

        return """
            <DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" xmlns:dc="http://purl.org/dc/elements/1.1/">
                <item id="$id" parentID="$parent" restricted="1">
                    <dc:title>$title</dc:title>
                    <upnp:class>$upnpClass</upnp:class>
                    <res protocolInfo="$protocolInfo">$uri</res>
                </item>
            </DIDL-Lite>
        """.trimIndent().replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
    }

    private fun escapeXml(input: String): String {
        return input.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private fun formatDuration(duration: Duration): String {
        val totalSeconds = duration.inWholeSeconds
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return String.format("%02d:%02d:%02d", h, m, s)
    }
}