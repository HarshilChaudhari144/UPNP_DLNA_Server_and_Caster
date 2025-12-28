package com.example.mysecondapp.dlna_lib.api

import com.example.mysecondapp.dlna_lib.core.models.Device
import kotlinx.coroutines.flow.Flow

/**
 * API to access discovered devices on the network.
 * Spec Reference: 5.3
 */
interface DeviceRegistry {
    /**
     * Live stream of currently active devices.
     */
    val devices: Flow<List<Device>>
    
    fun getDevice(deviceId: String): Device?
    
    fun getMediaServers(): List<Device>
    
    fun getMediaRenderers(): List<Device>
}