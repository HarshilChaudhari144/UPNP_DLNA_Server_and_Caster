package com.example.mysecondapp.dlna_lib.contract

import com.example.mysecondapp.dlna_lib.core.models.MediaObject

/**
 * Contract for the app to expose its media hierarchy to the DLNA server.
 * Spec Reference: 7.1
 */
interface MediaContentProvider {
    /**
     * Return the children of a specific container.
     */
    suspend fun list(containerId: String): List<MediaObject>

    fun listBlocking(containerId: String): List<MediaObject> = kotlinx.coroutines.runBlocking {
        list(containerId)
    }
    
    /**
     * Open the actual media data for streaming.
     */
    fun openMedia(mediaId: String): MediaDataSource
}