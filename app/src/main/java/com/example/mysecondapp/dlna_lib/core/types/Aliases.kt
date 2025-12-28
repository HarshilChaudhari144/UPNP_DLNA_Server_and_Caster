package com.example.mysecondapp.dlna_lib.core.types

/**
 * Domain-specific aliases for String identifiers.
 * Spec Reference: 11
 */
typealias DeviceId = String     // Must match UDN
typealias ServiceId = String    // e.g., "urn:upnp-org:serviceId:ContentDirectory"
typealias ContainerId = String  // e.g., "0" for root
typealias MediaId = String      // Unique ID of a media item