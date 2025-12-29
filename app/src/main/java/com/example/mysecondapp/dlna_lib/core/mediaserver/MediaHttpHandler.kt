package com.example.mysecondapp.dlna_lib.core.mediaserver

import com.example.mysecondapp.dlna_lib.api.media.MediaContentProvider
import com.example.mysecondapp.dlna_lib.api.media.MediaThumbnailProvider
import com.example.mysecondapp.dlna_lib.core.logging.DlnaLogger
import com.example.mysecondapp.dlna_lib.platform.HttpHandler
import com.example.mysecondapp.dlna_lib.platform.HttpMethod
import com.example.mysecondapp.dlna_lib.platform.HttpRequest
import com.example.mysecondapp.dlna_lib.platform.HttpResponse
import com.example.mysecondapp.dlna_lib.platform.MimeTypeResolver
import java.io.InputStream
import kotlin.math.min

internal class MediaHttpHandler(
    private val contentProvider: MediaContentProvider,
    private val thumbnailProvider: MediaThumbnailProvider?,
    private val mimeResolver: MimeTypeResolver
) : HttpHandler {

    private val tag = "MediaHttpHandler"

    // URL Patterns:
    // Content: /content/<mediaId>
    // Thumbnail: /thumb/<mediaId>

    override suspend fun handle(request: HttpRequest): HttpResponse {
        if (request.method != HttpMethod.GET && request.method != HttpMethod.HEAD) {
            return HttpResponse(405) // Method Not Allowed
        }

        val path = request.path
        return when {
            path.startsWith("/content/") -> handleContent(request, path.substringAfter("/content/"))
            path.startsWith("/thumb/") -> handleThumbnail(request, path.substringAfter("/thumb/"))
            else -> HttpResponse(404)
        }
    }

    private fun handleContent(request: HttpRequest, mediaIdRaw: String): HttpResponse {
        // Some clients append /filename.ext to the URL, strip it if necessary to get ID
        // Assuming simple ID for now, or decode URL if needed.
        val mediaId = mediaIdRaw.split("/").first()

        try {
            val dataSource = contentProvider.openMedia(mediaId)
            val totalSize = dataSource.size
            val rangeHeader = request.headers.entries
                .find { it.key.equals("Range", ignoreCase = true) }?.value

            // Resolve MimeType (Best effort: try file extension if ID has one, else guess)
            // Ideally, contentProvider could return mimeType, but for now we rely on Resolver or default.
            val mimeType = mimeResolver.getMimeType(mediaId) ?: "application/octet-stream"

            // 1. Full Content Request (No Range)
            if (rangeHeader == null) {
                val stream = if (request.method == HttpMethod.GET) dataSource.openFull() else null
                return HttpResponse(
                    statusCode = 200,
                    mimeType = mimeType,
                    headers = mapOf(
                        "Content-Length" to totalSize.toString(),
                        "Accept-Ranges" to "bytes",
                        "Content-Features.DLNA.ORG" to "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
                    ),
                    inputStream = stream,
                    contentLength = totalSize
                )
            }

            // 2. Range Request (Seeking)
            val range = parseRange(rangeHeader, totalSize)
            if (range == null) {
                return HttpResponse(416) // Range Not Satisfiable
            }

            val (start, end) = range
            val length = end - start + 1

            val stream = if (request.method == HttpMethod.GET) {
                dataSource.openRange(start, length)
            } else null

            return HttpResponse(
                statusCode = 206, // Partial Content
                mimeType = mimeType,
                headers = mapOf(
                    "Content-Range" to "bytes $start-$end/$totalSize",
                    "Content-Length" to length.toString(),
                    "Accept-Ranges" to "bytes",
                    // DLNA operations: 01 = Seek (Range) supported, 10 = Time seek supported
                    "Content-Features.DLNA.ORG" to "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000",
                    "transferMode.dlna.org" to "Streaming"
                ),
                inputStream = stream,
                contentLength = length
            )

        } catch (e: Exception) {
            DlnaLogger.e(tag, "Error serving media $mediaId: ${e.message}")
            return HttpResponse(404)
        }
    }

    private fun handleThumbnail(request: HttpRequest, mediaId: String): HttpResponse {
        if (thumbnailProvider == null) return HttpResponse(404)

        try {
            // Default sizes, could parse from query params if needed
            val result = thumbnailProvider.openThumbnail(mediaId, 320, 320)
                ?: return HttpResponse(404)

            val headers = mutableMapOf<String, String>()
            if (result.size != null) {
                headers["Content-Length"] = result.size.toString()
            }

            return HttpResponse(
                statusCode = 200,
                mimeType = result.mimeType,
                headers = headers,
                inputStream = result.inputStream,
                contentLength = result.size
            )

        } catch (e: Exception) {
            DlnaLogger.w(tag, "Error serving thumbnail $mediaId: ${e.message}")
            return HttpResponse(404)
        }
    }

    /**
     * Parses "bytes=0-499" or "bytes=500-"
     * Returns Pair(start, end) or null if invalid.
     */
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