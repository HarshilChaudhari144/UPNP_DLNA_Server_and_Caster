package com.example.mysecondapp.dlna_lib.core.mediaserver

import com.example.mysecondapp.dlna_lib.api.media.MediaContentProvider
import com.example.mysecondapp.dlna_lib.platform.MimeTypeResolver
import com.example.mysecondapp.dlna_lib.platform.HttpHandler
import com.example.mysecondapp.dlna_lib.platform.HttpMethod
import com.example.mysecondapp.dlna_lib.platform.HttpRequest
import com.example.mysecondapp.dlna_lib.platform.HttpResponse
import java.lang.Long.max
import java.lang.Long.min

internal class MediaHttpHandler(
    private val contentProvider: MediaContentProvider,
    private val mimeTypeResolver: MimeTypeResolver
) : HttpHandler {

    override suspend fun handle(request: HttpRequest): HttpResponse {
        return when (request.method) {
            HttpMethod.GET,
            HttpMethod.HEAD -> handleGetOrHead(request)

            else -> HttpResponse(
                statusCode = 405,
                headers = mapOf("Allow" to "GET, HEAD")
            )
        }
    }

    private fun resolveMimeType(mediaId: String): String {
        val extension = mediaId.substringAfterLast('.', missingDelimiterValue = "")
        return if (extension.isNotEmpty()) {
            mimeTypeResolver.getMimeType(extension)
        } else {
            "application/octet-stream"
        }
    }

    private fun handleGetOrHead(request: HttpRequest): HttpResponse {
        // Expected path: /media/{mediaId}
        val segments = request.path.trim('/').split("/")
        if (segments.size != 2 || segments[0] != "media") {
            return HttpResponse(statusCode = 404)
        }

        val mediaId = segments[1]
        val dataSource = try {
            contentProvider.openMedia(mediaId)
        } catch (t: Throwable) {
            return HttpResponse(statusCode = 404)
        }

        val totalSize = dataSource.size
        val rangeHeader = request.headers["Range"]
        val mimeType = resolveMimeType(mediaId)

        // Always advertise range support (DLNA expects this)
        val baseHeaders = mutableMapOf(
            "Accept-Ranges" to "bytes"
        )

        // HEAD behaves like GET but without a body
        val isHead = request.method == HttpMethod.HEAD

        if (rangeHeader == null) {
            // Full content
            return HttpResponse(
                statusCode = 200,
                mimeType = mimeType,
                headers = baseHeaders,
                inputStream = if (isHead) null else dataSource.openFull(),
                contentLength = totalSize
            )
        }

        // Parse Range: bytes=start-end
        val range = parseRange(rangeHeader, totalSize)
            ?: return HttpResponse(
                statusCode = 416,
                headers = mapOf(
                    "Content-Range" to "bytes */$totalSize"
                )
            )

        val (start, end) = range
        val length = end - start + 1

        baseHeaders["Content-Range"] = "bytes $start-$end/$totalSize"

        return HttpResponse(
            statusCode = 206,
            mimeType = mimeType,
            headers = baseHeaders,
            inputStream = if (isHead) null else dataSource.openRange(start, length),
            contentLength = length
        )
    }

    /**
     * Parses a HTTP Range header.
     * Supports:
     *  - bytes=start-end
     *  - bytes=start-
     */
    private fun parseRange(
        header: String,
        totalSize: Long
    ): Pair<Long, Long>? {
        if (!header.startsWith("bytes=")) return null

        val rangePart = header.removePrefix("bytes=")
        val parts = rangePart.split("-")
        if (parts.isEmpty()) return null

        val start = parts[0].toLongOrNull() ?: return null
        val end = if (parts.size > 1 && parts[1].isNotBlank()) {
            parts[1].toLongOrNull() ?: return null
        } else {
            totalSize - 1
        }

        if (start >= totalSize || start < 0) return null

        val clampedEnd = min(end, totalSize - 1)
        if (clampedEnd < start) return null

        return start to clampedEnd
    }
}
