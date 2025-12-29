package com.example.mysecondapp.dlna_lib.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import com.example.mysecondapp.dlna_lib.platform.SsdpTransport
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface

class AndroidSsdpTransport(
    private val context: Context
) : SsdpTransport {

    private val ssdpPort = 1900
    private val ssdpGroup = "239.255.255.250"

    // Socket 1: Passive Listener (Port 1900) - For NOTIFY
    private var multicastSocket: MulticastSocket? = null

    // Socket 2: Active Searcher (Random Port) - For Sending M-SEARCH & Receiving Responses
    private var unicastSocket: DatagramSocket? = null

    private var multicastLock: WifiManager.MulticastLock? = null

    // We need two threads now
    private var multicastThread: Thread? = null
    private var unicastThread: Thread? = null

    @Volatile
    private var isRunning = false

    override fun listen(onReceive: (data: String, remoteAddress: String) -> Unit) {
        if (isRunning) return
        isRunning = true

        acquireMulticastLock()

        try {
            setupSockets()
        } catch (e: Exception) {
            e.printStackTrace()
            releaseMulticastLock()
            isRunning = false
            return
        }

        // Thread 1: Listen for Multicast NOTIFY (Port 1900)
        multicastThread = Thread {
            val buffer = ByteArray(4096)
            while (isRunning) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    multicastSocket?.receive(packet)
                    val data = String(packet.data, 0, packet.length)
                    val address = packet.address.hostAddress ?: "unknown"
                    onReceive(data, address)
                } catch (e: IOException) {
                    // Socket closed
                }
            }
        }.apply { start() }

        // Thread 2: Listen for Unicast Responses (Random Port)
        unicastThread = Thread {
            val buffer = ByteArray(4096)
            while (isRunning) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    unicastSocket?.receive(packet) // Blocks until response matches
                    val data = String(packet.data, 0, packet.length)
                    val address = packet.address.hostAddress ?: "unknown"
                    onReceive(data, address)
                } catch (e: IOException) {
                    // Socket closed
                }
            }
        }.apply { start() }
    }

    override fun send(data: String) {
        if (!isRunning) return

        try {
            val group = InetAddress.getByName(ssdpGroup)
            val bytes = data.toByteArray()
            val packet = DatagramPacket(bytes, bytes.size, group, ssdpPort)

            // CRITICAL CHANGE: Send via the Unicast Socket (Random Port).
            // This ensures the TV replies to this random port, where we are listening in Thread 2.
            unicastSocket?.send(packet)

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun stop() {
        isRunning = false

        multicastSocket?.close()
        multicastSocket = null

        unicastSocket?.close()
        unicastSocket = null

        multicastThread?.interrupt()
        unicastThread?.interrupt()

        releaseMulticastLock()
    }

    private fun setupSockets() {
        // 1. Setup Multicast (1900)
        if (multicastSocket == null || multicastSocket!!.isClosed) {
            val ms = MulticastSocket(null)
            ms.reuseAddress = true
            ms.bind(InetSocketAddress(ssdpPort))
            val group = InetAddress.getByName(ssdpGroup)

            val wifiInterface = getWifiNetworkInterface()
            if (wifiInterface != null) {
                ms.joinGroup(InetSocketAddress(group, ssdpPort), wifiInterface)
            } else {
                ms.joinGroup(group)
            }
            multicastSocket = ms
        }

        // 2. Setup Unicast (Random Port)
        if (unicastSocket == null || unicastSocket!!.isClosed) {
            // Bind to null (wildcard address) and 0 (random port)
            val us = DatagramSocket(null)
            us.reuseAddress = true
            us.bind(InetSocketAddress(0))
            unicastSocket = us
        }
    }

    private fun getWifiNetworkInterface(): NetworkInterface? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return null

        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null

        val linkProperties = cm.getLinkProperties(activeNetwork) ?: return null
        val ifaceName = linkProperties.interfaceName ?: return null

        return NetworkInterface.getByName(ifaceName)
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