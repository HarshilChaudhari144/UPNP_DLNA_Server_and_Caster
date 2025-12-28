package com.example.mysecondapp.dlna_lib.contract

import java.io.InputStream

/**
 * Contract for the app to provide thumbnails for media items.
 * Spec Reference: 7.2
 */
interface MediaThumbnailProvider {
    fun openThumbnail(
        mediaId: String,
        maxWidth: Int,
        maxHeight: Int
    ): ThumbnailResult?
}

/**
 * Result container for a thumbnail request.
 * Spec Reference: 7.2
 */
data class ThumbnailResult(
    val mimeType: String,
    val inputStream: InputStream,
    val size: Long?
)