package com.example.mysecondapp.dlna_lib.core.eventing

import com.example.mysecondapp.dlna_lib.api.device.Service
import com.example.mysecondapp.dlna_lib.api.errors.DlnaError
import com.example.mysecondapp.dlna_lib.core.lifecycle.dlnaScope
import com.example.mysecondapp.dlna_lib.core.logging.DlnaLogger
import com.example.mysecondapp.dlna_lib.platform.*
import kotlinx.coroutines.*
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import javax.xml.parsers.DocumentBuilderFactory

internal class SubscriptionManager(
    private val eventServer: EventCallbackServer
) : HttpHandler {

    private val tag = "SubscriptionManager"

    // Maps ServiceId -> Subscription Data
    private val activeSubscriptions = ConcurrentHashMap<String, SubscriptionData>()

    // Maps SID (Subscription ID) -> ServiceId
    private val sidToServiceId = ConcurrentHashMap<String, String>()

    // Listeners: ServiceId -> Callback function
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

    // --- HTTP HANDLER (Incoming NOTIFY) ---

    override suspend fun handle(request: HttpRequest): HttpResponse {
        // Fix 1: Compare Enum directly
        if (request.method != HttpMethod.NOTIFY) {
            return HttpResponse(405) // Method Not Allowed
        }

        // SID is case-insensitive in header lookup
        val sid = request.headers.entries.find { it.key.equals("SID", ignoreCase = true) }?.value ?: ""

        if (sid.isEmpty()) return HttpResponse(412) // Precondition Failed

        val serviceId = sidToServiceId[sid]
        if (serviceId == null) {
            DlnaLogger.w(tag, "Received event for unknown SID: $sid")
            return HttpResponse(412)
        }

        // Fix 2: Body is already a String, just use it
        val bodyStr = request.body ?: ""

        if (bodyStr.isNotEmpty()) {
            val properties = parsePropertySet(bodyStr)
            if (properties.isNotEmpty()) {
                listeners[serviceId]?.invoke(properties)
            }
        }

        return HttpResponse(200)
    }

    // --- Internal Logic ---

    private fun performSubscribe(service: Service) {
        val callbackUrl = eventServer.getCallbackUrl()
        if (callbackUrl.isEmpty()) throw Exception("Callback Server URL is invalid")

        val url = URL(service.eventSubUrl)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "SUBSCRIBE"
        conn.addRequestProperty("NT", "upnp:event")
        conn.addRequestProperty("CALLBACK", "<$callbackUrl>")
        conn.addRequestProperty("TIMEOUT", "Second-300")
        conn.connectTimeout = 5000

        val code = conn.responseCode
        if (code != 200) {
            throw Exception("HTTP $code")
        }

        val sid = conn.getHeaderField("SID") ?: throw Exception("No SID returned")
        val timeoutStr = conn.getHeaderField("TIMEOUT") ?: "Second-300"

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
        val url = URL(sub.service.eventSubUrl)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "SUBSCRIBE"
        conn.addRequestProperty("SID", sub.sid)
        conn.addRequestProperty("TIMEOUT", "Second-300")

        try {
            if (conn.responseCode == 200) {
                val newSub = sub.copy(expirationTime = System.currentTimeMillis() + (sub.timeoutSeconds * 1000L))
                activeSubscriptions[sub.service.serviceId] = newSub
            } else {
                DlnaLogger.w(tag, "Renew failed for ${sub.service.serviceId}: ${conn.responseCode}")
                activeSubscriptions.remove(sub.service.serviceId)
                sidToServiceId.remove(sub.sid)
            }
        } catch (e: Exception) {
            DlnaLogger.w(tag, "Renew error: ${e.message}")
        }
    }

    private fun unsubscribeInternal(sub: SubscriptionData) {
        try {
            val url = URL(sub.service.eventSubUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "UNSUBSCRIBE"
            conn.addRequestProperty("SID", sub.sid)
            conn.responseCode

            // Clean up internal state
            activeSubscriptions.remove(sub.service.serviceId)
            sidToServiceId.remove(sub.sid)
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun startRenewalLoop() {
        renewalJob = dlnaScope.launch(Dispatchers.IO) {
            while (isActive) {
                val now = System.currentTimeMillis()
                activeSubscriptions.values.forEach { sub ->
                    val timeRemaining = sub.expirationTime - now
                    // Renew if 50% of time has elapsed
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