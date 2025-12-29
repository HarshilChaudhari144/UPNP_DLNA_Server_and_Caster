package com.example.mysecondapp.dlna_lib.core.ssdp

import com.example.mysecondapp.dlna_lib.core.device.DeviceStateMachine
import com.example.mysecondapp.dlna_lib.core.lifecycle.dlnaScope
import com.example.mysecondapp.dlna_lib.platform.SsdpTransport
import kotlinx.coroutines.launch

internal class SsdpController(
    private val transport: SsdpTransport,
    private val stateMachine: DeviceStateMachine
) {
    fun start() {
        dlnaScope.launch {
            // 1. Start listening for incoming packets
            transport.listen { data, remoteAddress ->
                handleIncomingPacket(data, remoteAddress)
            }
            // 2. Send initial M-SEARCH to find existing devices
            search()
        }
    }

    fun stop() {
        transport.stop()
    }

    private fun search() {
        val searchMessage = """
            M-SEARCH * HTTP/1.1
            HOST: 239.255.255.250:1900
            MAN: "ssdp:discover"
            MX: 3
            ST: upnp:rootdevice
            
        """.trimIndent().replace("\n", "\r\n")

        transport.send(searchMessage)
    }

    private fun handleIncomingPacket(data: String, remoteAddress: String) {
        val lines = data.split("\r\n")
        val headers = lines.associate { line ->
            val separatorIndex = line.indexOf(":")
            if (separatorIndex != -1) {
                line.substring(0, separatorIndex).uppercase().trim() to
                        line.substring(separatorIndex + 1).trim()
            } else {
                "" to ""
            }
        }

        val nts = headers["NTS"] // Notification Sub Type
        val location = headers["LOCATION"]
        val usn = headers["USN"]

        when {
            // Device is announcing itself (Alive) or responding to our Search
            location != null && (nts == "ssdp:alive" || nts == null) -> {
                stateMachine.onDeviceDiscovered(location)
            }
            // Device is shutting down
            nts == "ssdp:byebye" && usn != null -> {
                stateMachine.onDeviceOffline(usn)
            }
        }
    }
}