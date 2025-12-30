package com.example.mysecondapp.dlna_lib.core.lifecycle

import com.example.mysecondapp.dlna_lib.api.DlnaConfig
import com.example.mysecondapp.dlna_lib.api.device.DeviceRegistry
import com.example.mysecondapp.dlna_lib.core.browse.BrowseController
import com.example.mysecondapp.dlna_lib.core.device.DeviceDescriptorParser
import com.example.mysecondapp.dlna_lib.core.device.DeviceRepository
import com.example.mysecondapp.dlna_lib.core.device.DeviceStateMachine
import com.example.mysecondapp.dlna_lib.core.eventing.SubscriptionManager
import com.example.mysecondapp.dlna_lib.core.logging.DlnaLogger
import com.example.mysecondapp.dlna_lib.core.mediaserver.MediaServerController
import com.example.mysecondapp.dlna_lib.core.playback.PlaybackController
import com.example.mysecondapp.dlna_lib.core.soap.SoapClient
import com.example.mysecondapp.dlna_lib.core.ssdp.SsdpCache
import com.example.mysecondapp.dlna_lib.core.ssdp.SsdpController
import com.example.mysecondapp.dlna_lib.platform.DlnaPlatform

internal class DlnaEngine(
    private val config: DlnaConfig,
    private val platform: DlnaPlatform
) {

    private val tag = "DlnaEngine"

    // --- 1. Infrastructure ---
    private val soapClient = SoapClient()

    // Handles GENA (Eventing) - Wrapper around platform.eventServer
    private val subscriptionManager = SubscriptionManager(platform.eventServer)

    // --- 2. Discovery Components ---
    val deviceRepository = DeviceRepository()

    val deviceRegistry: DeviceRegistry = object : DeviceRegistry {
        override val devices = deviceRepository.devices
        override fun getDevice(deviceId: String) = deviceRepository.getDevice(deviceId)
        override fun getMediaServers() = deviceRepository.getServers()
        override fun getMediaRenderers() = deviceRepository.getRenderers()
    }

    private val descriptorParser = DeviceDescriptorParser()
    private val deviceStateMachine = DeviceStateMachine(deviceRepository, descriptorParser)
    private val ssdpCache = SsdpCache(deviceStateMachine)

    private val ssdpController = SsdpController(platform.ssdp, deviceStateMachine, ssdpCache)

    // --- 3. Functional Controllers ---

    val browseController = BrowseController(deviceRepository, soapClient)

    // Playback Logic
    val playbackController = PlaybackController(
        deviceRepository,
        soapClient,
        subscriptionManager
    )

    // Local Media Server (Now Enabled)
    // We pass 'platform.ssdp' so the server can send its own "Alive" notifications
    val mediaServerController = if (config.enableMediaServer && config.contentProvider != null) {
        MediaServerController(
            config = config,
            httpServer = platform.httpServer,
            mimeResolver = platform.mimeResolver,
            networkInfo = platform.networkInfo,
            ssdpTransport = platform.ssdp
        )
    } else null

    // --- 4. Initialization Wiring ---
    init {
        // Wire up Search Responses:
        // When SsdpController hears "M-SEARCH" from a TV, tell the Media Server to reply "I am here!"
        ssdpController.onSearchReceived = { packet, address, port ->
            mediaServerController?.respondToSearch(packet, address, port)
        }
    }

    fun start() {
        DlnaLogger.d(tag, "Engine Starting...")

        // 1. Start Network stuff
        ssdpController.start()

        // 2. Start Event Listening
        subscriptionManager.start()

        // 3. Start Media Server (if enabled)
        mediaServerController?.start()

        DlnaLogger.d(tag, "Engine Started.")
    }

    fun stop() {
        DlnaLogger.d(tag, "Engine Stopping...")

        ssdpController.stop()
        subscriptionManager.stop()
        mediaServerController?.stop()

        // Clear devices on stop so we don't show stale ones next time
        deviceRepository.clear()

        DlnaLogger.d(tag, "Engine Stopped.")
    }
}