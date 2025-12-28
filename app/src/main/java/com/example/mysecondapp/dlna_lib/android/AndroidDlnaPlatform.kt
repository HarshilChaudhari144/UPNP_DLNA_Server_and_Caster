package com.example.mysecondapp.dlna_lib.android

import android.content.Context
import android.net.wifi.WifiManager
import com.example.mysecondapp.dlna_lib.android.network.AndroidNetworkInfoProvider
import com.example.mysecondapp.dlna_lib.android.network.AndroidSsdpTransport
import com.example.mysecondapp.dlna_lib.android.server.NanoHttpServerWrapper
import com.example.mysecondapp.dlna_lib.android.util.AndroidMimeTypeResolver
import com.example.mysecondapp.dlna_lib.platform.DlnaPlatform
import com.example.mysecondapp.dlna_lib.platform.EventCallbackServer
import com.example.mysecondapp.dlna_lib.platform.HttpServer
import com.example.mysecondapp.dlna_lib.platform.MimeTypeResolver
import com.example.mysecondapp.dlna_lib.platform.NetworkInfoProvider
import com.example.mysecondapp.dlna_lib.platform.SsdpTransport

/**
 * The concrete Android implementation of the Platform Interface.
 * Pass this to DlnaManager.start() in your App.
 * Spec Reference: 6.1
 */
class AndroidDlnaPlatform(context: Context) : DlnaPlatform {

    // 1. Network Info
    override val networkInfo: NetworkInfoProvider = AndroidNetworkInfoProvider(context)

    // 2. SSDP
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    override val ssdpTransport: SsdpTransport = AndroidSsdpTransport(wifiManager, networkInfo.interfaceName)

    // 3. HTTP Server (NanoHTTPD)
    // We create one server instance used for both Media Serving and GENA Callbacks
    private val nanoWrapper = NanoHttpServerWrapper(networkInfo.localAddress, 0) // Port 0 = auto
    
    override val httpServer: HttpServer = nanoWrapper

    // 4. GENA Callback Server Info
    // This is just a view on the HTTP server, calculating the URL
    override val eventCallbackServer: EventCallbackServer = object : EventCallbackServer {
        override val callbackUrl: String
            get() {
                // Ensure server is started to get port
                if (nanoWrapper.getListeningPort() == 0) nanoWrapper.start() 
                val port = nanoWrapper.getListeningPort()
                val ip = networkInfo.localAddress.hostAddress
                return "http://$ip:$port/_gena/callback"
            }
    }

    // 5. MIME Types
    override val mimeTypeResolver: MimeTypeResolver = AndroidMimeTypeResolver()
}