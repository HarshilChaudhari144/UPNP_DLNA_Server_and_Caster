package com.example.mysecondapp.dlna_lib.core.playback

import com.example.mysecondapp.dlna_lib.api.device.DeviceId
import com.example.mysecondapp.dlna_lib.api.media.MediaItem
import com.example.mysecondapp.dlna_lib.api.playback.PlaybackApi
import com.example.mysecondapp.dlna_lib.api.playback.PlaybackState
import com.example.mysecondapp.dlna_lib.api.playback.TransportState
import com.example.mysecondapp.dlna_lib.core.device.DeviceRepository
import com.example.mysecondapp.dlna_lib.core.eventing.SubscriptionManager
import com.example.mysecondapp.dlna_lib.platform.HttpHandler
import com.example.mysecondapp.dlna_lib.platform.HttpRequest
import com.example.mysecondapp.dlna_lib.platform.HttpResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.time.Duration

internal class PlaybackController(
    private val deviceRepository: DeviceRepository,
    private val subscriptionManager: SubscriptionManager
) : PlaybackApi, HttpHandler {

    private var currentTargetDeviceId: DeviceId? = null

    private val _playbackState = MutableStateFlow(PlaybackState(TransportState.STOPPED))
    override val playbackState: StateFlow<PlaybackState> = _playbackState

    override suspend fun setRenderer(deviceId: DeviceId) {
        this.currentTargetDeviceId = deviceId
        val device = deviceRepository.getDevice(deviceId) ?: return

        // --- NEW: GENA Event Subscription with Null Safety ---
        // Subscribe to AVTransport events to get playback status (Playing, Stopped, etc.)
        device.services.find { it.serviceType.contains("AVTransport") }?.let { service ->
            service.eventSubUrl?.let { url -> subscriptionManager.subscribe(url) }
        }
        // Subscribe to RenderingControl for Volume and Mute updates
        device.services.find { it.serviceType.contains("RenderingControl") }?.let { service ->
            service.eventSubUrl?.let { url -> subscriptionManager.subscribe(url) }
        }
    }

    /**
     * This is called by the Platform's EventCallbackServer whenever the TV sends an HTTP NOTIFY.
     */
    override suspend fun handle(request: HttpRequest): HttpResponse {
        val xmlBody = request.body ?: return HttpResponse(200)

        // DLNA events are often "Double Encoded" XML inside a <LastChange> tag.
        // We look for the LastChange tag and extract its escaped content.
        if (xmlBody.contains("LastChange")) {
            val escapedXml = xmlBody.substringAfter("&lt;").substringBeforeLast("&gt;")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&amp;", "&")

            updateStateFromEvent(escapedXml)
        }

        return HttpResponse(200) // Always acknowledge GENA notifications with 200 OK
    }

    private fun updateStateFromEvent(xml: String) {
        // Simple string parsing for efficiency.
        // In a complex app, you might use a proper XML parser here.
        val newState = _playbackState.value.copy(
            transportState = when {
                xml.contains("PLAYING") -> TransportState.PLAYING
                xml.contains("PAUSED_PLAYBACK") -> TransportState.PAUSED_PLAYBACK
                xml.contains("STOPPED") -> TransportState.STOPPED
                xml.contains("TRANSITIONING") -> TransportState.TRANSITIONING
                xml.contains("NO_MEDIA_PRESENT") -> TransportState.NO_MEDIA_PRESENT
                else -> _playbackState.value.transportState
            },
            volume = if (xml.contains("Volume")) {
                // Extracts the value from: <Volume val="25"/>
                xml.substringAfter("Volume val=\"").substringBefore("\"").toIntOrNull()
                    ?: _playbackState.value.volume
            } else _playbackState.value.volume
        )
        _playbackState.value = newState
    }

    override suspend fun play(mediaItem: MediaItem, speed: String) = withContext(Dispatchers.IO) {
        val deviceId = currentTargetDeviceId ?: throw Exception("No renderer selected")
        val device = deviceRepository.getDevice(deviceId) ?: throw Exception("Device not found")
        val service = device.services.find { it.serviceType.contains("AVTransport") }
            ?: throw Exception("Device does not support playback")

        val streamUrl = mediaItem.resources.firstOrNull()?.uri
            ?: throw Exception("Media item has no playable URI")

        // Step 1: Tell the TV what to play
        val setUriBody = """
            <u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                <InstanceID>0</InstanceID>
                <CurrentURI>$streamUrl</CurrentURI>
                <CurrentURIMetaData></CurrentURIMetaData>
            </u:SetAVTransportURI>
        """.trimIndent()

        executeSoapAction(service.controlUrl, "AVTransport", "SetAVTransportURI", setUriBody)

        // Step 2: Tell the TV to start playing
        val playBody = """
            <u:Play xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                <InstanceID>0</InstanceID>
                <Speed>$speed</Speed>
            </u:Play>
        """.trimIndent()

        executeSoapAction(service.controlUrl, "AVTransport", "Play", playBody)

        // We update locally immediately for UI responsiveness,
        // but GENA events will eventually confirm the state from the TV.
        _playbackState.value = _playbackState.value.copy(
            transportState = TransportState.PLAYING,
            mediaItem = mediaItem
        )
    }

    override suspend fun pause() = sendSimpleCommand("Pause")
    override suspend fun stop() = sendSimpleCommand("Stop")

    override suspend fun seek(position: Duration) {
        val timeStr = formatDuration(position)
        sendSeekCommand("REL_TIME", timeStr)
    }

    override suspend fun seekToByte(byteOffset: Long) {
        sendSeekCommand("BYTE_UNITS", byteOffset.toString())
    }

    override suspend fun setVolume(volume: Int) {
        // Volume is usually in RenderingControl service, not AVTransport
        sendSimpleCommand("SetVolume", "RenderingControl", """
            <InstanceID>0</InstanceID>
            <Channel>Master</Channel>
            <DesiredVolume>$volume</DesiredVolume>
        """.trimIndent())
    }

    override suspend fun setMute(muted: Boolean) {
        val muteVal = if (muted) "1" else "0"
        sendSimpleCommand("SetMute", "RenderingControl", """
            <InstanceID>0</InstanceID>
            <Channel>Master</Channel>
            <DesiredMute>$muteVal</DesiredMute>
        """.trimIndent())
    }

    // --- Private Helpers ---

    private suspend fun sendSimpleCommand(action: String, serviceName: String = "AVTransport", customBody: String? = null) = withContext(Dispatchers.IO) {
        val deviceId = currentTargetDeviceId ?: return@withContext
        val device = deviceRepository.getDevice(deviceId) ?: return@withContext
        val service = device.services.find { it.serviceType.contains(serviceName) } ?: return@withContext

        val body = customBody ?: "<InstanceID>0</InstanceID>"
        val wrappedBody = "<u:$action xmlns:u=\"urn:schemas-upnp-org:service:$serviceName:1\">$body</u:$action>"

        executeSoapAction(service.controlUrl, serviceName, action, wrappedBody)
    }

    private suspend fun sendSeekCommand(unit: String, target: String) {
        val body = """
            <InstanceID>0</InstanceID>
            <Unit>$unit</Unit>
            <Target>$target</Target>
        """.trimIndent()
        sendSimpleCommand("Seek", "AVTransport", body)
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

    private fun formatDuration(duration: Duration): String {
        val totalSeconds = duration.inWholeSeconds
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return "%02d:%02d:%02d".format(hours, minutes, seconds)
    }
}