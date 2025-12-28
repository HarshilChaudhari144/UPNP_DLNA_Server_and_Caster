package com.example.mysecondapp.dlna_lib.core.models

import com.example.mysecondapp.dlna_lib.core.types.ServiceId

/**
 * Represents a UPnP Service (e.g., AVTransport, ConnectionManager).
 * Spec Reference: 12.2
 */
data class Service(
    val serviceType: String, // e.g., "urn:schemas-upnp-org:service:AVTransport:1"
    val serviceId: ServiceId,
    val controlUrl: String,
    val eventSubUrl: String?,
    val scpdUrl: String
)