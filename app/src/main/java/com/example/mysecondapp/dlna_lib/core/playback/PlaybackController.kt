package com.example.mysecondapp.dlna_lib.core.playback

import com.example.mysecondapp.dlna_lib.api.device.Device
import com.example.mysecondapp.dlna_lib.api.device.Service
import com.example.mysecondapp.dlna_lib.api.errors.DlnaError
import com.example.mysecondapp.dlna_lib.api.media.MediaItem
import com.example.mysecondapp.dlna_lib.api.media.MediaType
import com.example.mysecondapp.dlna_lib.api.playback.PlaybackApi
import com.example.mysecondapp.dlna_lib.api.playback.PlaybackState
import com.example.mysecondapp.dlna_lib.api.playback.TransportState
import com.example.mysecondapp.dlna_lib.core.capability.RendererCapabilities
import com.example.mysecondapp.dlna_lib.core.device.DeviceRepository
import com.example.mysecondapp.dlna_lib.core.didl.DidlLiteParser
import com.example.mysecondapp.dlna_lib.core.error.PublicErrorMapper
import com.example.mysecondapp.dlna_lib.core.eventing.SubscriptionManager
import com.example.mysecondapp.dlna_lib.core.lifecycle.dlnaScope
import com.example.mysecondapp.dlna_lib.core.logging.DlnaLogger
import com.example.mysecondapp.dlna_lib.core.soap.SoapClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    private var currentDeviceId: String? = null
    private var subscribedServices = mutableListOf<Service>()
    private var pollingJob: Job? = null
    private val didlParser = DidlLiteParser()

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

        cleanupSubscriptions()
        stopPolling()

        if (!RendererCapabilities.canSeek(device)) {
            DlnaLogger.w(tag, "Selected renderer might not support playback (Missing AVTransport)")
        }

        currentDeviceId = deviceId
        DlnaLogger.d(tag, "Renderer selected: ${device.friendlyName}")

        subscribeToDevice(device)
        syncDeviceState(device)
    }

    override suspend fun play(mediaItem: MediaItem, speed: String) {
        val device = getActiveDevice()
        val avTransport = getAVTransport(device)

        try { stop() } catch (e: Exception) { /* Ignore */ }

        val resource = mediaItem.resources.firstOrNull()
            ?: throw DlnaError.Playback("MediaItem has no playable resources")

        val metadata = buildDidlMetadata(mediaItem)

        executeSoap(avTransport, "SetAVTransportURI", mapOf(
            "InstanceID" to "0",
            "CurrentURI" to resource.uri,
            "CurrentURIMetaData" to metadata
        ))

        executeSoap(avTransport, "Play", mapOf(
            "InstanceID" to "0",
            "Speed" to speed
        ))

        _playbackState.update {
            it.copy(
                transportState = TransportState.TRANSITIONING,
                mediaItem = mediaItem
            )
        }

        startPolling()
    }

    override suspend fun resume() {
        val device = getActiveDevice()
        executeSoap(getAVTransport(device), "Play", mapOf("InstanceID" to "0", "Speed" to "1"))
        _playbackState.update { it.copy(transportState = TransportState.PLAYING) }
        startPolling()
    }

    override suspend fun pause() {
        val device = getActiveDevice()
        executeSoap(getAVTransport(device), "Pause", mapOf("InstanceID" to "0"))
        _playbackState.update { it.copy(transportState = TransportState.PAUSED_PLAYBACK) }
        stopPolling()
    }

    override suspend fun stop() {
        if (currentDeviceId == null) return
        val device = getActiveDevice()
        executeSoap(getAVTransport(device), "Stop", mapOf("InstanceID" to "0"))
        _playbackState.update { it.copy(transportState = TransportState.STOPPED) }
        stopPolling()
    }

    override suspend fun seek(position: Duration) {
        val device = getActiveDevice()

        val currentState = _playbackState.value.transportState
        if (currentState == TransportState.TRANSITIONING || currentState == TransportState.STOPPED) {
            DlnaLogger.w(tag, "Cannot seek while state is $currentState")
            return
        }

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

    // --- State Sync ---

    private suspend fun syncDeviceState(device: Device) {
        val avTransport = device.services.find { it.serviceType.contains("AVTransport") } ?: return
        val renderingControl = device.services.find { it.serviceType.contains("RenderingControl") }

        try {
            val transportXml = soapClient.sendAction(avTransport.controlUrl, avTransport.serviceType, "GetTransportInfo", mapOf("InstanceID" to "0"))
            val stateStr = extractValueRegex(transportXml, "CurrentTransportState")
            val transportState = mapTransportState(stateStr ?: "STOPPED")

            var volume = 0
            var muted = false
            if (renderingControl != null) {
                try {
                    val volXml = soapClient.sendAction(renderingControl.controlUrl, renderingControl.serviceType, "GetVolume", mapOf("InstanceID" to "0", "Channel" to "Master"))
                    volume = extractValueRegex(volXml, "CurrentVolume")?.toIntOrNull() ?: 0

                    val muteXml = soapClient.sendAction(renderingControl.controlUrl, renderingControl.serviceType, "GetMute", mapOf("InstanceID" to "0", "Channel" to "Master"))
                    val muteStr = extractValueRegex(muteXml, "CurrentMute")
                    muted = (muteStr == "1" || muteStr.equals("true", true))
                } catch (e: Exception) { /* Ignore */ }
            }

            var mediaItem: MediaItem? = null
            var duration: Duration? = null

            try {
                val mediaXml = soapClient.sendAction(avTransport.controlUrl, avTransport.serviceType, "GetMediaInfo", mapOf("InstanceID" to "0"))
                val metaData = extractValueRegex(mediaXml, "CurrentURIMetaData")
                val durationStr = extractValueRegex(mediaXml, "MediaDuration")

                if (!metaData.isNullOrBlank()) {
                    val unescaped = unescapeXml(metaData)

                    // Attempt strict parsing first
                    val result = didlParser.parse(unescaped)
                    mediaItem = result.items.firstOrNull()

                    // Fallback: If XML parser failed to find title (due to namespaces/entities), use Dirty Parse
                    if (mediaItem == null || mediaItem.title == "Unknown Item") {
                        val fallbackTitle = dirtyExtractTitle(unescaped)
                        if (fallbackTitle != null) {
                            mediaItem = MediaItem(
                                id = "0", parentId = "0",
                                title = fallbackTitle,
                                upnpClass = "object.item.videoItem",
                                mediaType = MediaType.VIDEO,
                                resources = emptyList()
                            )
                        }
                    }
                }
                duration = parseDuration(durationStr)
            } catch (e: Exception) {
                DlnaLogger.w(tag, "Failed to sync MediaInfo: ${e.message}")
            }

            var position: Duration? = null
            try {
                val posXml = soapClient.sendAction(avTransport.controlUrl, avTransport.serviceType, "GetPositionInfo", mapOf("InstanceID" to "0"))
                val timeStr = extractValueRegex(posXml, "RelTime")
                position = parseDuration(timeStr)
            } catch (e: Exception) { /* Ignore */ }

            _playbackState.update {
                it.copy(
                    transportState = transportState,
                    volume = volume,
                    muted = muted,
                    mediaItem = mediaItem,
                    duration = duration,
                    position = position
                )
            }

            if (transportState == TransportState.PLAYING || transportState == TransportState.TRANSITIONING) {
                startPolling()
            }

        } catch (e: Exception) {
            DlnaLogger.w(tag, "Initial Sync failed: ${e.message}")
        }
    }

    private fun startPolling() {
        stopPolling()
        pollingJob = dlnaScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val device = getActiveDevice()
                    val av = getAVTransport(device)

                    // 1. Poll Transport Info (To update state from TRANSITIONING -> PLAYING)
                    val statusXml = soapClient.sendAction(av.controlUrl, av.serviceType, "GetTransportInfo", mapOf("InstanceID" to "0"))
                    val stateStr = extractValueRegex(statusXml, "CurrentTransportState")
                    val transportState = mapTransportState(stateStr ?: "")

                    // 2. Poll Position Info (Time)
                    val posXml = soapClient.sendAction(av.controlUrl, av.serviceType, "GetPositionInfo", mapOf("InstanceID" to "0"))
                    val timeStr = extractValueRegex(posXml, "RelTime")
                    val position = parseDuration(timeStr)

                    // 3. Update State
                    _playbackState.update {
                        it.copy(
                            position = position,
                            transportState = transportState
                        )
                    }

                } catch (e: Exception) { /* Squelch */ }
                delay(1000)
            }
        }
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    // --- Subscription & Eventing ---

    private suspend fun cleanupSubscriptions() {
        subscribedServices.forEach { subscriptionManager.unsubscribe(it) }
        subscribedServices.clear()
    }

    private suspend fun subscribeToDevice(device: Device) {
        device.services.find { it.serviceType.contains("AVTransport") }?.let { service ->
            subscribedServices.add(service)
            subscriptionManager.subscribe(service) { props -> handleAvTransportEvent(props) }
        }
        device.services.find { it.serviceType.contains("RenderingControl") }?.let { service ->
            subscribedServices.add(service)
            subscriptionManager.subscribe(service) { props -> handleRenderingControlEvent(props) }
        }
    }

    private fun handleAvTransportEvent(properties: Map<String, String>) {
        val lastChange = properties["LastChange"] ?: return
        val changes = parseLastChange(lastChange)

        _playbackState.update { current ->
            var next = current
            changes["TransportState"]?.let { valStr ->
                val newState = mapTransportState(valStr)
                next = next.copy(transportState = newState)
                if (newState == TransportState.PLAYING) startPolling()
                else if (newState == TransportState.STOPPED || newState == TransportState.PAUSED_PLAYBACK) stopPolling()
            }
            next
        }
    }

    private fun handleRenderingControlEvent(properties: Map<String, String>) {
        val lastChange = properties["LastChange"] ?: return
        val changes = parseLastChange(lastChange)

        _playbackState.update { current ->
            var next = current
            changes["Volume"]?.toIntOrNull()?.let { next = next.copy(volume = it) }
            changes["Mute"]?.let { next = next.copy(muted = (it == "1" || it.equals("true", true))) }
            next
        }
    }

    // --- Helpers ---

    private fun getActiveDevice(): Device {
        val id = currentDeviceId ?: throw DlnaError.Playback("No renderer selected")
        return deviceRepository.getDevice(id) ?: throw DlnaError.Discovery("Renderer disconnected")
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
        val rawProtocol = item.resources.firstOrNull()?.protocolInfo ?: "*:*:*:*"
        val protocolInfo = patchProtocolInfo(rawProtocol)
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

    private fun patchProtocolInfo(info: String): String {
        if (info.contains("DLNA.ORG_OP")) return info
        val parts = info.split(":")
        if (parts.size < 3) return info
        val flags = "DLNA.ORG_PN=AVC_TS_MP_HD_AAC_ISO;DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
        return "${parts[0]}:${parts[1]}:${parts[2]}:$flags"
    }

    private fun parseLastChange(xml: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        try {
            val cleanXml = unescapeXml(xml)
            val factory = DocumentBuilderFactory.newInstance()
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(InputSource(StringReader(cleanXml)))
            doc.documentElement.normalize()
            val instanceIds = doc.getElementsByTagName("InstanceID")
            if (instanceIds.length > 0) {
                val children = instanceIds.item(0).childNodes
                for (i in 0 until children.length) {
                    val node = children.item(i)
                    if (node is Element) {
                        val value = node.getAttribute("val")
                        if (value.isNotBlank()) map[node.localName] = value
                    }
                }
            }
        } catch (e: Exception) { /* ignore */ }
        return map
    }

    // FIX: Updated regex to be more permissive with spacing/attributes
    private fun extractValueRegex(xml: String, tagName: String): String? {
        val regex = Regex("<([a-zA-Z0-9]+:)?$tagName(?:\\s[^>]*)?>(.*?)</([a-zA-Z0-9]+:)?$tagName>", RegexOption.DOT_MATCHES_ALL)
        val match = regex.find(xml)
        return match?.groupValues?.get(2)?.trim()
    }

    // Fallback parser using Regex to find title if XML parsing fails
    private fun dirtyExtractTitle(xml: String): String? {
        val regex = Regex("<(dc:)?title>(.*?)</(dc:)?title>", RegexOption.IGNORE_CASE)
        return regex.find(xml)?.groupValues?.get(2)?.trim()
    }

    private fun parseDuration(timestamp: String?): Duration? {
        if (timestamp.isNullOrBlank()) return null
        return try {
            val parts = timestamp.split(":")
            if (parts.size != 3) return null
            val h = parts[0].toLong()
            val m = parts[1].toLong()
            val s = parts[2].split(".")[0].toLong()
            h.seconds * 3600 + m.seconds * 60 + s.seconds
        } catch (e: Exception) { null }
    }

    private fun formatDuration(duration: Duration): String {
        val totalSeconds = duration.inWholeSeconds
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return String.format("%02d:%02d:%02d", h, m, s)
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

    private fun escapeXml(input: String): String {
        return input.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private fun unescapeXml(input: String): String {
        return input.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#10;", " ")
            .replace("&#x0A;", " ")
    }
}