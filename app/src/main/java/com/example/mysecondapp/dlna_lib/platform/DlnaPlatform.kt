package com.example.mysecondapp.dlna_lib.platform

/**
 * A container interface that provides the Core engine with all
 * necessary platform-specific networking tools.
 */
interface DlnaPlatform {
    val ssdp: SsdpTransport
    val httpServer: HttpServer
    val eventServer: EventCallbackServer
    val mimeResolver: MimeTypeResolver
    val networkInfo: NetworkInfoProvider
}