package com.example.mysecondapp.dlna_lib.api.media

import java.io.InputStream

/**
 * Interface implemented by the App to expose local content to the DLNA Library.
 */
interface MediaContentProvider {
    /**
     * Returns the list of items/folders inside a specific container.
     * ContainerId "0" is usually the root.
     */
    suspend fun list(containerId: ContainerId): List<MediaObject>

    /**
     * Provides a way to read the actual bytes of a media file.
     */
    fun openMedia(mediaId: MediaId): MediaDataSource
}

/**
 * Abstraction for reading file data, allowing for range-based (seeking) requests.
 */
interface MediaDataSource {
    val size: Long

    /** Returns a stream for the entire file. */
    fun openFull(): InputStream

    /** Returns a stream for a specific byte range (essential for seeking on a TV). */
    fun openRange(start: Long, length: Long?): InputStream
}