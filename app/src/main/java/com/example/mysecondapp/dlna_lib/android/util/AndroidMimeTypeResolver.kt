package com.example.mysecondapp.dlna_lib.android.util

import android.webkit.MimeTypeMap
import com.example.mysecondapp.dlna_lib.platform.MimeTypeResolver
import com.example.mysecondapp.dlna_lib.platform.model.MimeInfo
import java.util.Locale

/**
 * Uses Android's MimeTypeMap and manual fallbacks for DLNA profiles.
 * Spec Reference: 6.6
 */
class AndroidMimeTypeResolver : MimeTypeResolver {
    override fun resolve(fileName: String): MimeInfo {
        val extension = MimeTypeMap.getFileExtensionFromUrl(fileName).lowercase(Locale.ROOT)
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
        
        // Basic DLNA Profile guessing (simplified for v2.1)
        val dlnaProfile = when (mime) {
            "video/mp4" -> "AVC_MP4_BL_CIF15_AAC_520"
            "audio/mpeg" -> "MP3"
            "image/jpeg" -> "JPEG_SM"
            else -> null
        }
        
        val flags = "01700000${"0".repeat(24)}" // Standard DLNA flags
        
        return MimeInfo(mime, dlnaProfile, flags)
    }
}