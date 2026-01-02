package com.example.mysecondapp

import android.content.ContentUris
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import com.example.mysecondapp.dlna_lib.api.media.*
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.time.Duration.Companion.milliseconds

/**
 * A hybrid content provider that uses folder paths to filter the MediaStore.
 * The user selects folders via SAF, which are converted to paths for this provider to use.
 */
class MediaStoreContentProvider(private val context: Context) : MediaContentProvider {

    private val allowedFolderPaths = mutableSetOf<String>()

    // This now accepts a set of file paths, not bucket IDs.
    fun setAllowedFolders(folderPaths: Set<String>) {
        allowedFolderPaths.clear()
        allowedFolderPaths.addAll(folderPaths)
    }

    // This now simply converts the saved paths into MediaContainer objects for the UI.
    fun getAllFolders(): List<MediaContainer> {
        return allowedFolderPaths.map { path ->
            MediaContainer(
                id = path, // The ID is the path itself
                parentId = "0",
                title = path.substringAfterLast('/')
            )
        }
    }

    private val collectionUri: Uri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
        MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
    } else {
        MediaStore.Files.getContentUri("external")
    }

    override suspend fun getMetadata(mediaId: String): MediaObject? {
        // 1. Root container
        if (mediaId == "0") {
            return MediaContainer("0", "-1", "Root", childCount = allowedFolderPaths.size, searchable = true)
        }

        // 2. A Folder container (its ID is its path)
        if (allowedFolderPaths.contains(mediaId)) {
            return MediaContainer(mediaId, "0", mediaId.substringAfterLast('/'), searchable = true)
        }

        // 3. A File (its ID is its MediaStore _ID)
        val projection = arrayOf(
            MediaStore.Files.FileColumns.DATA, // Need the path to verify it's in an allowed folder
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DURATION,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.DATE_ADDED,
            MediaStore.Files.FileColumns.WIDTH,
            MediaStore.Files.FileColumns.HEIGHT
        )
        val selection = "${MediaStore.Files.FileColumns._ID} = ?"
        try {
            context.contentResolver.query(collectionUri, projection, selection, arrayOf(mediaId), null)?.use { c ->
                if (c.moveToFirst()) {
                    val path = c.getString(c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA))
                    val parentPath = File(path).parent
                    // Check if the file's parent path is one of the allowed folders
                    if (parentPath != null && allowedFolderPaths.any { path.startsWith(it) }) {
                        return mapCursorToMediaItem(c, parentPath)
                    }
                }
            }
        } catch (e: Exception) { /* Ignore */ }
        return null
    }

    override suspend fun list(containerId: String): List<MediaObject> {
        return if (containerId == "0") {
            // Return the list of top-level shared folders
            getAllFolders()
        } else {
            // Assume the containerId is a folder path and list files inside it
            getFilesForPath(containerId)
        }
    }

    private fun getFilesForPath(folderPath: String): List<MediaObject> {
        val items = mutableListOf<MediaItem>()
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DURATION,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.DATE_ADDED,
            MediaStore.Files.FileColumns.WIDTH,
            MediaStore.Files.FileColumns.HEIGHT,
            MediaStore.Files.FileColumns.DATA // Important for filtering
        )
        // CRITICAL CHANGE: Query by path, not bucket ID
        val selection = "${MediaStore.Files.FileColumns.DATA} LIKE ? AND ${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?, ?)"
        val selectionArgs = arrayOf("$folderPath/%", MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(), MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO.toString(), MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString())

        try {
            context.contentResolver.query(collectionUri, projection, selection, selectionArgs, "${MediaStore.Files.FileColumns.DISPLAY_NAME} ASC")?.use { cursor ->
                while (cursor.moveToNext()) {
                    // Additional check to prevent files from sub-sub-folders appearing
                    val filePath = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA))
                    if (File(filePath).parent == folderPath) {
                        items.add(mapCursorToMediaItem(cursor, folderPath))
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return items
    }

    private fun mapCursorToMediaItem(cursor: Cursor, parentId: String): MediaItem {
        val id = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID))
        val rawTitle = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)) ?: "Unknown"
        val title = rawTitle.substringBeforeLast('.')
        val mimeType = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)) ?: "application/octet-stream"
        val size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE))
        val duration = getLongOrNull(cursor, MediaStore.Files.FileColumns.DURATION)?.milliseconds
        val date = getLongOrNull(cursor, MediaStore.Files.FileColumns.DATE_ADDED)?.let { it * 1000L }
        val width = getIntOrNull(cursor, MediaStore.Files.FileColumns.WIDTH)
        val height = getIntOrNull(cursor, MediaStore.Files.FileColumns.HEIGHT)
        val resolution = if (width != null && height != null && width > 0) "${width}x${height}" else null

        val mediaTypeInt = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE))
        val (mediaType, upnpClass) = when (mediaTypeInt) {
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> MediaType.IMAGE to "object.item.imageItem"
            MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO -> MediaType.AUDIO to "object.item.audioItem"
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> MediaType.VIDEO to "object.item.videoItem"
            else -> MediaType.UNKNOWN to "object.item"
        }

        // CRITICAL FIX: The resource URI must be a relative path using the MediaStore ID
        val resourceUri = "/content/$id"

        val resource = MediaResource(
            uri = resourceUri,
            protocolInfo = "http-get:*:$mimeType:*",
            mimeType = mimeType,
            size = size,
            duration = duration,
            resolution = resolution
        )

        return MediaItem(id = id, parentId = parentId, title = title, upnpClass = upnpClass, mediaType = mediaType, resources = listOf(resource), date = date)
    }

    // Helper functions to safely get values from cursor
    private fun getLongOrNull(cursor: Cursor, columnName: String): Long? {
        val index = cursor.getColumnIndex(columnName)
        return if (index != -1 && !cursor.isNull(index)) cursor.getLong(index) else null
    }

    private fun getIntOrNull(cursor: Cursor, columnName: String): Int? {
        val index = cursor.getColumnIndex(columnName)
        return if (index != -1 && !cursor.isNull(index)) cursor.getInt(index) else null
    }

    override fun openMedia(mediaId: String): MediaDataSource {
        val idLong = mediaId.toLongOrNull() ?: throw IllegalArgumentException("Invalid Media ID")
        val contentUri = ContentUris.withAppendedId(collectionUri, idLong)
        val size = context.contentResolver.openAssetFileDescriptor(contentUri, "r")?.use { it.length } ?: 0
        val mime = context.contentResolver.getType(contentUri) ?: "application/octet-stream"
        return MediaStoreDataSource(context, contentUri, size, mime)
    }

    private class MediaStoreDataSource(
        private val context: Context,
        private val uri: Uri,
        override val size: Long,
        override val contentType: String
    ) : MediaDataSource {
        override fun openFull(): InputStream {
            return context.contentResolver.openInputStream(uri) ?: throw IOException("Could not open stream for $uri")
        }

        override fun openRange(start: Long, length: Long?): InputStream {
            val afd = context.contentResolver.openAssetFileDescriptor(uri, "r")
                ?: throw IOException("Cannot open AFD for $uri")
            val fis = afd.createInputStream()
            if (start > 0) {
                fis.skip(start)
            }
            return fis
        }
    }
}