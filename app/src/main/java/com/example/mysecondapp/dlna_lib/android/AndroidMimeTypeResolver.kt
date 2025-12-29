package com.example.mysecondapp.dlna_lib.android

import android.webkit.MimeTypeMap
import com.example.mysecondapp.dlna_lib.platform.MimeTypeResolver
import java.util.Locale

class AndroidMimeTypeResolver : MimeTypeResolver {

    override fun getMimeType(extension: String): String {
        // MimeTypeMap expects the extension without the dot
        val cleanExt = extension.substringAfterLast('.').lowercase(Locale.ROOT)

        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(cleanExt)

        return mime ?: "application/octet-stream" // Fallback
    }
}