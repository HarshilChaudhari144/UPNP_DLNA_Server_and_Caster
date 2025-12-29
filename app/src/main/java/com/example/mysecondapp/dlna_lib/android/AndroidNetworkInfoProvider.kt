package com.example.mysecondapp.dlna_lib.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.example.mysecondapp.dlna_lib.platform.NetworkInfoProvider
import java.net.Inet4Address

class AndroidNetworkInfoProvider(
    private val context: Context
) : NetworkInfoProvider {

    override fun getCurrentIpAddress(): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = cm.activeNetwork ?: return null
        val linkProperties = cm.getLinkProperties(activeNetwork) ?: return null

        // DLNA usually runs on IPv4. We filter specifically for that.
        for (linkAddress in linkProperties.linkAddresses) {
            val address = linkAddress.address
            if (address is Inet4Address && !address.isLoopbackAddress) {
                return address.hostAddress
            }
        }
        return null
    }

    override fun isWifiConnected(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false

        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}