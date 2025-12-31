package com.example.mysecondapp.dlna_lib.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log
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

    private val TAG = "AndroidSsdpTransport"
    private val ssdpPort = 1900
    private val ssdpGroup = "239.255.255.250"

    private var multicastSocket: MulticastSocket? = null
    private var unicastSocket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private var multicastThread: Thread? = null
    private var unicastThread: Thread? = null

    @Volatile
    private var isRunning = false

    override fun listen(onReceive: (data: String, remoteAddress: String, remotePort: Int) -> Unit) {
        if (isRunning) return
        isRunning = true

        acquireMulticastLock()
        try { setupSockets() } catch (e: Exception) {
            Log.e(TAG, "Socket setup failed", e)
            releaseMulticastLock(); isRunning = false; return
        }

        // Thread 1: Multicast
        multicastThread = Thread {
            val buffer = ByteArray(4096)
            Log.d(TAG, "Multicast Listener Started")
            while (isRunning) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    multicastSocket?.receive(packet)
                    val data = String(packet.data, 0, packet.length)
                    // Log only M-SEARCH to avoid spamming NOTIFY logs
                    if (data.startsWith("M-SEARCH")) {
                        Log.d(TAG, "RX Multicast from ${packet.address.hostAddress}:${packet.port} -> M-SEARCH")
                    }
                    onReceive(data, packet.address.hostAddress ?: "", packet.port)
                } catch (e: IOException) { }
            }
        }.apply { start() }

        // Thread 2: Unicast
        unicastThread = Thread {
            val buffer = ByteArray(4096)
            Log.d(TAG, "Unicast Listener Started")
            while (isRunning) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    unicastSocket?.receive(packet)
                    val data = String(packet.data, 0, packet.length)
                    Log.d(TAG, "RX Unicast from ${packet.address.hostAddress}:${packet.port}")
                    onReceive(data, packet.address.hostAddress ?: "", packet.port)
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
            Log.d(TAG, "TX Multicast (NOTIFY)")
        } catch (e: Exception) { Log.e(TAG, "TX Error", e) }
    }

    override fun sendTo(data: String, address: String, port: Int) {
        if (!isRunning) return
        try {
            val target = InetAddress.getByName(address)
            val bytes = data.toByteArray()
            val packet = DatagramPacket(bytes, bytes.size, target, port)
            unicastSocket?.send(packet)
            Log.d(TAG, "TX Unicast to $address:$port -> RESPONSE SENT")
        } catch (e: Exception) { Log.e(TAG, "TX Unicast Error", e) }
    }

    override fun stop() {
        Log.d(TAG, "Stopping Transport")
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
            if (wifiInterface != null) {
                Log.d(TAG, "Binding Multicast to Interface: ${wifiInterface.displayName}")
                ms.joinGroup(InetSocketAddress(group, ssdpPort), wifiInterface)
            } else {
                Log.w(TAG, "No WiFi Interface found, binding default")
                ms.joinGroup(group)
            }
            multicastSocket = ms
        }
        if (unicastSocket == null || unicastSocket!!.isClosed) {
            val us = DatagramSocket(null)
            us.reuseAddress = true

            val wifiInterface = getWifiNetworkInterface()
            val wifiIp = getIpAddressFromInterface(wifiInterface)

            if (wifiIp != null) {
                Log.d(TAG, "Binding Unicast to IP: $wifiIp")
                us.bind(InetSocketAddress(wifiIp, 0))
            } else {
                Log.w(TAG, "Binding Unicast to Wildcard (0.0.0.0)")
                us.bind(InetSocketAddress(0))
            }
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

    private fun getIpAddressFromInterface(ni: NetworkInterface?): InetAddress? {
        if (ni == null) return null
        val addrs = ni.inetAddresses
        while (addrs.hasMoreElements()) {
            val addr = addrs.nextElement()
            if (!addr.isLoopbackAddress && addr.hostAddress.indexOf(':') < 0) return addr
        }
        return null
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