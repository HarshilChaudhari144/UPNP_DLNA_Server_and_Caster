package com.example.mysecondapp.dlna_lib.android

import com.example.mysecondapp.dlna_lib.platform.EventCallbackServer
import com.example.mysecondapp.dlna_lib.platform.HttpHandler
import com.example.mysecondapp.dlna_lib.platform.HttpMethod
import com.example.mysecondapp.dlna_lib.platform.HttpRequest
import com.example.mysecondapp.dlna_lib.platform.HttpResponse
import com.example.mysecondapp.dlna_lib.platform.NetworkInfoProvider
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

class AndroidEventCallbackServer(
    private val networkInfoProvider: NetworkInfoProvider
) : EventCallbackServer {

    private var serverSocket: ServerSocket? = null
    private var listeningPort = 0
    private var handler: HttpHandler? = null
    private val executor = Executors.newCachedThreadPool()
    @Volatile private var isRunning = false

    override fun start(handler: HttpHandler) {
        if (isRunning) return
        this.handler = handler

        Thread {
            try {
                serverSocket = ServerSocket(0) // Random port
                listeningPort = serverSocket?.localPort ?: 0
                isRunning = true

                while (isRunning) {
                    val socket = serverSocket?.accept() ?: break
                    executor.execute { handleConnection(socket) }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    override fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) { /* ignore */ }
        serverSocket = null
        executor.shutdown()
    }

    override fun getCallbackUrl(): String {
        val ip = networkInfoProvider.getCurrentIpAddress() ?: return ""
        if (listeningPort == 0) return ""
        return "http://$ip:$listeningPort/callback"
    }

    private fun handleConnection(socket: Socket) {
        socket.use { s ->
            try {
                val input = s.getInputStream()
                val reader = BufferedReader(InputStreamReader(input))

                // 1. Read Request Line
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return

                val methodStr = parts[0].uppercase()
                // We only care about NOTIFY
                if (methodStr != "NOTIFY") return

                // 2. Read Headers
                val headers = mutableMapOf<String, String>()
                var contentLength = 0
                var line = reader.readLine()
                while (!line.isNullOrBlank()) {
                    val headerParts = line.split(":", limit = 2)
                    if (headerParts.size == 2) {
                        val key = headerParts[0].trim()
                        val value = headerParts[1].trim()
                        headers[key] = value
                        if (key.equals("Content-Length", ignoreCase = true)) {
                            contentLength = value.toIntOrNull() ?: 0
                        }
                    }
                    line = reader.readLine()
                }

                // 3. Read Body
                val bodyChars = CharArray(contentLength)
                var bytesRead = 0
                while (bytesRead < contentLength) {
                    val read = reader.read(bodyChars, bytesRead, contentLength - bytesRead)
                    if (read == -1) break
                    bytesRead += read
                }
                val body = String(bodyChars)

                // 4. Construct Request Object
                val request = HttpRequest(
                    method = HttpMethod.NOTIFY,
                    path = parts[1],
                    headers = headers,
                    body = body,
                    inputStream = null
                )

                // 5. Delegate to Handler (Core)
                // FIX: Use runBlocking to keep the socket open while processing
                runBlocking {
                    val response = handler?.handle(request) ?: HttpResponse(404)

                    // 6. Send Response
                    val output = s.getOutputStream()
                    val respHeader = "HTTP/1.1 ${response.statusCode} OK\r\nContent-Length: 0\r\n\r\n"
                    output.write(respHeader.toByteArray())
                    output.flush()
                }

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}