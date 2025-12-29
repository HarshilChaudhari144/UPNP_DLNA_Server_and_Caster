package com.example.mysecondapp.dlna_lib.api.device

typealias DeviceId = String
typealias ServiceId = String

/**
 * Represents a physical DLNA device on the network.
 */
data class Device(
    val deviceId: DeviceId,
    val deviceType: String,
    val friendlyName: String,
    val manufacturer: String?,
    val modelName: String?,
    val udn: String, // Unique Device Name
    val services: List<Service>,
    val presentationUrl: String?,
    val locationUrl: String
)

/**
 * Represents a specific service (like Playback or Content Search) within a Device.
 */
data class Service(
    val serviceType: String,
    val serviceId: ServiceId,
    val controlUrl: String,
    val eventSubUrl: String?,
    val scpdUrl: String // Service Control Protocol Document (the XML description)
)