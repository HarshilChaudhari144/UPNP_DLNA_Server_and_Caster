package com.example.mysecondapp.dlna_lib.platform

/**
 * Specific server info for GENA event callbacks.
 * Spec Reference: 6.5
 */
interface EventCallbackServer {
    /**
     * The publicly accessible URL that external devices should call
     * to deliver GENA events to this device.
     * e.g., "http://192.168.1.5:8080/callback"
     */
    val callbackUrl: String
}