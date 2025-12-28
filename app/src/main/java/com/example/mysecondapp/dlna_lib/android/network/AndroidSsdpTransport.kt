package com.example.mysecondapp.dlna_lib.android.network

import android.net.wifi.WifiManager
import android.util.Log
import com.example.mysecondapp.dlna_lib.platform.SsdpTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface

/**
 * MulticastSocket implementation for SSDP.
 * Handles the Android MulticastLock weirdness.
 * Spec Reference: 6.3
 */
class AndroidSsdpTransport(
    private val wifiManager: WifiManager,
    private val networkInterfaceName: String?
) : SsdpTransport {

    private val multicastLock: WifiManager.MulticastLock = wifiManager.createMulticastLock("dlna-ssdp-lock").apply {
        setReferenceCounted(true)
    }

    private var socket: MulticastSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private var receiveJob: Job? = null

    private val _incomingPackets = MutableSharedFlow<ByteArray>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val incomingPackets = _incomingPackets.asSharedFlow()

    override fun start() {
        if (socket != null) return // Already running

        try {
            multicastLock.acquire()
            
            // SSDP Standard Port and Group
            val group = InetAddress.getByName("239.255.255.250")
            val port = 1900

            socket = MulticastSocket(port).apply {
                reuseAddress = true
                // Bind to specific interface if known to avoid routing issues
                if (networkInterfaceName != null) {
                    val nif = NetworkInterface.getByName(networkInterfaceName)
                    if (nif != null) {
                        networkInterface = nif
                    }
                }
                joinGroup(InetSocketAddress(group, port), networkInterface)
                timeToLive = 4 // Standard UPnP TTL
            }

            startReceiving()
            Log.d("AndroidSsdpTransport", "SSDP Transport Started")
        } catch (e: Exception) {
            Log.e("AndroidSsdpTransport", "Failed to start SSDP", e)
            cleanup()
        }
    }

    private fun startReceiving() {
        receiveJob = scope.launch {
            val buffer = ByteArray(4096)
            while (isActive) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket?.receive(packet)
                    // Copy data to ensure thread safety
                    val data = packet.data.copyOfRange(packet.offset, packet.length)
                    _incomingPackets.emit(data)
                } catch (e: Exception) {
                    if (isActive) Log.e("AndroidSsdpTransport", "Receive error", e)
                }
            }
        }
    }

    override fun send(packet: ByteArray) {
        scope.launch {
            try {
                val group = InetAddress.getByName("239.255.255.250")
                val port = 1900
                val dp = DatagramPacket(packet, packet.size, group, port)
                socket?.send(dp)
            } catch (e: Exception) {
                Log.e("AndroidSsdpTransport", "Send error", e)
            }
        }
    }

    override fun stop() {
        receiveJob?.cancel()
        cleanup()
    }

    private fun cleanup() {
        try {
            if (multicastLock.isHeld) multicastLock.release()
            socket?.leaveGroup(InetAddress.getByName("239.255.255.250"))
            socket?.close()
        } catch (e: Exception) {
            // Ignore close errors
        } finally {
            socket = null
        }
    }
}