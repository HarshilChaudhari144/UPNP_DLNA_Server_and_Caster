package com.example.mysecondapp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Log
import com.example.mysecondapp.dlna_lib.api.media.MediaId
import com.example.mysecondapp.dlna_lib.api.media.MediaThumbnailProvider
import com.example.mysecondapp.dlna_lib.api.media.ThumbnailResult
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLConnection

class FileSystemThumbnailProvider : MediaThumbnailProvider {

    private val tag = "FileSystemThumbProvider"

    override fun openThumbnail(mediaId: MediaId, maxWidth: Int, maxHeight: Int): ThumbnailResult? {
        val file = File(mediaId)
        if (!file.exists() || !file.isFile) return null

        return try {
            val mimeType = URLConnection.guessContentTypeFromName(file.name) ?: ""
            val bitmap = when {
                mimeType.startsWith("video/") -> createVideoThumbnail(file, maxWidth, maxHeight)
                mimeType.startsWith("image/") -> createImageThumbnail(file, maxWidth, maxHeight)
                else -> null
            }

            bitmap?.let {
                val bos = ByteArrayOutputStream()
                // Samsung TVs strictly prefer JPEG for thumbnails
                it.compress(Bitmap.CompressFormat.JPEG, 85, bos)
                val bytes = bos.toByteArray()
                it.recycle() // Cleanup bitmap memory

                ThumbnailResult(
                    mimeType = "image/jpeg",
                    inputStream = ByteArrayInputStream(bytes),
                    size = bytes.size.toLong()
                )
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to generate thumbnail for $mediaId", e)
            null
        }
    }

    private fun createVideoThumbnail(file: File, width: Int, height: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            // Grab a frame at 1 second to avoid potential black frames at start
            val rawFrame = retriever.getFrameAtTime(5000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            rawFrame?.let {
                val scaled = Bitmap.createScaledBitmap(it, width, height, true)
                if (scaled != it) it.recycle()
                scaled
            }
        } finally {
            retriever.release()
        }
    }

    private fun createImageThumbnail(file: File, width: Int, height: Int): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(file.absolutePath, options)

        // Calculate inSampleSize to load a smaller version into memory
        options.inSampleSize = calculateInSampleSize(options, width, height)
        options.inJustDecodeBounds = false

        val decoded = BitmapFactory.decodeFile(file.absolutePath, options)
        return decoded?.let {
            val scaled = Bitmap.createScaledBitmap(it, width, height, true)
            if (scaled != it) it.recycle()
            scaled
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}