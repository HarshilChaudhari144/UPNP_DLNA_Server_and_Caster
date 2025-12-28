package com.example.mysecondapp.dlna_lib.platform.model

/**
 * Represents the MIME type and DLNA-specific flags for a media resource.
 * Spec Reference: 6.6
 */
data class MimeInfo(
    val mimeType: String,
    val dlnaProfile: String?,
    val flags: String
)