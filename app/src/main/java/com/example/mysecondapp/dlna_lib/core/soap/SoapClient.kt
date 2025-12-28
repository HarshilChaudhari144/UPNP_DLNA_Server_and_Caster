package com.example.mysecondapp.dlna_lib.core.soap

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL

class SoapClient {

    /**
     * Sends a standard SOAP Action (POST) to control a device.
     */
    suspend fun sendAction(
        controlUrl: String,
        serviceType: String,
        actionName: String,
        arguments: Map<String, String>
    ): String {
        return withContext(Dispatchers.IO) {
            val soapAction = "\"$serviceType#$actionName\""
            val body = buildSoapBody(actionName, serviceType, arguments)

            Log.d("SoapClient", "Sending $actionName to $controlUrl")

            try {
                val url = URL(controlUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.readTimeout = 5000
                conn.connectTimeout = 5000

                // Required UPnP Headers
                conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
                conn.setRequestProperty("SOAPAction", soapAction)
                conn.setRequestProperty("Connection", "Close")

                // Write Body
                OutputStreamWriter(conn.outputStream).use { it.write(body) }

                val responseCode = conn.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val result = conn.inputStream.bufferedReader().use { it.readText() }
                    Log.d("SoapClient", "TV Response: $result")
                    result
                } else {
                    val error = conn.errorStream?.bufferedReader()?.use { it.readText() }
                    Log.e("SoapClient", "SOAP Error $responseCode: $error")
                    throw Exception("SOAP Action failed: $responseCode")
                }
            } catch (e: Exception) {
                Log.e("SoapClient", "Transport Error", e)
                throw e
            }
        }
    }

    // --- GENA (Eventing) Methods for Phase 8 ---

    /**
     * Subscribes to event notifications.
     * Returns the Subscription ID (SID) if successful, or null if failed.
     */
    suspend fun subscribe(eventUrl: String, callbackUrl: String): String? {
        val headers = mapOf(
            "CALLBACK" to "<$callbackUrl>",
            "NT" to "upnp:event",
            "TIMEOUT" to "Second-1800" // Request 30 minutes
        )

        val responseHeaders = sendGenaRequest("SUBSCRIBE", eventUrl, headers)
        val sid = responseHeaders["SID"] ?: responseHeaders["sid"]

        if (sid != null) {
            Log.d("SoapClient", "Subscribed successfully. SID: $sid")
        } else {
            Log.e("SoapClient", "Subscribe failed. No SID in response.")
        }
        return sid
    }

    /**
     * Renews an existing subscription to keep it alive.
     */
    suspend fun renewSubscription(eventUrl: String, sid: String): Boolean {
        val headers = mapOf(
            "SID" to sid,
            "TIMEOUT" to "Second-1800"
        )
        // If the map is not empty, we got a 200 OK
        val responseHeaders = sendGenaRequest("SUBSCRIBE", eventUrl, headers)
        return responseHeaders.isNotEmpty()
    }

    /**
     * Cancels the subscription.
     */
    suspend fun unsubscribe(eventUrl: String, sid: String): Boolean {
        val headers = mapOf(
            "SID" to sid
        )
        val responseHeaders = sendGenaRequest("UNSUBSCRIBE", eventUrl, headers)
        return responseHeaders.isNotEmpty()
    }

    // --- Helpers ---

    private fun buildSoapBody(action: String, serviceType: String, args: Map<String, String>): String {
        val argsXml = args.entries.joinToString("") { "<${it.key}>${it.value}</${it.key}>" }

        return """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/" xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
                <s:Body>
                    <u:$action xmlns:u="$serviceType">
                        $argsXml
                    </u:$action>
                </s:Body>
            </s:Envelope>
        """.trimIndent()
    }

    /**
     * Sends a raw HTTP request via Socket.
     * Needed because HttpURLConnection throws ProtocolException for "SUBSCRIBE" and "UNSUBSCRIBE" verbs.
     */
    private suspend fun sendGenaRequest(method: String, urlStr: String, headers: Map<String, String>): Map<String, String> {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL(urlStr)
                val port = if (url.port == -1) 80 else url.port
                // FIX: url.host can technically be null, so we default to empty string or fail gracefully
                val host = url.host ?: throw Exception("Invalid Host in URL: $urlStr")

                // 1. Open Raw Socket
                Socket(host, port).use { socket ->
                    socket.soTimeout = 5000

                    val writer = OutputStreamWriter(socket.getOutputStream(), "UTF-8")
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

                    // 2. Construct Raw HTTP Request
                    val path = if (url.path.isEmpty()) "/" else url.path
                    val sb = StringBuilder()
                    sb.append("$method $path HTTP/1.1\r\n")
                    sb.append("Host: ${url.host}:$port\r\n")
                    headers.forEach { (k, v) -> sb.append("$k: $v\r\n") }
                    sb.append("Content-Length: 0\r\n") // GENA requests usually have no body
                    sb.append("Connection: Close\r\n")
                    sb.append("\r\n") // End of headers

                    // 3. Send
                    writer.write(sb.toString())
                    writer.flush()

                    // 4. Read Response (Headers only)
                    val responseHeaders = mutableMapOf<String, String>()
                    var line = reader.readLine()

                    // First line is status like "HTTP/1.1 200 OK"
                    if (line != null && line.contains(" 200 ")) {
                        // Success! Parse headers
                        while (true) {
                            line = reader.readLine()
                            if (line.isNullOrEmpty()) break // End of headers

                            val parts = line.split(":", limit = 2)
                            if (parts.size == 2) {
                                responseHeaders[parts[0].trim().uppercase()] = parts[1].trim()
                            }
                        }
                    } else {
                        Log.w("SoapClient", "GENA $method failed: $line")
                        return@withContext emptyMap<String, String>()
                    }

                    return@withContext responseHeaders
                }
            } catch (e: Exception) {
                Log.e("SoapClient", "GENA Transport Error ($method)", e)
                emptyMap()
            }
        }
    }
}