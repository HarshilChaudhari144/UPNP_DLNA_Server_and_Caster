package com.example.mysecondapp.dlna_lib.platform

interface NetworkInfoProvider {
    /**
     * Returns the current local IP address of the device on the Wi-Fi network.
     * Returns null if not connected to Wi-Fi.
     */
    fun getCurrentIpAddress(): String?

    /**
     * Observable flag to detect when the network changes.
     */
    fun isWifiConnected(): Boolean
}