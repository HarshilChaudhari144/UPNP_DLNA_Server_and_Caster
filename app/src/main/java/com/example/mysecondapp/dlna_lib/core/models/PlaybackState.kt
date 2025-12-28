package com.example.mysecondapp.dlna_lib.core.models

import kotlin.time.Duration

/**
 * The user-facing projection of the current playback status.
 * Combines wire state (TransportState) with local session data.
 * Spec Reference: 16.2
 */
data class PlaybackState(
    val transportState: TransportState,
    val position: Duration?,
    val duration: Duration?,
    val speed: String?,
    val volume: Int?,
    val muted: Boolean?,
    val mediaItem: MediaItem?
)