package com.example.mysecondapp.dlna_lib.android

import android.content.Context
import com.example.mysecondapp.dlna_lib.platform.DlnaPlatform
import com.example.mysecondapp.dlna_lib.platform.EventCallbackServer
import com.example.mysecondapp.dlna_lib.platform.HttpServer
import com.example.mysecondapp.dlna_lib.platform.MimeTypeResolver
import com.example.mysecondapp.dlna_lib.platform.NetworkInfoProvider
import com.example.mysecondapp.dlna_lib.platform.SsdpTransport

/**
 * Concrete implementation of the Platform Interface for Android.
 * Pass this to DlnaManager.start().
 */
class AndroidDlnaPlatform(context: Context) : DlnaPlatform {

    // 1. Network Utilities
    override val networkInfo: NetworkInfoProvider = AndroidNetworkInfoProvider(context)

    // 2. MIME Resolution
    override val mimeResolver: MimeTypeResolver = AndroidMimeTypeResolver()

    // 3. UDP Transport (SSDP)
    // Passes context to acquire MulticastLock
    override val ssdp: SsdpTransport = AndroidSsdpTransport(context)

    // 4. HTTP Server (NanoHTTPD Wrapper)
    override val httpServer: HttpServer = AndroidHttpServer()

    // 5. Event Server (Raw ServerSocket)
    // Needs networkInfo to generate the Callback URL (IP address)
    override val eventServer: EventCallbackServer = AndroidEventCallbackServer(networkInfo)
}