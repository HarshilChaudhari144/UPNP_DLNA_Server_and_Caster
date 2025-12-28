package com.example.mysecondapp.dlna_lib.core.discovery

import android.util.Log
import com.example.mysecondapp.dlna_lib.core.models.Device
import com.example.mysecondapp.dlna_lib.core.parsers.DeviceDescriptionParser
import com.example.mysecondapp.dlna_lib.platform.SsdpTransport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.net.URL
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap

class SsdpHandler(
    private val transport: SsdpTransport,
    private val scope: CoroutineScope
) {
    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices.asStateFlow()

    private val discoveredLocations = ConcurrentHashMap<String, Boolean>()
    private val descriptionParser = DeviceDescriptionParser()

    // --- SERVER MODE STATE ---
    private var isServerEnabled = false
    private var serverUuid: String = ""
    private var serverBaseUrl: String = "" // http://192.168.x.x:8080
    private var serverJob: Job? = null

    // Constants
    private val SSDP_ADDR = "239.255.255.250"
    private val SSDP_PORT = 1900

    /**
     * Call this to make the App visible to TVs.
     * @param baseUrl The URL of your HTTP server (e.g. http://192.168.1.5:8080)
     * @param uuid The unique ID of this server
     */
    fun enableServer(baseUrl: String, uuid: String) {
        this.serverBaseUrl = baseUrl
        this.serverUuid = uuid
        this.isServerEnabled = true
        Log.d("SsdpHandler", "Server Mode Enabled. Location: $baseUrl/rootDesc.xml")
    }

    fun start() {
        transport.start()

        // 1. Process Incoming Packets (Listen)
        scope.launch(Dispatchers.IO) {
            transport.incomingPackets.collect { packet ->
                processPacket(packet)
            }
        }

        // 2. Client Mode: Search for others
        sendDiscoverySearch()

        // 3. Server Mode: Announce ourselves periodically
        if (isServerEnabled) {
            startServerAnnouncements()
        }
    }

    fun stop() {
        if (isServerEnabled) {
            sendByeBye() // Tell TV we are going offline
        }
        serverJob?.cancel()
        transport.stop()
    }

    // --- CLIENT LOGIC (Existing) ---

    private fun sendDiscoverySearch() {
        val mSearch = """
            M-SEARCH * HTTP/1.1
            HOST: $SSDP_ADDR:$SSDP_PORT
            MAN: "ssdp:discover"
            MX: 3
            ST: ssdp:all
            
        """.trimIndent().replace("\n", "\r\n") + "\r\n"
        transport.send(mSearch.toByteArray())
    }

    private suspend fun processPacket(data: ByteArray) {
        val text = String(data, Charset.defaultCharset())

        // A. If we are a Server, check if someone is looking for us
        if (isServerEnabled && text.startsWith("M-SEARCH")) {
            handleIncomingSearch(text)
            return
        }

        // B. Client Logic: Look for other devices
        val locationMatch = Regex("(?i)LOCATION: (.*)").find(text)
        val locationUrl = locationMatch?.groupValues?.get(1)?.trim() ?: return

        // Filter out our own server if detected
        if (isServerEnabled && locationUrl.contains(serverBaseUrl)) return

        if (discoveredLocations.putIfAbsent(locationUrl, true) == null) {
            fetchAndParseDevice(locationUrl)
        }
    }

    private suspend fun fetchAndParseDevice(url: String) {
        withContext(Dispatchers.IO) {
            try {
                val xml = URL(url).readText()
                val device = descriptionParser.parse(xml, url)
                if (device != null) {
                    updateDeviceList(device)
                    Log.d("SsdpHandler", "Discovered: ${device.friendlyName}")
                }
            } catch (e: Exception) {
                // Log.e("SsdpHandler", "Failed to load device desc: $url")
                discoveredLocations.remove(url)
            }
        }
    }

    private fun updateDeviceList(newDevice: Device) {
        _devices.update { currentList ->
            val list = currentList.toMutableList()
            val index = list.indexOfFirst { it.udn == newDevice.udn }
            if (index != -1) list[index] = newDevice else list.add(newDevice)
            list
        }
    }

    // --- SERVER LOGIC (New) ---

    private fun startServerAnnouncements() {
        serverJob?.cancel()
        serverJob = scope.launch(Dispatchers.IO) {
            // Send "Alive" immediately, then every 10 seconds
            while (isActive) {
                sendAlive()
                delay(10_000)
            }
        }
    }

    private fun handleIncomingSearch(packetText: String) {
        // If the TV asks "ssdp:all" or "MediaServer", we reply
        if (packetText.contains("ssdp:all") || packetText.contains("MediaServer")) {
            Log.d("SsdpHandler", "Replying to M-SEARCH from TV")
            sendAlive() // Simplified: Responding with Multicast Notify is often enough and easier
        }
    }

    private fun sendAlive() {
        // We broadcast 3 notifications for robustness
        val types = listOf(
            "upnp:rootdevice",
            "urn:schemas-upnp-org:device:MediaServer:1",
            "uuid:$serverUuid" // Unique ID
        )

        types.forEach { type ->
            val packet = """
                NOTIFY * HTTP/1.1
                HOST: $SSDP_ADDR:$SSDP_PORT
                CACHE-CONTROL: max-age=1800
                LOCATION: $serverBaseUrl/rootDesc.xml
                NT: $type
                NTS: ssdp:alive
                SERVER: Android/14 UPnP/1.0 MyDlna/1.0
                USN: uuid:$serverUuid::$type
                
            """.trimIndent().replace("\n", "\r\n") + "\r\n"

            try { transport.send(packet.toByteArray()) } catch (e: Exception) {}
            // Small delay to prevent flooding buffer
            runBlocking { delay(20) }
        }
    }

    private fun sendByeBye() {
        val type = "urn:schemas-upnp-org:device:MediaServer:1"
        val packet = """
            NOTIFY * HTTP/1.1
            HOST: $SSDP_ADDR:$SSDP_PORT
            NT: $type
            NTS: ssdp:byebye
            USN: uuid:$serverUuid::$type
            
        """.trimIndent().replace("\n", "\r\n") + "\r\n"
        transport.send(packet.toByteArray())
    }
}