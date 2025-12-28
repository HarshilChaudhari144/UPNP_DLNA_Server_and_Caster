package com.example.mysecondapp.dlna_lib.platform

interface EventCallbackServer {
    /**
     * Starts the dedicated server to listen for GENA NOTIFY requests.
     * @param handler The logic that will parse the incoming XML events.
     */
    fun start(handler: HttpHandler)

    fun stop()

    /**
     * Returns the full URL (e.g., http://192.168.1.5:5000/callback)
     * that the remote device should send notifications to.
     */
    fun getCallbackUrl(): String
}