package com.example.mysecondapp.dlna_lib.core.device

import com.example.mysecondapp.dlna_lib.api.device.Device
import com.example.mysecondapp.dlna_lib.api.device.DeviceId
import com.example.mysecondapp.dlna_lib.api.device.DeviceRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class DeviceRepository : DeviceRegistry {

    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    override val devices: StateFlow<List<Device>> = _devices.asStateFlow()

    /**
     * Internal method for the Core to add or update a device.
     */
    fun upsert(device: Device) {
        val current = _devices.value.toMutableList()
        val index = current.indexOfFirst { it.deviceId == device.deviceId }
        if (index != -1) {
            current[index] = device
        } else {
            current.add(device)
        }
        _devices.value = current
    }

    fun remove(deviceId: DeviceId) {
        _devices.value = _devices.value.filter { it.deviceId != deviceId }
    }

    override fun getDevice(deviceId: DeviceId): Device? =
        _devices.value.find { it.deviceId == deviceId }

    override fun getMediaServers(): List<Device> =
        _devices.value.filter { it.deviceType.contains("MediaServer") }

    override fun getMediaRenderers(): List<Device> =
        _devices.value.filter { it.deviceType.contains("MediaRenderer") }
}