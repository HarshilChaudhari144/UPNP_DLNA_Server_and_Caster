package com.example.mysecondapp.dlna_lib.contract

import java.io.InputStream

/**
 * A seek-capable data source for media content provided by the app.
 * Spec Reference: 8
 */
interface MediaDataSource {
    val size: Long
    
    /**
     * Open a stream for the entire file.
     */
    fun openFull(): InputStream
    
    /**
     * Open a stream for a specific byte range.
     * Used for HTTP Range requests (seeking).
     */
    fun openRange(start: Long, length: Long?): InputStream
}