package com.example.mysecondapp.dlna_lib.core.models

import com.example.mysecondapp.dlna_lib.core.types.DeviceId

/**
 * Represents a discovered UPnP Device (e.g., a TV or Media Server).
 * Spec Reference: 12.1
 */
data class Device(
    val deviceId: DeviceId, // Identical to UDN
    val deviceType: String,
    val friendlyName: String,
    val manufacturer: String?,
    val modelName: String?,
    val modelDescription: String?,
    val udn: String,
    val services: List<Service>,
    val presentationUrl: String?,
    val locationUrl: String
)