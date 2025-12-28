package com.example.mysecondapp.dlna_lib.platform

/**
 * Bridge to access the platform's MIME type database.
 */
interface MimeTypeResolver {
    fun getMimeType(extension: String): String
}