package com.example.mysecondapp.dlna_lib.platform

import kotlinx.coroutines.flow.Flow

/**
 * Handles the sending and receiving of raw UDP Multicast packets.
 * Spec Reference: 6.3
 */
interface SsdpTransport {
    fun start()
    fun stop()
    
    /**
     * Sends a raw SSDP packet (M-SEARCH or NOTIFY).
     */
    fun send(packet: ByteArray)
    
    /**
     * A hot flow of incoming UDP packets on the multicast group.
     */
    val incomingPackets: Flow<ByteArray>
}