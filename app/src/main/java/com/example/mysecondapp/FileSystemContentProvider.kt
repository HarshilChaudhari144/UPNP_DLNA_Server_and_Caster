package com.example.mysecondapp

import com.example.mysecondapp.dlna_lib.api.media.*
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URLConnection
import java.net.URLEncoder

/**
 * A content provider that serves media files directly from the filesystem.
 * It recursively lists media files from a given set of root folders.
 */
class FileSystemContentProvider(
    private val rootFolders: Set<File>
) : MediaContentProvider {

    // A simple list of common media file extensions for filtering.
    private val supportedMediaExtensions = setOf(
        // Video
        "mp4", "mkv", "webm", "avi", "mov", "flv", "wmv", "3gp", "mpeg", "mpg",
        // Audio
        "mp3", "aac", "flac", "ogg", "wav", "m4a",
        // Image
        "jpg", "jpeg", "png", "gif", "bmp", "webp"
    )

    override suspend fun list(containerId: String): List<MediaObject> {
        // If containerId is "0", list the configured root folders.
        if (containerId == "0") {
            return rootFolders.filter { it.exists() && it.isDirectory }.map { file ->
                MediaContainer(
                    id = file.absolutePath,
                    parentId = "0",
                    title = file.name,
                    childCount = file.listFiles()?.size ?: 0, // Approximate child count
                    searchable = true
                )
            }
        }

        // Otherwise, list the contents of the folder path specified by containerId.
        val folder = File(containerId)
        if (!folder.exists() || !folder.isDirectory) {
            return emptyList()
        }

        val children = folder.listFiles() ?: return emptyList()
        val mediaObjects = mutableListOf<MediaObject>()

        for (child in children) {
            if (child.isDirectory) {
                mediaObjects.add(
                    MediaContainer(
                        id = child.absolutePath,
                        parentId = containerId,
                        title = child.name,
                        childCount = child.listFiles()?.size ?: 0,
                        searchable = true
                    )
                )
            } else {
                val extension = child.extension.lowercase()
                if (supportedMediaExtensions.contains(extension)) {
                    // It's a supported media file, create a MediaItem.
                    mapFileToMediaItem(child)?.let { mediaObjects.add(it) }
                }
            }
        }
        // Sort folders first, then files, all alphabetically.
        return mediaObjects.sortedWith(compareBy({ it !is MediaContainer }, { it.title }))
    }

    override suspend fun getMetadata(mediaId: String): MediaObject? {
        if (mediaId == "0") {
            return MediaContainer("0", "-1", "Root", childCount = rootFolders.size, searchable = true)
        }
        val file = File(mediaId)
        if (!file.exists()) {
            return null
        }
        return if (file.isDirectory) {
            MediaContainer(
                id = file.absolutePath,
                parentId = file.parent ?: "0",
                title = file.name,
                childCount = file.listFiles()?.size ?: 0,
                searchable = true
            )
        } else {
            mapFileToMediaItem(file)
        }
    }

    override fun openMedia(mediaId: String): MediaDataSource {
        val file = File(mediaId)
        if (!file.exists() || !file.isFile) {
            throw IOException("File not found or is not a regular file: $mediaId")
        }
        return FileSystemDataSource(file)
    }

    private fun mapFileToMediaItem(file: File): MediaItem? {
        val id = file.absolutePath
        val parentId = file.parent ?: "0"
        val title = file.nameWithoutExtension
        val mimeType = URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream"
        val size = file.length()
        val date = file.lastModified()

        val (mediaType, upnpClass) = when {
            mimeType.startsWith("video/") -> MediaType.VIDEO to "object.item.videoItem"
            mimeType.startsWith("audio/") -> MediaType.AUDIO to "object.item.audioItem"
            mimeType.startsWith("image/") -> MediaType.IMAGE to "object.item.imageItem"
            else -> return null // Ignore files with unknown primary media types
        }

        // --- FIX: Remove the leading slash from the path before encoding it. ---
        // This prevents the double slash issue (//) in the final URL.
        // For example, "/storage/emulated/0/file.mkv" becomes "storage/emulated/0/file.mkv" before encoding.
        val safeId = if (id.startsWith("/")) id.substring(1) else id
        val resourceUri = "/content/${URLEncoder.encode(safeId, "UTF-8")}"

        val resource = MediaResource(
            uri = resourceUri,
            protocolInfo = "http-get:*:$mimeType:*",
            mimeType = mimeType,
            size = size,
            duration = null, // Note: Reading duration/resolution from a File is complex
            resolution = null  // and is omitted for simplicity.
        )

        return MediaItem(
            id = id,
            parentId = parentId,
            title = title,
            upnpClass = upnpClass,
            mediaType = mediaType,
            resources = listOf(resource),
            date = date
        )
    }

    private class FileSystemDataSource(private val file: File) : MediaDataSource {
        override val size: Long by lazy { file.length() }
        override val contentType: String by lazy {
            URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream"
        }

        override fun openFull(): InputStream {
            return file.inputStream()
        }

        override fun openRange(start: Long, length: Long?): InputStream {
            val fis = FileInputStream(file)
            if (start > 0) {
                val skipped = fis.skip(start)
                if (skipped != start) {
                    fis.close()
                    throw IOException("Failed to skip to the requested start position.")
                }
            }
            return fis
        }
    }
}