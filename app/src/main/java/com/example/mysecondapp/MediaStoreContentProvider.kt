package com.example.mysecondapp

import android.content.ContentUris
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import com.example.mysecondapp.dlna_lib.api.media.*
import java.io.FileInputStream
import java.io.InputStream
import kotlin.time.Duration.Companion.milliseconds

class MediaStoreContentProvider(private val context: Context) : MediaContentProvider {

    private val allowedFolderIds = mutableSetOf<String>()

    fun setAllowedFolders(folderIds: Set<String>) {
        allowedFolderIds.clear()
        allowedFolderIds.addAll(folderIds)
    }

    fun getAllFolders(): List<MediaContainer> {
        return getFoldersInternal(filterAllowed = false)
            .filterIsInstance<MediaContainer>()
    }

    private val collectionUri: Uri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
        MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
    } else {
        MediaStore.Files.getContentUri("external")
    }

    override suspend fun getMetadata(mediaId: String): MediaObject? {
        // 1. Root
        if (mediaId == "0") {
            return MediaContainer("0", "-1", "Root", childCount = null, searchable = true)
        }

        // 2. Folder (Bucket)
        if (allowedFolderIds.contains(mediaId)) {
            // We need to fetch the name of the bucket
            val projection = arrayOf(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)
            val selection = "${MediaStore.Files.FileColumns.BUCKET_ID} = ?"
            val args = arrayOf(mediaId)

            try {
                context.contentResolver.query(collectionUri, projection, selection, args, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val name = c.getString(0) ?: "Folder"
                        return MediaContainer(mediaId, "0", name, searchable = false)
                    }
                }
            } catch (e: Exception) { }
        }

        // 3. File
        // If not a known folder, try to find it as a file
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DURATION,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.BUCKET_ID
        )
        val selection = "${MediaStore.Files.FileColumns._ID} = ?"

        try {
            context.contentResolver.query(collectionUri, projection, selection, arrayOf(mediaId), null)?.use { c ->
                if (c.moveToFirst()) {
                    val bucketId = c.getString(c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_ID))
                    // Only return metadata if the parent folder is allowed
                    if (allowedFolderIds.contains(bucketId)) {
                        return mapCursorToMediaItem(c, bucketId)
                    }
                }
            }
        } catch (e: Exception) { }

        return null
    }

    override suspend fun list(containerId: String): List<MediaObject> {
        return if (containerId == "0") {
            getFoldersInternal(filterAllowed = true)
        } else {
            if (allowedFolderIds.contains(containerId)) {
                getFiles(containerId)
            } else {
                emptyList()
            }
        }
    }

    private fun getFoldersInternal(filterAllowed: Boolean): List<MediaObject> {
        val folders = mutableMapOf<String, String>()
        val projection = arrayOf(
            MediaStore.Files.FileColumns.BUCKET_ID,
            MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME
        )
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?, ?)"
        val selectionArgs = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString()
        )

        try {
            context.contentResolver.query(collectionUri, projection, selection, selectionArgs, null)?.use { cursor ->
                val idCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.BUCKET_ID)
                val nameCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    if (idCol != -1 && nameCol != -1) {
                        val id = cursor.getString(idCol)
                        val name = cursor.getString(nameCol)
                        if (id != null && name != null) folders[id] = name
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }

        return folders.map { (id, name) ->
            MediaContainer(id = id, parentId = "0", title = name, searchable = false)
        }
            .filter { !filterAllowed || allowedFolderIds.contains(it.id) }
            .sortedBy { it.title }
    }

    private fun getFiles(bucketId: String): List<MediaObject> {
        val items = mutableListOf<MediaItem>()
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DURATION,
            MediaStore.Files.FileColumns.MEDIA_TYPE
        )
        val selection = "${MediaStore.Files.FileColumns.BUCKET_ID} = ? AND ${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?, ?)"
        val selectionArgs = arrayOf(bucketId, MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(), MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO.toString(), MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString())

        try {
            context.contentResolver.query(collectionUri, projection, selection, selectionArgs, "${MediaStore.Files.FileColumns.DISPLAY_NAME} ASC")?.use { cursor ->
                while (cursor.moveToNext()) {
                    items.add(mapCursorToMediaItem(cursor, bucketId))
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return items
    }

    private fun mapCursorToMediaItem(cursor: Cursor, parentId: String): MediaItem {
        val id = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID))
        val title = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)) ?: "Unknown"
        val mimeType = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)) ?: "application/octet-stream"
        val size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE))
        val durationIndex = cursor.getColumnIndex(MediaStore.Files.FileColumns.DURATION)
        val duration = if (durationIndex != -1 && !cursor.isNull(durationIndex)) cursor.getLong(durationIndex).milliseconds else null
        val mediaTypeInt = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE))

        val (mediaType, upnpClass) = when (mediaTypeInt) {
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> MediaType.IMAGE to "object.item.imageItem"
            MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO -> MediaType.AUDIO to "object.item.audioItem"
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> MediaType.VIDEO to "object.item.videoItem"
            else -> MediaType.UNKNOWN to "object.item"
        }

        val protocolInfo = "http-get:*:$mimeType:*"
        val resource = MediaResource(
            uri = "",
            protocolInfo = protocolInfo,
            mimeType = mimeType,
            size = size,
            duration = duration
        )

        return MediaItem(id = id, parentId = parentId, title = title, upnpClass = upnpClass, mediaType = mediaType, resources = listOf(resource))
    }

    override fun openMedia(mediaId: String): MediaDataSource {
        val idLong = mediaId.toLongOrNull() ?: throw IllegalArgumentException("Invalid Media ID")
        val contentUri = ContentUris.withAppendedId(collectionUri, idLong)

        // FIX: Get accurate size/mime from content resolver
        var mime = context.contentResolver.getType(contentUri) ?: "application/octet-stream"

        // Use AssetFileDescriptor to get EXACT size
        var size = 0L
        try {
            val afd = context.contentResolver.openAssetFileDescriptor(contentUri, "r")
            size = afd?.length ?: 0L
            afd?.close()
        } catch (e: Exception) {
            // Fallback to basic query if AFD fails
            e.printStackTrace()
        }

        return MediaStoreDataSource(context, contentUri, size, mime)
    }

    private class MediaStoreDataSource(
        private val context: Context,
        private val uri: Uri,
        override val size: Long,
        override val contentType: String
    ) : MediaDataSource {

        override fun openFull(): InputStream {
            // Use openRange(0, null) to reuse the AFD logic
            return openRange(0, null)
        }

        override fun openRange(start: Long, length: Long?): InputStream {
            try {
                // FIX: Use AssetFileDescriptor for robust seeking
                val afd: AssetFileDescriptor = context.contentResolver.openAssetFileDescriptor(uri, "r")
                    ?: throw java.io.IOException("Cannot open AFD for $uri")

                val fis = afd.createInputStream() // Auto-closes AFD when this stream closes

                if (start > 0) {
                    fis.skip(start)
                }

                return fis
            } catch (e: Exception) {
                throw java.io.IOException(e)
            }
        }
    }
}