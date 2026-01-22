package com.example.mysecondapp.dlna_lib.core.mediaserver

import com.example.mysecondapp.dlna_lib.api.media.MediaContentProvider
import com.example.mysecondapp.dlna_lib.api.media.MediaThumbnailProvider
import com.example.mysecondapp.dlna_lib.core.logging.DlnaLogger
import com.example.mysecondapp.dlna_lib.platform.HttpHandler
import com.example.mysecondapp.dlna_lib.platform.HttpMethod
import com.example.mysecondapp.dlna_lib.platform.HttpRequest
import com.example.mysecondapp.dlna_lib.platform.HttpResponse
import java.io.File
import java.net.URLDecoder

internal class MediaHttpHandler(
    private val contentProvider: MediaContentProvider,
    private val thumbnailProvider: MediaThumbnailProvider?
) : HttpHandler {

    private val tag = "MediaHttpHandler"
    // Separate flags for different content types
    private val videoFlags = "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
    private val thumbFlags = "DLNA.ORG_PN=JPEG_TN"

    override suspend fun handle(request: HttpRequest): HttpResponse {
        if (request.method != HttpMethod.GET && request.method != HttpMethod.HEAD) {
            return HttpResponse(405)
        }

        // We need to decode the path here because it may contain URL-encoded segments like %20 for spaces.
        val path = URLDecoder.decode(request.path, "UTF-8")
        return when {
            path.startsWith("/content/") -> handleContent(request, path.substringAfter("/content/"))
            path.startsWith("/thumb/") -> handleThumbnail(request, path.substringAfter("/thumb/"))
            else -> HttpResponse(404)
        }
    }

    private fun handleContent(request: HttpRequest, pathRaw: String): HttpResponse {
        // --- FIX: Robustly parse the file path from the raw URL path. ---
        // 1. Reconstruct the potential absolute path. We prepend "/" because our
        //    FileSystemContentProvider removed it before encoding.
        val potentialPath = "/$pathRaw"

        // 2. Find the actual file path by stripping potential suffixes added by renderers.
        //    For example, if the path is "/path/to/file.mkv/title", this loop will find "/path/to/file.mkv".
        var file = File(potentialPath)
        while (file.path != "/" && !file.isFile) {
            file = file.parentFile ?: break // Go up one level
        }

        // 3. Check if we found a valid file.
        if (!file.isFile) {
            DlnaLogger.e(tag, "Error serving media : File not found or is not a regular file: $potentialPath")
            return HttpResponse(404, body = "File not found: $potentialPath")
        }
        val mediaId = file.absolutePath
        // --- End of FIX ---

        try {
            val dataSource = contentProvider.openMedia(mediaId)
            val totalSize = dataSource.size
            val mimeType = dataSource.contentType
            val rangeHeader = request.headers.entries.find { it.key.equals("Range", ignoreCase = true) }?.value

            // 1. Check for Range Request
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                val range = parseRange(rangeHeader, totalSize)
                if (range != null) {
                    val (start, end) = range
                    val length = end - start + 1

                    return HttpResponse(
                        statusCode = 206, // Partial Content
                        mimeType = mimeType,
                        headers = mapOf(
                            "Content-Range" to "bytes $start-$end/$totalSize",
                            "Content-Length" to length.toString(),
                            "Accept-Ranges" to "bytes",
                            "contentFeatures.dlna.org" to videoFlags,
                            "transferMode.dlna.org" to "Streaming"
                        ),
                        inputStream = dataSource.openRange(start, length),
                        contentLength = length
                    )
                }
            }

            // 2. Full Content / Fallback
            return HttpResponse(
                statusCode = 200,
                mimeType = mimeType,
                headers = mapOf(
                    "Content-Length" to totalSize.toString(),
                    "Accept-Ranges" to "bytes",
                    "contentFeatures.dlna.org" to videoFlags,
                    "transferMode.dlna.org" to "Streaming"
                ),
                inputStream = dataSource.openFull(),
                contentLength = totalSize
            )

        } catch (e: Exception) {
            DlnaLogger.e(tag, "Error serving media $mediaId: ${e.message}")
            return HttpResponse(404)
        }
    }

    private fun handleThumbnail(request: HttpRequest, pathRaw: String): HttpResponse {
        if (thumbnailProvider == null) return HttpResponse(404)

        // FIX: Strip the dummy .jpg extension if present so the file resolution
        // logic can find the actual media file (e.g., .mp4 or .mkv)
        val cleanPathRaw = if (pathRaw.endsWith(".jpg", ignoreCase = true)) {
            pathRaw.substring(0, pathRaw.length - 4)
        } else {
            pathRaw
        }

        val potentialPath = "/$cleanPathRaw"
        var file = File(potentialPath)

        // Now this loop will correctly find the file
        while (file.path != "/" && !file.isFile) {
            file = file.parentFile ?: break
        }

        if (!file.isFile) {
            DlnaLogger.e(tag, "Thumbnail lookup failed: $potentialPath")
            return HttpResponse(404)
        }
        val mediaId = file.absolutePath

        try {
            // 2. Request 320x320 thumbnail (standard for Samsung grid views)
            val result = thumbnailProvider.openThumbnail(mediaId, 320, 320)
                ?: return HttpResponse(404)

            // Thumbnails are ALWAYS "Interactive"
            val headers = mutableMapOf(
                "Content-Length" to result.size.toString(),
                "contentFeatures.dlna.org" to thumbFlags,
                "transferMode.dlna.org" to "Interactive",
                "realTimeInfo.dlna.org" to "DLNA.ORG_TLAG=*"
            )

            return HttpResponse(
                statusCode = 200,
                mimeType = result.mimeType, // Will be "image/jpeg" from our Step 1 implementation
                headers = headers,
                inputStream = result.inputStream,
                contentLength = result.size
            )

        } catch (e: Exception) {
            DlnaLogger.w(tag, "Error serving thumbnail $mediaId: ${e.message}")
            return HttpResponse(404)
        }
    }

    private fun parseRange(header: String, totalSize: Long): Pair<Long, Long>? {
        try {
            val prefix = "bytes="
            if (!header.startsWith(prefix)) return null

            val values = header.removePrefix(prefix).split("-")
            var start = values[0].toLongOrNull() ?: 0L
            var end = values.getOrNull(1)?.toLongOrNull() ?: (totalSize - 1)

            if (start >= totalSize) return null
            if (end >= totalSize) end = totalSize - 1
            if (start > end) return null

            return start to end
        } catch (e: Exception) {
            return null
        }
    }
}