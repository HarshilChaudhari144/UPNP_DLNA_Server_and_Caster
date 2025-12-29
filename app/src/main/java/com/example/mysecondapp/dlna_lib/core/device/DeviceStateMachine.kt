package com.example.mysecondapp.dlna_lib.core.device

import com.example.mysecondapp.dlna_lib.core.lifecycle.dlnaScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

internal class DeviceStateMachine(
    private val repository: DeviceRepository,
    private val parser: DeviceDescriptorParser
) {
    // Keeps track of URLs currently being fetched to avoid duplicate requests
    private val pendingFetches = mutableSetOf<String>()

    /**
     * Called when SSDP finds a "location" URL.
     */
    fun onDeviceDiscovered(location: String) {
        if (pendingFetches.contains(location)) return

        // If we already have a device with this location, we might want to skip
        // or re-verify. For now, let's fetch if it's new.
        dlnaScope.launch {
            fetchAndRegister(location)
        }
    }

    private suspend fun fetchAndRegister(location: String) {
        pendingFetches.add(location)
        try {
            // Perform the network IO on the IO dispatcher
            val device = withContext(Dispatchers.IO) {
                URL(location).openStream().use { stream ->
                    parser.parse(stream, location)
                }
            }

            if (device != null) {
                repository.upsert(device)
            }
        } catch (e: Exception) {
            // Log error: Failed to fetch descriptor
            e.printStackTrace()
        } finally {
            pendingFetches.remove(location)
        }
    }

    /**
     * Called when an SSDP "byebye" message is received.
     */
    fun onDeviceOffline(usn: String) {
        // USN usually contains the UDN (Unique Device Name)
        // We extract the UDN to remove it from the repo
        val udn = usn.split("::").firstOrNull() ?: usn
        repository.remove(udn)
    }
}