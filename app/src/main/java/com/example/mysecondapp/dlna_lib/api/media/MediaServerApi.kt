package com.example.mysecondapp.dlna_lib.api.media

import kotlinx.coroutines.flow.StateFlow

interface MediaServerApi {
    /**
     * Observable state of the local Media Server.
     */
    val isRunning: StateFlow<Boolean>

    /**
     * Manually starts the Media Server (if not already running).
     * Requires [DlnaConfig.enableMediaServer] to be true.
     */
    fun start()

    /**
     * Stops the Media Server without stopping the rest of the DLNA engine.
     */
    fun stop()

    /**
     * Notifies the core engine that the local content has changed.
     */
    fun refreshContent()
}