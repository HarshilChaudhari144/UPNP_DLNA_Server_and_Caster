package com.example.mysecondapp.dlna_lib.platform

interface SsdpTransport {
    /**
     * Sends an SSDP message (e.g., M-SEARCH or NOTIFY) to the multicast group.
     */
    fun send(data: String)

    /**
     * Starts listening for incoming SSDP packets.
     * @param onReceive Callback triggered whenever a packet arrives.
     * Provides the raw packet data and the sender's address.
     */
    fun listen(onReceive: (data: String, remoteAddress: String) -> Unit)

    /**
     * Stops the UDP listener and releases the socket.
     */
    fun stop()
}