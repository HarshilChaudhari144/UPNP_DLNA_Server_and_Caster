package com.example.mysecondapp.dlna_lib.api.media

import java.io.InputStream

/**
 * Optional interface to generate thumbnails for the DLNA server on the fly.
 */
interface MediaThumbnailProvider {
    fun openThumbnail(
        mediaId: MediaId,
        maxWidth: Int,
        maxHeight: Int
    ): ThumbnailResult?
}

data class ThumbnailResult(
    val mimeType: String,
    val inputStream: InputStream,
    val size: Long?
)