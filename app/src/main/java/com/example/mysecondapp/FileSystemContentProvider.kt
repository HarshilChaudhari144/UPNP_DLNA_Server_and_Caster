package com.example.mysecondapp

import android.util.Log
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

    // Supported subtitle extensions for sidecar detection.
    private val subtitleExtensions = setOf("srt", "vtt", "smi")

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
        val mimeType = getMimeType(file)
        val size = file.length()
        val date = file.lastModified()

        val (mediaType, upnpClass) = when {
            mimeType.startsWith("video/") -> MediaType.VIDEO to "object.item.videoItem"
            mimeType.startsWith("audio/") -> MediaType.AUDIO to "object.item.audioItem"
            mimeType.startsWith("image/") -> MediaType.IMAGE to "object.item.imageItem"
            else -> return null // Ignore files with unknown primary media types
        }

        val resources = mutableListOf<MediaResource>()

        // 1. Add the primary media resource
        resources.add(createResourceForFile(file, mimeType))

        // 2. Automatically detect and add sidecar subtitles for videos
        if (mediaType == MediaType.VIDEO) {
            val parentDir = file.parentFile
            if (parentDir != null) {
                subtitleExtensions.forEach { ext ->
                    val subtitleFile = File(parentDir, "${file.nameWithoutExtension}.$ext")
                    if (subtitleFile.exists() && subtitleFile.isFile) {
                        val subMime = getMimeType(subtitleFile)
                        resources.add(createResourceForFile(subtitleFile, subMime))
                    }
                }
            }
        }
        Log.d("FileSystemContentProvider", "Resources:\n$resources")

        return MediaItem(
            id = id,
            parentId = parentId,
            title = title,
            upnpClass = upnpClass,
            mediaType = mediaType,
            resources = resources,
            thumbnail = null,
            date = date
        )
    }

    private fun createResourceForFile(file: File, mimeType: String): MediaResource {
        val id = file.absolutePath
        // Remove the leading slash from the path before encoding it for the URI.
        val safeId = if (id.startsWith("/")) id.substring(1) else id
        val resourceUri = "/content/${URLEncoder.encode(safeId, "UTF-8")}"

        return MediaResource(
            uri = resourceUri,
            protocolInfo = "http-get:*:$mimeType:*",
            mimeType = mimeType,
            size = file.length(),
            duration = null,
            resolution = null
        )
    }

    private fun getMimeType(file: File): String {
        val extension = file.extension.lowercase()
        return when (extension) {
            "srt" -> "text/srt"
            "vtt" -> "text/vtt"
            "smi" -> "application/x-sami"
            else -> URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream"
        }
    }

    private class FileSystemDataSource(private val file: File) : MediaDataSource {
        override val size: Long by lazy { file.length() }
        override val contentType: String by lazy {
            val extension = file.extension.lowercase()
            when (extension) {
                "srt" -> "text/srt"
                "vtt" -> "text/vtt"
                "smi" -> "application/x-sami"
                else -> URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream"
            }
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