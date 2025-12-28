package com.example.mysecondapp.dlna_lib.api.playback

import com.example.mysecondapp.dlna_lib.api.media.MediaItem
import kotlin.time.Duration

/**
 * Standard UPnP/DLNA transport states.
 */
enum class TransportState {
    STOPPED,
    PLAYING,
    PAUSED_PLAYBACK,
    TRANSITIONING, // Buffering or loading
    NO_MEDIA_PRESENT,
    UNKNOWN
}

/**
 * The current state of a remote playback session.
 */
data class PlaybackState(
    val transportState: TransportState,
    val position: Duration? = null,
    val duration: Duration? = null,
    val speed: String? = "1",
    val volume: Int? = null,
    val muted: Boolean? = null,
    val mediaItem: MediaItem? = null
)