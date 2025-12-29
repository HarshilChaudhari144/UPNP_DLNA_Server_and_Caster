package com.example.mysecondapp.dlna_lib.core.eventing

import com.example.mysecondapp.dlna_lib.api.device.Service
import com.example.mysecondapp.dlna_lib.api.errors.DlnaError
import com.example.mysecondapp.dlna_lib.core.lifecycle.dlnaScope
import com.example.mysecondapp.dlna_lib.core.logging.DlnaLogger
import com.example.mysecondapp.dlna_lib.platform.*
import kotlinx.coroutines.*
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.StringReader
import java.net.Socket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import javax.xml.parsers.DocumentBuilderFactory

internal class SubscriptionManager(
    private val eventServer: EventCallbackServer
) : HttpHandler {

    private val tag = "SubscriptionManager"

    private val activeSubscriptions = ConcurrentHashMap<String, SubscriptionData>()
    private val sidToServiceId = ConcurrentHashMap<String, String>()
    private val listeners = ConcurrentHashMap<String, (Map<String, String>) -> Unit>()

    private var renewalJob: Job? = null

    data class SubscriptionData(
        val service: Service,
        val sid: String,
        val timeoutSeconds: Int,
        val expirationTime: Long
    )

    fun start() {
        DlnaLogger.d(tag, "Starting Subscription Manager")
        eventServer.start(this)
        startRenewalLoop()
    }

    fun stop() {
        DlnaLogger.d(tag, "Stopping Subscription Manager")
        renewalJob?.cancel()

        val subs = ArrayList(activeSubscriptions.values)
        runBlocking(Dispatchers.IO) {
            subs.forEach { unsubscribeInternal(it) }
        }

        activeSubscriptions.clear()
        sidToServiceId.clear()
        listeners.clear()
        eventServer.stop()
    }

    suspend fun subscribe(service: Service, callback: (Map<String, String>) -> Unit) {
        if (service.eventSubUrl == null) {
            DlnaLogger.w(tag, "Service ${service.serviceId} has no event URL")
            return
        }

        listeners[service.serviceId] = callback

        if (activeSubscriptions.containsKey(service.serviceId)) {
            return
        }

        withContext(Dispatchers.IO) {
            try {
                performSubscribe(service)
            } catch (e: Exception) {
                DlnaLogger.e(tag, "Subscription failed for ${service.serviceId}: ${e.message}")
                throw DlnaError.Subscription("Failed to subscribe: ${e.message}")
            }
        }
    }

    suspend fun unsubscribe(service: Service) {
        listeners.remove(service.serviceId)
        val sub = activeSubscriptions[service.serviceId] ?: return

        withContext(Dispatchers.IO) {
            unsubscribeInternal(sub)
        }
    }

    override suspend fun handle(request: HttpRequest): HttpResponse {
        if (request.method != HttpMethod.NOTIFY) {
            return HttpResponse(405)
        }

        val sid = request.headers.entries.find { it.key.equals("SID", ignoreCase = true) }?.value ?: ""
        if (sid.isEmpty()) return HttpResponse(412)

        val serviceId = sidToServiceId[sid]
        if (serviceId == null) {
            DlnaLogger.w(tag, "Received event for unknown SID: $sid")
            return HttpResponse(412)
        }

        val bodyStr = request.body ?: ""
        if (bodyStr.isNotEmpty()) {
            val properties = parsePropertySet(bodyStr)
            if (properties.isNotEmpty()) {
                listeners[serviceId]?.invoke(properties)
            }
        }

        return HttpResponse(200)
    }

    // --- Raw Socket Logic (Bypassing HttpURLConnection) ---

    private fun performSubscribe(service: Service) {
        val callbackUrl = eventServer.getCallbackUrl()
        if (callbackUrl.isEmpty()) throw Exception("Callback Server URL is invalid")

        // Use Raw Socket request to send "SUBSCRIBE" method
        val headers = mapOf(
            "NT" to "upnp:event",
            "CALLBACK" to "<$callbackUrl>",
            "TIMEOUT" to "Second-300"
        )

        val response = executeRawRequest("SUBSCRIBE", service.eventSubUrl!!, headers)

        val sid = response["SID"] ?: throw Exception("No SID returned")
        val timeoutStr = response["TIMEOUT"] ?: "Second-300"

        val timeoutSeconds = try {
            timeoutStr.replace("Second-", "").trim().toInt()
        } catch (e: Exception) { 300 }

        val sub = SubscriptionData(
            service = service,
            sid = sid,
            timeoutSeconds = timeoutSeconds,
            expirationTime = System.currentTimeMillis() + (timeoutSeconds * 1000L)
        )

        activeSubscriptions[service.serviceId] = sub
        sidToServiceId[sid] = service.serviceId

        DlnaLogger.d(tag, "Subscribed to ${service.serviceId} (SID: $sid)")
    }

    private fun performRenew(sub: SubscriptionData) {
        try {
            val headers = mapOf(
                "SID" to sub.sid,
                "TIMEOUT" to "Second-300"
            )

            // Re-use raw request for SUBSCRIBE
            executeRawRequest("SUBSCRIBE", sub.service.eventSubUrl!!, headers)

            // Success, update time
            val newSub = sub.copy(expirationTime = System.currentTimeMillis() + (sub.timeoutSeconds * 1000L))
            activeSubscriptions[sub.service.serviceId] = newSub

        } catch (e: Exception) {
            DlnaLogger.w(tag, "Renew failed for ${sub.service.serviceId}: ${e.message}")
            activeSubscriptions.remove(sub.service.serviceId)
            sidToServiceId.remove(sub.sid)
        }
    }

    private fun unsubscribeInternal(sub: SubscriptionData) {
        try {
            val headers = mapOf("SID" to sub.sid)
            executeRawRequest("UNSUBSCRIBE", sub.service.eventSubUrl!!, headers)

            activeSubscriptions.remove(sub.service.serviceId)
            sidToServiceId.remove(sub.sid)
        } catch (e: Exception) {
            // Ignore errors on unsubscribe
        }
    }

    /**
     * Manually constructs an HTTP request string and sends it over a raw TCP socket.
     * Required because HttpURLConnection throws exceptions for SUBSCRIBE/UNSUBSCRIBE methods on Android.
     */
    private fun executeRawRequest(method: String, urlStr: String, headers: Map<String, String>): Map<String, String> {
        val url = URL(urlStr)
        val port = if (url.port == -1) 80 else url.port
        val host = url.host
        val path = if (url.path.isEmpty()) "/" else url.path

        Socket(host, port).use { socket ->
            socket.soTimeout = 5000 // 5s timeout

            val writer = socket.getOutputStream().bufferedWriter()

            // 1. Write Request Line
            writer.write("$method $path HTTP/1.1\r\n")

            // 2. Write Host Header (Required)
            writer.write("HOST: $host:$port\r\n")

            // 3. Write Custom Headers
            headers.forEach { (k, v) ->
                writer.write("$k: $v\r\n")
            }

            // 4. End Headers
            writer.write("Connection: Close\r\n") // Ensure connection closes
            writer.write("\r\n")
            writer.flush()

            // 5. Read Response
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

            // Read Status Line
            val statusLine = reader.readLine() ?: throw Exception("Empty response from device")
            if (!statusLine.contains(" 200 OK")) {
                throw Exception("HTTP Error: $statusLine")
            }

            // Read Response Headers
            val responseHeaders = mutableMapOf<String, String>()
            var line = reader.readLine()
            while (!line.isNullOrBlank()) {
                val parts = line.split(":", limit = 2)
                if (parts.size == 2) {
                    responseHeaders[parts[0].trim().uppercase()] = parts[1].trim()
                }
                line = reader.readLine()
            }

            return responseHeaders
        }
    }

    private fun startRenewalLoop() {
        renewalJob = dlnaScope.launch(Dispatchers.IO) {
            while (isActive) {
                val now = System.currentTimeMillis()
                activeSubscriptions.values.forEach { sub ->
                    val timeRemaining = sub.expirationTime - now
                    if (timeRemaining < (sub.timeoutSeconds * 500)) {
                        performRenew(sub)
                    }
                }
                delay(30_000)
            }
        }
    }

    private fun parsePropertySet(xml: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(InputSource(StringReader(xml)))
            doc.documentElement.normalize()

            val props = doc.getElementsByTagNameNS("*", "property")
            for (i in 0 until props.length) {
                val propNode = props.item(i)
                val childNodes = propNode.childNodes
                for (j in 0 until childNodes.length) {
                    val node = childNodes.item(j)
                    if (node.nodeType == Node.ELEMENT_NODE) {
                        map[node.localName] = node.textContent
                    }
                }
            }
        } catch (e: Exception) {
            DlnaLogger.w(tag, "Error parsing event XML: ${e.message}")
        }
        return map
    }
}