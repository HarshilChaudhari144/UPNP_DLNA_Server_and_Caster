package com.example.mysecondapp.dlna_lib.core.models

import kotlin.time.Duration

/**
 * Represents the `<res>` tag in DIDL-Lite.
 * Contains the actual streaming URL and metadata.
 * Spec Reference: 14
 */
data class MediaResource(
    val uri: String,
    val protocolInfo: String,
    val mimeType: String,
    val dlnaProfile: String?,
    val flags: String?,
    val resolution: String?,
    val duration: Duration?,
    val size: Long?
)