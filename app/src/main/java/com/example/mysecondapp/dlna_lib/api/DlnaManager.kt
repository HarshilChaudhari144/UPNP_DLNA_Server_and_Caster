package com.example.mysecondapp.dlna_lib.api

import com.example.mysecondapp.dlna_lib.api.browse.BrowseApi
import com.example.mysecondapp.dlna_lib.api.device.DeviceRegistry
import com.example.mysecondapp.dlna_lib.api.media.MediaServerApi
import com.example.mysecondapp.dlna_lib.api.playback.PlaybackApi
import com.example.mysecondapp.dlna_lib.platform.DlnaPlatform
import com.example.mysecondapp.dlna_lib.core.lifecycle.DlnaEngine

/**
 * The central entry point for the DLNA library.
 */
object DlnaManager {
    private var engine: DlnaEngine? = null
    lateinit var devices: DeviceRegistry
        internal set

    lateinit var browser: BrowseApi
        internal set

    lateinit var playback: PlaybackApi
        internal set

    lateinit var mediaServer: MediaServerApi
        internal set

    /**
     * Initializes the entire DLNA engine (SSDP discovery, etc.).
     * This will also start the Media Server if [DlnaConfig.enableMediaServer] is true.
     */
    fun start(
        config: DlnaConfig,
        platform: DlnaPlatform
    ) {
        // Implementation will delegate to DlnaEngine in the Core layer
        engine = DlnaEngine(config, platform).apply { start() }
    }

    /**
     * Shuts down all services, including discovery and the Media Server.
     */
    fun stop() {
        // Implementation will stop the internal engine
        engine?.stop()
        engine = null
    }
}