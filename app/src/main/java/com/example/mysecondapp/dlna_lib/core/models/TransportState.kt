package com.example.mysecondapp.dlna_lib.core.models

/**
 * Represents the raw UPnP wire state of the AVTransport service.
 * Spec Reference: 16.1
 */
enum class TransportState {
    STOPPED,
    PLAYING,
    PAUSED_PLAYBACK,
    TRANSITIONING,
    NO_MEDIA_PRESENT,
    UNKNOWN
}