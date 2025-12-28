package com.example.mysecondapp.dlna_lib

import com.example.mysecondapp.dlna_lib.contract.MediaContentProvider
import com.example.mysecondapp.dlna_lib.contract.MediaThumbnailProvider

/**
 * Configuration object to initialize the library.
 * Spec Reference: 5.2
 */
data class DlnaConfig(
    val enableMediaServer: Boolean,
    val serverName: String,
    val contentProvider: MediaContentProvider?,
    val thumbnailProvider: MediaThumbnailProvider?,
    val eventSubscriptionTimeoutSeconds: Int = 300
)