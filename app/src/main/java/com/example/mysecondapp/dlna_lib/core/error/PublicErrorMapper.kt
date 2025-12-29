package com.example.mysecondapp.dlna_lib.core.error

import com.example.mysecondapp.dlna_lib.api.errors.DlnaError
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

internal object PublicErrorMapper {

    fun mapToDiscoveryError(t: Throwable, context: String): DlnaError.Discovery {
        // Discovery constructor only takes a String
        val msg = t.message ?: t.javaClass.simpleName
        return DlnaError.Discovery("Discovery failed [$context]: $msg")
    }

    fun mapToBrowseError(t: Throwable, containerId: String): DlnaError.Browse {
        // Browse constructor only takes a String
        val msg = t.message ?: t.javaClass.simpleName
        return DlnaError.Browse("Browse failed for container '$containerId': $msg")
    }

    fun mapToPlaybackError(t: Throwable, action: String): DlnaError.Playback {
        // Playback constructor only takes a String
        val msg = t.message ?: t.javaClass.simpleName
        return DlnaError.Playback("Playback action '$action' failed: $msg")
    }

    fun mapToSubscriptionError(t: Throwable, serviceId: String): DlnaError.Subscription {
        // Subscription constructor only takes a String
        val msg = t.message ?: t.javaClass.simpleName
        return DlnaError.Subscription("Subscription failed for '$serviceId': $msg")
    }

    fun mapToMediaServerError(t: Throwable, msgPrefix: String): DlnaError.MediaServer {
        // MediaServer constructor only takes a String
        val msg = t.message ?: t.javaClass.simpleName
        return DlnaError.MediaServer("$msgPrefix: $msg")
    }

    /**
     * General mapper for lower-level network exceptions or unknown errors.
     * If the throwable is already a DlnaError, it returns it as-is.
     * Otherwise, it wraps it in a DlnaError.Network (which supports a cause).
     */
    fun map(t: Throwable): DlnaError {
        if (t is DlnaError) return t

        val msg = t.message ?: "Unknown error"

        return when (t) {
            is SocketTimeoutException -> DlnaError.Network("Operation timed out: $msg", t)
            is UnknownHostException -> DlnaError.Network("Host unreachable: $msg", t)
            is IOException -> DlnaError.Network("Network IO error: $msg", t)
            else -> DlnaError.Network("Unexpected error: $msg", t)
        }
    }
}