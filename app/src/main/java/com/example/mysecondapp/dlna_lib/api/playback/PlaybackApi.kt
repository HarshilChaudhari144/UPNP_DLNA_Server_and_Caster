package com.example.mysecondapp.dlna_lib.api.playback

import com.example.mysecondapp.dlna_lib.api.device.DeviceId
import com.example.mysecondapp.dlna_lib.api.media.MediaItem
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration

interface PlaybackApi {

    /**
     * Tells the library which TV/Renderer we want to control.
     */
    suspend fun setRenderer(deviceId: DeviceId)

    /**
     * Starts playback of a specific item.
     */
    suspend fun play(mediaItem: MediaItem, speed: String = "1")

    /**
     * Resumes playback of the *current* media from a Paused or Stopped state.
     * Unlike [play], this does NOT re-send the URI or metadata.
     */
    suspend fun resume()

    suspend fun pause()
    suspend fun stop()

    /**
     * Jump to a specific time in the media.
     */
    suspend fun seek(position: Duration)

    /**
     * Jump to a specific byte (used for some older devices).
     */
    suspend fun seekToByte(byteOffset: Long)

    suspend fun setVolume(volume: Int)
    suspend fun setMute(muted: Boolean)

    /**
     * A StateFlow that the UI can observe to see the current
     * playback progress and status.
     */
    val playbackState: StateFlow<PlaybackState>
}