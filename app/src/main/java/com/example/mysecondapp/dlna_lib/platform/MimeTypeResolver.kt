package com.example.mysecondapp.dlna_lib.platform

import com.example.mysecondapp.dlna_lib.platform.model.MimeInfo

/**
 * Maps filenames/extensions to MIME types and DLNA profiles.
 * Spec Reference: 6.6
 */
interface MimeTypeResolver {
    fun resolve(fileName: String): MimeInfo
}