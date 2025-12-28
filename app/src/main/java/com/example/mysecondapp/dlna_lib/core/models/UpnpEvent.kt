package com.example.mysecondapp.dlna_lib.core.models

import com.example.mysecondapp.dlna_lib.core.types.ServiceId

/**
 * Represents a GENA event payload received from a device.
 * Spec Reference: 17
 */
data class UpnpEvent(
    val serviceId: ServiceId,
    val sequence: Long,
    val properties: Map<String, String> // Key-Value pairs of state variables
)