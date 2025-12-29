package com.example.mysecondapp.dlna_lib.android

import android.content.Context
import android.net.wifi.WifiManager
import com.example.mysecondapp.dlna_lib.platform.SsdpTransport
import java.io.IOException
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface

class AndroidSsdpTransport(
    private val context: Context
) : SsdpTransport {

    private val ssdpPort = 1900
    private val ssdpGroup = "239.255.255.250"

    private var multicastSocket: MulticastSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var listenThread: Thread? = null

    @Volatile
    private var isRunning = false


    override fun listen(onReceive: (data: String, remoteAddress: String) -> Unit) {
        if (isRunning) return
        isRunning = true

        acquireMulticastLock()

        try {
            setupSocket()
        } catch (e: Exception) {
            e.printStackTrace()
            releaseMulticastLock()
            isRunning = false
            return
        }

        listenThread = Thread {
            val buffer = ByteArray(4096) // Large buffer for headers
            while (isRunning) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    multicastSocket?.receive(packet)

                    val data = String(packet.data, 0, packet.length)
                    val address = packet.address.hostAddress ?: "unknown"

                    onReceive(data, address)

                } catch (e: IOException) {
                    if (isRunning) {
                        e.printStackTrace()
                    }
                }
            }
        }.apply { start() }
    }

    override fun send(data: String) {
        if (multicastSocket == null || !isRunning) {
            // Attempt to setup if needed, or throw
            try { setupSocket() } catch (e: Exception) { return }
        }

        try {
            val group = InetAddress.getByName(ssdpGroup)
            val bytes = data.toByteArray()
            val packet = DatagramPacket(bytes, bytes.size, group, ssdpPort)

            // Send usually happens on IO dispatcher from Core, but MulticastSocket is thread-safe
            multicastSocket?.send(packet)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun stop() {
        isRunning = false

        // Close socket to break the receive loop
        multicastSocket?.close()
        multicastSocket = null

        listenThread?.interrupt()
        listenThread = null

        releaseMulticastLock()
    }

    private fun setupSocket() {
        if (multicastSocket != null && !multicastSocket!!.isClosed) return

        val socket = MulticastSocket(null)
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(ssdpPort))

        val group = InetAddress.getByName(ssdpGroup)

        // Android 11+ might need specific interface, but default usually works for local subnet
        // If needed, iterate NetworkInterfaces to find WiFi
        socket.joinGroup(group)

        multicastSocket = socket
    }

    private fun acquireMulticastLock() {
        if (multicastLock == null) {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("dlna_library_lock")
            multicastLock?.setReferenceCounted(true)
        }
        multicastLock?.acquire()
    }

    private fun releaseMulticastLock() {
        if (multicastLock?.isHeld == true) {
            multicastLock?.release()
        }
    }
}