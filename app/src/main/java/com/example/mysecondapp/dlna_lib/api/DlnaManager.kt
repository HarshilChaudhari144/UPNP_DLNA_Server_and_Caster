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
    // We use lateinit, but we guarantee initialization in startClientEngine().
    lateinit var devices: DeviceRegistry
        private set
    lateinit var browser: BrowseApi
        private set
    lateinit var playback: PlaybackApi
        private set
    lateinit var mediaServer: MediaServerApi
        private set

    /**
     * Checks if the Manager's client engine has been started.
     */
    fun isInitialized(): Boolean {
        return engine != null
    }

    /**
     * Initializes the client-side components of the DLNA Library (Discovery, Control).
     * This MUST be called before accessing any other properties.
     */
    fun startClientEngine(config: DlnaConfig, platform: DlnaPlatform) {
        if (engine != null) {
            // If already running, just return. Or consider a soft restart.
            return
        }

        // 1. Instantiate the Core Engine
        val newEngine = DlnaEngine(config, platform)

        // 2. Bind Public APIs to Core Controllers
        devices = newEngine.deviceRegistry
        browser = newEngine.browseController
        playback = newEngine.playbackController

        // 3. Create the MediaServer API Wrapper
        // This wrapper now delegates to the new granular engine controls.
        mediaServer = object : MediaServerApi {
            private val _isRunning = MutableStateFlow(false)
            override val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

            override fun start() {
                // This will be called by the service, which calls the public startMediaServer()
                newEngine.startServer()
                _isRunning.value = true
            }

            override fun stop() {
                // This will be called by the service, which calls the public stopMediaServer()
                newEngine.stopServer()
                _isRunning.value = false
            }

            override fun refreshContent() {
                // Future implementation
            }
        }

        // 4. Start only the Client-side components of the Engine
        newEngine.startClient()

        // 5. Store engine reference
        engine = newEngine
    }

    /**
     * Stops the client-side components of the library, releases network locks, and clears device lists.
     * Does NOT stop the media server if it's running in its service.
     */
    fun stopClientEngine() {
        engine?.stopClient()
        engine = null

        // Reset the isInitialized state for mediaServer as well, but don't stop it
        if (::mediaServer.isInitialized) {
            (mediaServer.isRunning as? MutableStateFlow)?.value = false
        }
    }

    /**
     * Starts the local media server component of an already initialized engine.
     * Intended to be called from the DlnaService.
     */
    fun startMediaServer() {
        // The engine must be initialized first (by the UI)
        if (!isInitialized()) return
        mediaServer.start()
    }

    /**
     * Stops the local media server component.
     * Intended to be called from the DlnaService.
     */
    fun stopMediaServer() {
        if (!isInitialized()) return
        mediaServer.stop()
    }
}