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

    private var multicastSocket: MulticastSocket? = null
    private var unicastSocket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private var multicastThread: Thread? = null
    private var unicastThread: Thread? = null

    @Volatile private var isRunning = false

    override fun listen(onReceive: (data: String, address: InetAddress, port: Int) -> Unit) {
        if (isRunning) return
        isRunning = true

        acquireMulticastLock()
        try { setupSockets() } catch (e: Exception) {
            releaseMulticastLock(); isRunning = false; return
        }

        // Thread 1: Multicast
        multicastThread = Thread {
            val buffer = ByteArray(4096)
            while (isRunning) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    multicastSocket?.receive(packet)
                    val data = String(packet.data, 0, packet.length)
                    onReceive(data, packet.address, packet.port)
                } catch (e: IOException) { }
            }
        }.apply { start() }

        // Thread 2: Unicast
        unicastThread = Thread {
            val buffer = ByteArray(4096)
            while (isRunning) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    unicastSocket?.receive(packet)
                    val data = String(packet.data, 0, packet.length)
                    onReceive(data, packet.address, packet.port)
                } catch (e: IOException) { }
            }
        }.apply { start() }
    }

    override fun send(data: String) {
        if (!isRunning) return
        try {
            val group = InetAddress.getByName(ssdpGroup)
            val bytes = data.toByteArray()
            val packet = DatagramPacket(bytes, bytes.size, group, ssdpPort)
            unicastSocket?.send(packet)
        } catch (e: Exception) { e.printStackTrace() }
    }

    override fun sendDirect(data: String, address: InetAddress, port: Int) {
        if (!isRunning) return
        try {
            val bytes = data.toByteArray()
            val packet = DatagramPacket(bytes, bytes.size, address, port)
            unicastSocket?.send(packet)
        } catch (e: Exception) { e.printStackTrace() }
    }

    override fun stop() {
        isRunning = false
        multicastSocket?.close(); multicastSocket = null
        unicastSocket?.close(); unicastSocket = null
        multicastThread?.interrupt()
        unicastThread?.interrupt()
        releaseMulticastLock()
    }

    private fun setupSockets() {
        if (multicastSocket == null || multicastSocket!!.isClosed) {
            val ms = MulticastSocket(null)
            ms.reuseAddress = true
            ms.bind(InetSocketAddress(ssdpPort))
            val group = InetAddress.getByName(ssdpGroup)
            val wifiInterface = getWifiNetworkInterface()
            if (wifiInterface != null) ms.joinGroup(InetSocketAddress(group, ssdpPort), wifiInterface)
            else ms.joinGroup(group)
            multicastSocket = ms
        }
        if (unicastSocket == null || unicastSocket!!.isClosed) {
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
        if (multicastLock?.isHeld == true) multicastLock?.release()
    }
}