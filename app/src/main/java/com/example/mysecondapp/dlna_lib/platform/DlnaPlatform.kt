package com.example.mysecondapp.dlna_lib.platform

/**
 * The root interface that the `dlna-android` module must implement
 * and pass into `dlna-core` initialization.
 * Spec Reference: 6.1
 */
interface DlnaPlatform {
    val ssdpTransport: SsdpTransport
    val httpServer: HttpServer
    val eventCallbackServer: EventCallbackServer
    val mimeTypeResolver: MimeTypeResolver
    val networkInfo: NetworkInfoProvider
}