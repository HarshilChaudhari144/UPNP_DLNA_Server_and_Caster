package com.example.mysecondapp.dlna_lib.core.lifecycle

import com.example.mysecondapp.dlna_lib.api.DlnaConfig
import com.example.mysecondapp.dlna_lib.api.DlnaManager
import com.example.mysecondapp.dlna_lib.core.browse.BrowseController
import com.example.mysecondapp.dlna_lib.core.device.DeviceDescriptorParser
import com.example.mysecondapp.dlna_lib.core.device.DeviceRepository
import com.example.mysecondapp.dlna_lib.core.device.DeviceStateMachine
import com.example.mysecondapp.dlna_lib.core.didl.DidlLiteParser
import com.example.mysecondapp.dlna_lib.core.eventing.SubscriptionManager
import com.example.mysecondapp.dlna_lib.core.playback.PlaybackController
import com.example.mysecondapp.dlna_lib.core.ssdp.SsdpController
import com.example.mysecondapp.dlna_lib.platform.DlnaPlatform

internal class DlnaEngine(
    private val config: DlnaConfig,
    private val platform: DlnaPlatform
) {
    // 1. Create the repository (The storage)
    val deviceRepository = DeviceRepository()

    // 2. Create the parser (The XML reader)
    private val deviceParser = DeviceDescriptorParser()

    // 3. Create the state machine (The logic controller)
    private val deviceStateMachine = DeviceStateMachine(deviceRepository, deviceParser)

    // 4. Create the SSDP controller (The network listener) and pass the state machine
    private val ssdpController = SsdpController(platform.ssdp, deviceStateMachine)

    // 5. Browsing logic
    private val didlParser = DidlLiteParser()
    private val browseController = BrowseController(deviceRepository, didlParser)

    // 6. Playback & Eventing Logic
    private val subscriptionManager = SubscriptionManager(platform.eventServer)
    private val playbackController = PlaybackController(deviceRepository, subscriptionManager)

    fun start() {
        // Wire the internal repository to the public DlnaManager
        DlnaManager.devices = deviceRepository

        // Wire the browser to the public API
        DlnaManager.browser = browseController

        DlnaManager.playback = playbackController

        // Start the Platform's Event Server (Phone becomes a listener)
        // We pass the playbackController because it implements HttpHandler
        platform.eventServer.start(playbackController)

        // Start discovery
        ssdpController.start()

        // Note: Browsing/Playback controllers will be added here later
        // Note: Media Server initialization will happen here next
    }

    fun stop() {
        ssdpController.stop()

        // Clean up GENA subscriptions and stop the callback server
        subscriptionManager.unsubscribeAll()
        platform.eventServer.stop()

        // Note: Media Server stop logic will be added here
    }
}