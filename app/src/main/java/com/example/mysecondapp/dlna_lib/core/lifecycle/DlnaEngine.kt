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
    config: DlnaConfig,
    platform: DlnaPlatform
) {

    private val tag = "DlnaEngine"

    // --- 1. Infrastructure (Client & Server) ---
    private val soapClient = SoapClient()
    private val subscriptionManager = SubscriptionManager(platform.eventServer)

    // --- 2. Discovery Components (Client) ---
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

    // --- 3. Functional Controllers (Client) ---
    val browseController = BrowseController(deviceRepository, soapClient)
    val playbackController = PlaybackController(deviceRepository, soapClient, subscriptionManager)

    // --- 4. Media Server Controller (Server) ---
    // Instantiated but not started until requested.
    val mediaServerController = if (config.contentProvider != null) {
        MediaServerController(
            config = config,
            httpServer = platform.httpServer,
            mimeResolver = platform.mimeResolver,
            networkInfo = platform.networkInfo,
            ssdpTransport = platform.ssdp,
            ssdpController = ssdpController
        )
    } else null

    fun startClient() {
        DlnaLogger.d(tag, "Starting Client Engine components...")
        // Start network discovery and event listening
        ssdpController.start()
        subscriptionManager.start()
        DlnaLogger.d(tag, "Client Engine components started.")
    }

    fun stopClient() {
        DlnaLogger.d(tag, "Stopping Client Engine components...")
        ssdpController.stop()
        subscriptionManager.stop()
        // Clear discovered devices when the client stops
        deviceRepository.clear()
        DlnaLogger.d(tag, "Client Engine components stopped.")
    }

    fun startServer() {
        DlnaLogger.d(tag, "Starting Server Engine component...")
        // Only start the media server part
        mediaServerController?.start()
        DlnaLogger.d(tag, "Server Engine component started.")
    }

    fun stopServer() {
        DlnaLogger.d(tag, "Stopping Server Engine component...")
        // Only stop the media server part
        mediaServerController?.stop()
        DlnaLogger.d(tag, "Server Engine component stopped.")
    }
}