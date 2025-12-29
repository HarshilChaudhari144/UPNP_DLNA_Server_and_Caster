package com.example.mysecondapp.dlna_lib.api.media

import kotlin.time.Duration

typealias MediaId = String
typealias ContainerId = String

/**
 * Base interface for anything that can exist in a Media Server.
 */
sealed interface MediaObject {
    val id: String
    val parentId: String?
    val title: String
}

/**
 * Represents a "Folder" or "Album" that can contain other objects.
 */
data class MediaContainer(
    override val id: ContainerId,
    override val parentId: ContainerId?,
    override val title: String,
    val childCount: Int? = null,
    val searchable: Boolean = false
) : MediaObject

enum class MediaType { AUDIO, VIDEO, IMAGE, UNKNOWN }

/**
 * Represents a playable file (Song, Movie, Photo).
 */
data class MediaItem(
    override val id: MediaId,
    override val parentId: ContainerId,
    override val title: String,
    val upnpClass: String, // e.g., "object.item.videoItem"
    val mediaType: MediaType,
    val resources: List<MediaResource>,
    val thumbnail: MediaThumbnail? = null
) : MediaObject

/**
 * Technical details of the stream (URL, Bitrate, MimeType).
 */
data class MediaResource(
    val uri: String,
    val protocolInfo: String,
    val mimeType: String,
    val size: Long? = null,
    val duration: Duration? = null,
    val resolution: String? = null
)

/**
 * Metadata for the artwork associated with an item.
 */
data class MediaThumbnail(
    val uri: String,
    val mimeType: String,
    val width: Int? = null,
    val height: Int? = null
)