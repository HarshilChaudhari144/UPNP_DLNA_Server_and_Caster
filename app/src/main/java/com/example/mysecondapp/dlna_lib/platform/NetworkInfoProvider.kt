package com.example.mysecondapp.dlna_lib.platform

import java.net.InetAddress

/**
 * Provides information about the active network interface.
 * Spec Reference: 6.2
 */
interface NetworkInfoProvider {
    val localAddress: InetAddress
    val interfaceName: String?
}