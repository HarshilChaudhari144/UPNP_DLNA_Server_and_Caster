package com.example.mysecondapp.dlna_lib.core.models

import com.example.mysecondapp.dlna_lib.core.types.ContainerId
import com.example.mysecondapp.dlna_lib.core.types.MediaId
import kotlin.time.Duration

/**
 * Root interface for the content hierarchy.
 * Spec Reference: 13.1
 */
sealed interface MediaObject {
    val id: String
    val parentId: String?
    val title: String
}

/**
 * A folder or container that holds other objects.
 * Spec Reference: 13.2
 */
data class MediaContainer(
    override val id: ContainerId,
    override val parentId: ContainerId?,
    override val title: String,
    val childCount: Int?,
    val searchable: Boolean
) : MediaObject

/**
 * A playable item (Video, Audio, Image).
 * Spec Reference: 13.3
 */
data class MediaItem(
    override val id: MediaId,
    override val parentId: ContainerId,
    override val title: String,
    val upnpClass: String, // e.g., "object.item.videoItem"
    val mediaType: MediaType,
    val duration: Duration?,
    val size: Long?,
    val resources: List<MediaResource>,
    val thumbnail: MediaThumbnail?
) : MediaObject