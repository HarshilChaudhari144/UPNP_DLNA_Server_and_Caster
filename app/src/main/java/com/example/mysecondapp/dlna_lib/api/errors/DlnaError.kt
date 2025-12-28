package com.example.mysecondapp.dlna_lib.api.errors

/**
 * Unified exception hierarchy for the DLNA library.
 */
sealed class DlnaError(message: String, cause: Throwable? = null) : Throwable(message, cause) {

    /** Occurs when discovery fails or SSDP transport has issues. */
    class Discovery(msg: String) : DlnaError(msg)

    /** Occurs when a ContentDirectory browse action fails. */
    class Browse(msg: String) : DlnaError(msg)

    /** Occurs when a transport action (Play, Pause, Seek) fails. */
    class Playback(msg: String) : DlnaError(msg)

    /** Occurs when the GENA event subscription fails. */
    class Subscription(msg: String) : DlnaError(msg)

    /** Occurs when the local media server fails to start or serve content. */
    class MediaServer(msg: String) : DlnaError(msg)

    /** Occurs when there are general network connectivity issues. */
    class Network(msg: String, cause: Throwable? = null) : DlnaError(msg, cause)
}