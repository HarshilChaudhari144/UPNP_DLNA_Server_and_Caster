package com.example.mysecondapp.dlna_lib.api.device

import kotlinx.coroutines.flow.Flow

/**
 * The public-facing registry to access discovered DLNA devices.
 */
interface DeviceRegistry {
    /**
     * A reactive stream of all currently available devices.
     */
    val devices: Flow<List<Device>>

    /**
     * Finds a specific device by its ID.
     */
    fun getDevice(deviceId: DeviceId): Device?

    /**
     * Filters the registry for Media Servers (sources of content).
     */
    fun getMediaServers(): List<Device>

    /**
     * Filters the registry for Media Renderers (TVs, Speakers).
     */
    fun getMediaRenderers(): List<Device>

    /**
     * Triggers an active network scan (SSDP M-SEARCH) to find new devices
     * or verify existing ones.
     */
    fun refresh()
}