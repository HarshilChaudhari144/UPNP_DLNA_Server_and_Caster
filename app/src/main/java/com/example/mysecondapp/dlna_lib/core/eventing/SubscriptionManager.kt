package com.example.mysecondapp.dlna_lib.core.eventing

import com.example.mysecondapp.dlna_lib.core.lifecycle.dlnaScope
import com.example.mysecondapp.dlna_lib.platform.EventCallbackServer
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL

internal class SubscriptionManager(
    private val eventServer: EventCallbackServer
) {
    // Maps Service Control URL -> Subscription ID (SID)
    private val activeSubscriptions = mutableMapOf<String, String>()
    private val renewalJobs = mutableMapOf<String, Job>()

    /**
     * Subscribes to a service's events.
     * @param eventSubUrl The URL provided in the Device XML for eventing.
     * @param timeoutSeconds Requested duration of subscription.
     */
    fun subscribe(eventSubUrl: String, timeoutSeconds: Int = 300) {
        dlnaScope.launch(Dispatchers.IO) {
            try {
                val connection = URL(eventSubUrl).openConnection() as HttpURLConnection
                connection.requestMethod = "SUBSCRIBE"
                connection.setRequestProperty("CALLBACK", "<${eventServer.getCallbackUrl()}>")
                connection.setRequestProperty("NT", "upnp:event")
                connection.setRequestProperty("TIMEOUT", "Second-$timeoutSeconds")

                if (connection.responseCode == 200) {
                    val sid = connection.getHeaderField("SID")
                    val actualTimeout = connection.getHeaderField("TIMEOUT")
                        ?.removePrefix("Second-")?.toIntOrNull() ?: timeoutSeconds

                    activeSubscriptions[eventSubUrl] = sid
                    scheduleRenewal(eventSubUrl, sid, actualTimeout)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun scheduleRenewal(url: String, sid: String, seconds: Int) {
        renewalJobs[url]?.cancel()
        renewalJobs[url] = dlnaScope.launch {
            // Renew halfway through the timeout period
            delay((seconds * 1000L) / 2)
            renew(url, sid)
        }
    }

    private suspend fun renew(url: String, sid: String) = withContext(Dispatchers.IO) {
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "SUBSCRIBE"
            connection.setRequestProperty("SID", sid)
            connection.setRequestProperty("TIMEOUT", "Second-300")

            if (connection.responseCode == 200) {
                val actualTimeout = connection.getHeaderField("TIMEOUT")
                    ?.removePrefix("Second-")?.toIntOrNull() ?: 300
                scheduleRenewal(url, sid, actualTimeout)
            }
        } catch (e: Exception) {
            activeSubscriptions.remove(url)
        }
    }

    fun unsubscribeAll() {
        activeSubscriptions.forEach { (url, sid) ->
            dlnaScope.launch(Dispatchers.IO) {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "UNSUBSCRIBE"
                connection.setRequestProperty("SID", sid)
                connection.responseCode // Execute
            }
        }
        renewalJobs.values.forEach { it.cancel() }
        activeSubscriptions.clear()
    }
}