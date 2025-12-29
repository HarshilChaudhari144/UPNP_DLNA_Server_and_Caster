package com.example.mysecondapp.dlna_lib.core.ssdp

import com.example.mysecondapp.dlna_lib.core.device.DeviceStateMachine
import com.example.mysecondapp.dlna_lib.core.lifecycle.dlnaScope
import com.example.mysecondapp.dlna_lib.core.logging.DlnaLogger
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal class SsdpCache(
    private val stateMachine: DeviceStateMachine
) {
    private val tag = "SsdpCache"

    // Map of UDN -> Expiration Timestamp (System.currentTimeMillis + duration)
    private val expirationMap = mutableMapOf<String, Long>()
    private var cleanupJob: Job? = null

    fun start() {
        stop()
        cleanupJob = dlnaScope.launch {
            while (isActive) {
                cleanup()
                delay(10_000) // Check every 10 seconds
            }
        }
    }

    fun stop() {
        cleanupJob?.cancel()
        cleanupJob = null
        synchronized(expirationMap) {
            expirationMap.clear()
        }
    }

    fun recordAlive(udn: String, maxAgeSeconds: Int) {
        val expiryTime = System.currentTimeMillis() + (maxAgeSeconds * 1000L)
        synchronized(expirationMap) {
            expirationMap[udn] = expiryTime
        }
    }

    fun recordByeBye(udn: String) {
        synchronized(expirationMap) {
            expirationMap.remove(udn)
        }
        // State machine is notified immediately by the Controller,
        // but we remove it here to keep the cache clean.
    }

    private fun cleanup() {
        val now = System.currentTimeMillis()
        val expiredUdns = mutableListOf<String>()

        synchronized(expirationMap) {
            val iterator = expirationMap.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.value < now) {
                    expiredUdns.add(entry.key)
                    iterator.remove()
                }
            }
        }

        expiredUdns.forEach { udn ->
            DlnaLogger.d(tag, "Device expired: $udn")
            stateMachine.onSsdpByeBye(udn)
        }
    }
}