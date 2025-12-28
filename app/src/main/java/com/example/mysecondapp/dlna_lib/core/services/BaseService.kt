package com.example.mysecondapp.dlna_lib.core.services

import android.util.Log
import com.example.mysecondapp.dlna_lib.core.models.Service
import com.example.mysecondapp.dlna_lib.core.soap.SoapClient
import kotlinx.coroutines.*

abstract class BaseService(
    protected val service: Service,
    protected val soapClient: SoapClient
) {
    // Phase 8: Subscription State
    private var subscriptionId: String? = null
    private var renewalJob: Job? = null

    // Internal scope for background renewal tasks
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Sends a generic SOAP Action (Play, Stop, etc.)
     */
    protected suspend fun action(name: String, args: Map<String, String> = emptyMap()): String {
        return soapClient.sendAction(
            controlUrl = service.controlUrl,
            serviceType = service.serviceType,
            actionName = name,
            arguments = args
        )
    }

    // --- GENA Eventing (Phase 8) ---

    /**
     * Subscribes to device events.
     * @param callbackBaseUrl The "http://IP:PORT" of our Android device.
     * @param callbackPath Unique path for this service (e.g. "/event/avtransport").
     */
    suspend fun subscribe(callbackBaseUrl: String, callbackPath: String) {

        val eventUrl = service.eventSubUrl
        if (eventUrl.isNullOrEmpty()) return
        // 1. Construct the URL the TV should call us back on
        val fullCallbackUrl = "$callbackBaseUrl$callbackPath"

        Log.d("BaseService", "Subscribing to ${service.serviceType} with callback: $fullCallbackUrl")

        // 2. Send SUBSCRIBE request via SoapClient
        val sid = soapClient.subscribe(eventUrl, fullCallbackUrl)

        if (sid != null) {
            subscriptionId = sid
            Log.d("BaseService", "Subscribed successfully. SID: $sid")

            // 3. Start Auto-Renewal
            startRenewalTimer()
        }
    }

    /**
     * Unsubscribes and cleans up resources.
     */
    suspend fun unsubscribe() {
        val sid = subscriptionId ?: return
        val eventUrl = service.eventSubUrl ?: return // FIX: Safety check

        // Cancel renewal first
        renewalJob?.cancel()
        renewalJob = null

        try {
            soapClient.unsubscribe(eventUrl, sid)
            Log.d("BaseService", "Unsubscribed from ${service.serviceType}")
        } catch (e: Exception) {
            Log.w("BaseService", "Error unsubscribing", e)
        } finally {
            subscriptionId = null
        }
    }

    /**
     * Keeps the subscription alive by sending a renewal request every 15 minutes.
     */
    private fun startRenewalTimer() {
        renewalJob?.cancel()
        renewalJob = scope.launch {
            while (isActive) {
                // Wait 15 minutes (subscriptions usually last 30 mins)
                delay(15 * 60 * 1000L)

                val sid = subscriptionId ?: break
                val eventUrl = service.eventSubUrl ?: break // FIX: Safety check
                Log.d("BaseService", "Renewing subscription... $sid")

                val success = soapClient.renewSubscription(eventUrl, sid)

                if (success) {
                    Log.d("BaseService", "Renewal successful.")
                } else {
                    Log.e("BaseService", "Renewal failed. Subscription lost.")
                    subscriptionId = null
                    break
                }
            }
        }
    }

    /**
     * Called when our HTTP Server receives a NOTIFY message for this service.
     * Child classes must implement the parsing logic.
     */
    abstract suspend fun handleEvent(xmlBody: String)
}