package com.example.mysecondapp.dlna_lib.api

import com.example.mysecondapp.dlna_lib.core.models.MediaItem
import com.example.mysecondapp.dlna_lib.core.models.PlaybackState
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration

/**
 * API for controlling playback on remote Renderers (TVs, Speakers).
 * Spec Reference: 5.5
 */
interface PlaybackApi {
    suspend fun setRenderer(deviceId: String)

    /**
     * @param speed "1" is normal. Other values are best-effort.
     */
    suspend fun play(mediaItem: MediaItem, speed: String = "1")
    suspend fun pause()
    suspend fun stop()

    /**
     * Throws only on lifecycle/connection errors.
     * Silent no-op if renderer lacks capability.
     */
    suspend fun seek(position: Duration)
    suspend fun seekToByte(byteOffset: Long)

    suspend fun setVolume(volume: Int)
    suspend fun setMute(muted: Boolean)

    /**
     * Public projection of internal session state + AVTransport state.
     */
    val playbackState: StateFlow<PlaybackState>
}