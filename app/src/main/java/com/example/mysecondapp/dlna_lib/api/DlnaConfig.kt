package com.example.mysecondapp.dlna_lib.api

import com.example.mysecondapp.dlna_lib.api.media.MediaContentProvider
import com.example.mysecondapp.dlna_lib.api.media.MediaThumbnailProvider

/**
 * Configuration for the DLNA library.
 * * @property enableMediaServer If true, your app will appear as a Media Server to others.
 * @property serverName The name that will appear on other DLNA devices.
 * @property contentProvider Required if [enableMediaServer] is true. Provides the media list.
 * @property eventSubscriptionTimeoutSeconds How long to stay subscribed to device updates.
 */
data class DlnaConfig(
    val enableMediaServer: Boolean,
    val serverName: String,
    val contentProvider: MediaContentProvider? = null,
    val thumbnailProvider: MediaThumbnailProvider? = null,
    val eventSubscriptionTimeoutSeconds: Int = 300
)