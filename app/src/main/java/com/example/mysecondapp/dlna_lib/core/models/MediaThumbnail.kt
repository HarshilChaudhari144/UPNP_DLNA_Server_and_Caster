package com.example.mysecondapp.dlna_lib.core.models

/**
 * Represents album art or video poster.
 * Spec Reference: 15
 */
data class MediaThumbnail(
    val uri: String,
    val mimeType: String,
    val width: Int?,
    val height: Int?
)