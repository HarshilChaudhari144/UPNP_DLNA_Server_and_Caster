package com.example.mysecondapp.dlna_lib.api

import com.example.mysecondapp.dlna_lib.core.models.MediaContainer
import com.example.mysecondapp.dlna_lib.core.models.MediaItem

/**
 * API for browsing content on remote Media Servers.
 * Spec Reference: 5.4
 */
interface BrowseApi {
    suspend fun browse(
        deviceId: String,
        containerId: String,
        startIndex: Int,
        count: Int
    ): BrowseResult

    suspend fun browseRoot(deviceId: String): BrowseResult
}

/**
 * The result of a browse operation.
 * Spec Reference: 5.4
 */
data class BrowseResult(
    val containers: List<MediaContainer>,
    val items: List<MediaItem>,
    val totalMatches: Int,
    val numberReturned: Int
)