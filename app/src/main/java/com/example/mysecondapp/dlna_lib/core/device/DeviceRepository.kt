package com.example.mysecondapp.dlna_lib.core.device

import com.example.mysecondapp.dlna_lib.api.device.Device
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal class DeviceRepository {

    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices.asStateFlow()

    fun upsert(device: Device) {
        _devices.update { currentList ->
            val mutable = currentList.toMutableList()
            val index = mutable.indexOfFirst { it.udn == device.udn }
            if (index >= 0) {
                mutable[index] = device // Update existing
            } else {
                mutable.add(device) // Add new
            }
            mutable
        }
    }

    fun remove(deviceId: String) {
        _devices.update { currentList ->
            currentList.filterNot { it.deviceId == deviceId }
        }
    }

    fun getDevice(deviceId: String): Device? {
        return _devices.value.find { it.deviceId == deviceId }
    }

    fun getRenderers(): List<Device> {
        return _devices.value.filter { device ->
            // A Renderer must have AVTransport service
            device.services.any { it.serviceType.contains("AVTransport") }
        }
    }

    fun getServers(): List<Device> {
        return _devices.value.filter { device ->
            // A Server must have ContentDirectory service
            device.services.any { it.serviceType.contains("ContentDirectory") }
        }
    }

    fun clear() {
        _devices.value = emptyList()
    }
}