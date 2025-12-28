package com.example.mysecondapp.dlna_lib.android.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import com.example.mysecondapp.dlna_lib.platform.NetworkInfoProvider
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Android implementation to find the current active IP address.
 * Spec Reference: 6.2
 */
class AndroidNetworkInfoProvider(private val context: Context) : NetworkInfoProvider {

    override val localAddress: InetAddress
        get() = findActiveIpAddress() ?: InetAddress.getLoopbackAddress()

    override val interfaceName: String?
        get() = try {
            NetworkInterface.getByInetAddress(localAddress)?.name
        } catch (e: Exception) {
            null
        }

    private fun findActiveIpAddress(): InetAddress? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = cm.activeNetwork ?: return null
        val linkProperties = cm.getLinkProperties(activeNetwork) ?: return null
        
        // Prioritize IPv4 for DLNA compatibility
        return linkProperties.linkAddresses
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
    }
}