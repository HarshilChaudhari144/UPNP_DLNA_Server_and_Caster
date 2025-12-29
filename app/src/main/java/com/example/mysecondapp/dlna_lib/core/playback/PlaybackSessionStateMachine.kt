package com.example.mysecondapp.dlna_lib.core.playback

/**
 * Internal state of the library's playback logic, distinct from the device's transport state.
 */
internal enum class PlaybackSessionState {
    NO_RENDERER,        // No device selected
    RENDERER_SELECTED,  // Device selected, no media set
    MEDIA_SET,          // setAVTransportURI called
    PLAYING,            // Device is playing
    PAUSED,             // Device is paused
    STOPPED,            // Device is stopped
    ERROR               // Last command failed
}