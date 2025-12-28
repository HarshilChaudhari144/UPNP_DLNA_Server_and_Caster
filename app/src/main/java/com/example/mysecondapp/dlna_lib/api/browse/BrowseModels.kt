package com.example.mysecondapp.dlna_lib.api.browse

import com.example.mysecondapp.dlna_lib.api.media.MediaContainer
import com.example.mysecondapp.dlna_lib.api.media.MediaItem

/**
 * Data returned from a successful browse request.
 * * @property containers Folders found in this directory.
 * @property items Playable files found in this directory.
 * @property totalMatches The total number of items available on the server (for scrollbar logic).
 * @property numberReturned The number of objects returned in this specific response.
 */
data class BrowseResult(
    val containers: List<MediaContainer>,
    val items: List<MediaItem>,
    val totalMatches: Int,
    val numberReturned: Int
)