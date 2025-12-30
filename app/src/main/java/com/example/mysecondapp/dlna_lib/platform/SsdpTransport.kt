package com.example.mysecondapp.dlna_lib.platform

import java.net.InetAddress

interface SsdpTransport {
    /**
     * Sends a Multicast packet (Standard usage).
     */
    fun send(data: String)

    /**
     * Sends a Unicast packet to a specific IP and Port (For replying to M-SEARCH).
     */
    fun sendDirect(data: String, address: InetAddress, port: Int)

    /**
     * Starts listening.
     * Callback now includes the sender's Port and full InetAddress.
     */
    fun listen(onReceive: (data: String, address: InetAddress, port: Int) -> Unit)

    fun stop()
}