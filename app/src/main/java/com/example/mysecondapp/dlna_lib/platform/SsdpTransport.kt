package com.example.mysecondapp.dlna_lib.platform

interface SsdpTransport {
    // Broadcast/Multicast send
    fun send(data: String)

    // Unicast send (Reply to a specific device)
    fun sendTo(data: String, address: String, port: Int)

    // Listen with port info
    fun listen(onReceive: (data: String, remoteAddress: String, remotePort: Int) -> Unit)

    fun stop()
}