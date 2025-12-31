package com.example.mysecondapp.dlna_lib.core.mediaserver

import com.example.mysecondapp.dlna_lib.api.media.MediaContentProvider
import com.example.mysecondapp.dlna_lib.api.media.MediaThumbnailProvider
import com.example.mysecondapp.dlna_lib.core.logging.DlnaLogger
import com.example.mysecondapp.dlna_lib.platform.HttpHandler
import com.example.mysecondapp.dlna_lib.platform.HttpMethod
import com.example.mysecondapp.dlna_lib.platform.HttpRequest
import com.example.mysecondapp.dlna_lib.platform.HttpResponse

internal class MediaHttpHandler(
    private val contentProvider: MediaContentProvider,
    private val thumbnailProvider: MediaThumbnailProvider?
) : HttpHandler {

    private val tag = "MediaHttpHandler"

    // Matches Reference: OP=01 (Byte Seek)
    private val dlnaFlags = "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"

    override suspend fun handle(request: HttpRequest): HttpResponse {
        if (request.method != HttpMethod.GET && request.method != HttpMethod.HEAD) {
            return HttpResponse(405)
        }

        val path = request.path
        return when {
            path.startsWith("/content/") -> handleContent(request, path.substringAfter("/content/"))
            path.startsWith("/thumb/") -> handleThumbnail(request, path.substringAfter("/thumb/"))
            else -> HttpResponse(404)
        }
    }

    private fun handleContent(request: HttpRequest, pathRaw: String): HttpResponse {
        val mediaId = pathRaw.split("/").first()

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
                            "contentFeatures.dlna.org" to dlnaFlags,
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
                    "contentFeatures.dlna.org" to dlnaFlags,
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

    private fun handleThumbnail(request: HttpRequest, mediaId: String): HttpResponse {
        if (thumbnailProvider == null) return HttpResponse(404)

        try {
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