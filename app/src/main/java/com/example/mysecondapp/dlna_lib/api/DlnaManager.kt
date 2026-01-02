package com.example.mysecondapp.dlna_lib.api

import com.example.mysecondapp.dlna_lib.api.browse.BrowseApi
import com.example.mysecondapp.dlna_lib.api.device.DeviceRegistry
import com.example.mysecondapp.dlna_lib.api.media.MediaServerApi
import com.example.mysecondapp.dlna_lib.api.playback.PlaybackApi
import com.example.mysecondapp.dlna_lib.core.lifecycle.DlnaEngine
import com.example.mysecondapp.dlna_lib.platform.DlnaPlatform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object DlnaManager {

    private var engine: DlnaEngine? = null

    // Public APIs backed by the Core Engine.
    // We use lateinit, but we guarantee initialization in start().

    lateinit var devices: DeviceRegistry
        private set

    lateinit var browser: BrowseApi
        private set

    lateinit var playback: PlaybackApi
        private set

    lateinit var mediaServer: MediaServerApi
        private set

    /**
     * Checks if the Manager has been started and the engine is running.
     * Useful for UI components waiting for the Service to initialize the library.
     */
    fun isInitialized(): Boolean {
        return engine != null
    }

    /**
     * Initializes the DLNA Library.
     * This MUST be called before accessing any other properties.
     */
    fun start(config: DlnaConfig, platform: DlnaPlatform) {
        // 1. Prevent double initialization
        if (engine != null) {
            stop()
        }

        // 2. Instantiate the Core Engine
        val newEngine = DlnaEngine(config, platform)

        // 3. Bind Public APIs to Core Controllers immediately
        // This prevents UninitializedPropertyAccessException
        devices = newEngine.deviceRegistry
        browser = newEngine.browseController
        playback = newEngine.playbackController

        // 4. Create the MediaServer API Wrapper
        mediaServer = object : MediaServerApi {
            // Shadow state since Controller doesn't expose Flow yet
            private val _isRunning = MutableStateFlow(false)
            override val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

            override fun start() {
                // Only start if enabled in config and controller exists
                newEngine.mediaServerController?.let { controller ->
                    controller.start()
                    _isRunning.value = true
                }
            }

            override fun stop() {
                newEngine.mediaServerController?.let { controller ->
                    controller.stop()
                    _isRunning.value = false
                }
            }

            override fun refreshContent() {
                // TODO: MediaServerController does not yet support dynamic updates (SystemUpdateID).
                // This is a placeholder for future implementation.
            }
        }

        // 5. Start the Engine Lifecycle
        newEngine.start()

        // 6. If config enabled it, mark our API wrapper state as true
        if (config.enableMediaServer && newEngine.mediaServerController != null) {
            // The engine.start() calls mediaServerController.start() internally,
            // so we should reflect that in our state flow.
            (mediaServer.isRunning as? MutableStateFlow)?.value = true
        }

        // 7. Store reference
        engine = newEngine
    }

    /**
     * Stops the library, releases network locks, and clears device lists.
     */
    fun stop() {
        engine?.stop()
        engine = null

        // Reset MediaServer state if possible
        if (::mediaServer.isInitialized) {
            (mediaServer.isRunning as? MutableStateFlow)?.value = false
        }
    }
}