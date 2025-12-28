package com.example.mysecondapp.dlna_lib.android

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import com.example.mysecondapp.dlna_lib.contract.MediaContentProvider
import com.example.mysecondapp.dlna_lib.contract.MediaDataSource
import com.example.mysecondapp.dlna_lib.core.models.MediaContainer
import com.example.mysecondapp.dlna_lib.core.models.MediaItem
import com.example.mysecondapp.dlna_lib.core.models.MediaObject
import com.example.mysecondapp.dlna_lib.core.models.MediaType
import kotlinx.coroutines.runBlocking
import java.io.InputStream
import kotlin.time.Duration.Companion.milliseconds

class AndroidLocalContentProvider(private val context: Context, private val targetFolderName: String = "Movies") : MediaContentProvider {

    private val resolver = context.contentResolver

//    override suspend fun list(containerId: String): List<MediaObject> {
//        return when (containerId) {
//            "0" -> listOf(
//                MediaContainer(
//                    id = "video-root",
//                    parentId = "0",
//                    title = "All Videos",
//                    childCount = 0,
//                    searchable = true
//                )
//            )
//            "video-root" -> queryVideos()
//            else -> emptyList()
//        }
//    }
    override suspend fun list(containerId: String): List<MediaObject> {
        return when (containerId) {
            "0" -> {
                val children = queryVideosFromFolder(targetFolderName)
                listOf(
                    MediaContainer(
                        id = "video-root",
                        parentId = "0",
                        title = "Folder: $targetFolderName",
                        childCount = children.size, // <-- dynamic
                        searchable = true
                    )
                )
            }
            "video-root" -> queryVideosFromFolder(targetFolderName)
            else -> emptyList()
        }
    }
    override fun listBlocking(containerId: String): List<MediaObject> = runBlocking {
        list(containerId)
    }

    private fun queryVideosFromFolder(folderName: String): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.TITLE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME // The name of the folder
        )

        // FILTER: Only select items where the bucket name matches our target
        val selection = "${MediaStore.Video.Media.BUCKET_DISPLAY_NAME} = ?"
        val selectionArgs = arrayOf(folderName)

        val cursor: Cursor? = resolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${MediaStore.Video.Media.DATE_ADDED} DESC"
        )

        cursor?.use {
            val idCol = it.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val titleCol = it.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE)
            val durCol = it.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeCol = it.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)

            while (it.moveToNext()) {
                val id = it.getLong(idCol)
                val title = it.getString(titleCol) ?: "Unknown"
                val durationMs = it.getLong(durCol)
                val size = it.getLong(sizeCol)

                items.add(
                    MediaItem(
                        id = id.toString(),
                        parentId = "video-root",
                        title = title,
                        upnpClass = "object.item.videoItem",
                        resources = emptyList(),
                        thumbnail = null,
                        duration = durationMs.milliseconds,
                        mediaType = MediaType.VIDEO,
                        size = size
                    )
                )
            }
        }
        Log.d("AndrodiLocalContentProvider", "Querying videos in folder: $folderName")
        Log.d("AndrodiLocalContentProvider", "Found ${items.size} items")

        return items
    }

    override fun openMedia(mediaId: String): MediaDataSource {
        val id = mediaId.toLongOrNull() ?: throw IllegalArgumentException("Invalid Media ID: $mediaId")
        val contentUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
        return AndroidMediaDataSource(context, contentUri)
    }

    private fun queryVideos(): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.TITLE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.MIME_TYPE
        )
        val cursor: Cursor? = resolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Video.Media.DATE_ADDED} DESC"
        )
        cursor?.use {
            val idCol = it.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val titleCol = it.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE)
            val durCol = it.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeCol = it.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val mimeCol = it.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)

            while (it.moveToNext()) {
                val id = it.getLong(idCol)
                val title = it.getString(titleCol) ?: "Unknown"
                val durationMs = it.getLong(durCol)
                val size = it.getLong(sizeCol)
                items.add(
                    MediaItem(
                        id = id.toString(),
                        parentId = "video-root",
                        title = title,
                        upnpClass = "object.item.videoItem",
                        resources = emptyList(),
                        thumbnail = null,
                        duration = durationMs.milliseconds,
                        mediaType = MediaType.VIDEO,
                        size = size
                    )
                )
            }
        }
        return items
    }

    private class AndroidMediaDataSource(
        private val context: Context,
        private val uri: Uri
    ) : MediaDataSource {

        override val size: Long by lazy {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
        }

        override fun openFull(): InputStream {
            return context.contentResolver.openInputStream(uri)
                ?: throw java.io.IOException("Could not open stream for $uri")
        }

        override fun openRange(start: Long, length: Long?): InputStream {
            // FIX: Use ParcelFileDescriptor.AutoCloseInputStream
            // This ensures that when the stream is closed by NanoHTTPD, the underlying FD is also closed.
            val fileDescriptor = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw java.io.IOException("Could not open FD for $uri")

            val inputStream = ParcelFileDescriptor.AutoCloseInputStream(fileDescriptor)

            if (start > 0) {
                // Skip safely
                var remaining = start
                while (remaining > 0) {
                    val skipped = inputStream.skip(remaining)
                    if (skipped <= 0) break // End of file or error
                    remaining -= skipped
                }
            }
            return inputStream
        }
    }
}