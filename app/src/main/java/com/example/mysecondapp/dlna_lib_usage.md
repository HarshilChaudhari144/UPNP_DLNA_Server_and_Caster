# 📚 DLNA Library — Integration Guide

This library allows your Android app to:
1.  **Discover** DLNA devices (TVs, Speakers, Media Servers).
2.  **Control** playback on those devices (Play, Pause, Seek, Volume).
3.  **Browse** remote content sources (NAS, Windows Media Player).
4.  **Host** local files so TVs can play content from your phone.

---

## 1. Prerequisites

### **A. Gradle Dependencies**
Ensure your `app/build.gradle` includes **NanoHTTPD** (used by `dlna-android` for the HTTP server) and Coroutines.

```kotlin
dependencies {
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
```

### **B. Android Manifest Permissions**
Add these to `AndroidManifest.xml`.
*   **Multicast:** Required for SSDP discovery.
*   **Internet:** Required for HTTP/SOAP calls.

```xml
<manifest ...>
    <!-- Required for Discovery & Networking -->
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE" />
    
    <!-- Required if you want to host local files (Media Server) -->
    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
    <!-- Or READ_MEDIA_IMAGES / VIDEO / AUDIO for Android 13+ -->
</manifest>
```

---

## 2. Initialization

You must initialize the library once, ideally in your `Application` class or a singleton `ViewModel`.

```kotlin
// 1. Create the Platform implementation (Hardware bindings)
val platform = AndroidDlnaPlatform(context)

// 2. Define Configuration
val config = DlnaConfig(
    enableMediaServer = true, // Set to false if you only want to be a Remote Control
    serverName = "My Android App",
    contentProvider = MyLocalContentProvider(context), // See Section 5 for implementation
    thumbnailProvider = null
)

// 3. Start the Engine
DlnaManager.start(config, platform)
```

---

## 3. Discovery (Finding Devices)

The `DlnaManager` exposes a reactive list of devices.

```kotlin
lifecycleScope.launch {
    DlnaManager.devices.devices.collect { allDevices ->
        
        // Filter: Find TVs and Speakers
        val renderers = allDevices.filter { device -> 
            device.services.any { it.serviceType.contains("AVTransport") }
        }

        // Filter: Find NAS or Media Servers
        val servers = allDevices.filter { device ->
            device.services.any { it.serviceType.contains("ContentDirectory") }
        }

        renderers.forEach { println("Found TV: ${it.friendlyName}") }
    }
}
```

---

## 4. Playback (Remote Control)

To play media on a TV, you need to select it, then send a `MediaItem`.

### **A. Connect to a Renderer**
```kotlin
val targetTvId = "uuid:1234-5678-..." // Get this from the Discovery list above

try {
    DlnaManager.playback.setRenderer(targetTvId)
    println("Connected to TV!")
} catch (e: Exception) {
    println("Failed to connect: ${e.message}")
}
```

### **B. Play a URL**
You must construct a `MediaItem`. The `resources` list contains the actual URL the TV will stream.

```kotlin
val mediaItem = MediaItem(
    id = "101",
    parentId = "0",
    title = "Big Buck Bunny",
    upnpClass = "object.item.videoItem",
    mediaType = MediaType.VIDEO,
    resources = listOf(
        MediaResource(
            uri = "http://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
            protocolInfo = "http-get:*:video/mp4:*", // Crucial for DLNA
            mimeType = "video/mp4"
        )
    )
)

// Play
DlnaManager.playback.play(mediaItem)
```

### **C. Controls & State**
The state is reactive. You don't poll; you observe.

```kotlin
// Observe State (Update UI)
lifecycleScope.launch {
    DlnaManager.playback.playbackState.collect { state ->
        println("Status: ${state.transportState}") // PLAYING, PAUSED, STOPPED
        println("Time: ${state.position} / ${state.duration}")
        println("Volume: ${state.volume}")
    }
}

// Commands
DlnaManager.playback.pause()
DlnaManager.playback.setVolume(50)
DlnaManager.playback.seek(Duration.ofMinutes(2))
```

---

## 5. Hosting Local Files (Media Server)

If you set `enableMediaServer = true`, you **must** implement `MediaContentProvider`. This tells the library how to read files from your phone storage.

### **Step 1: Implement `MediaDataSource`**
This handles reading the file bytes. Android's `ContentResolver` is best for this.

```kotlin
class AndroidUriDataSource(
    private val context: Context,
    private val uri: Uri,
    override val size: Long
) : MediaDataSource {

    override fun openFull(): InputStream {
        return context.contentResolver.openInputStream(uri) 
            ?: throw IOException("Cannot open URI")
    }

    override fun openRange(start: Long, length: Long?): InputStream {
        val stream = openFull()
        stream.skip(start)
        // Note: Real implementation should wrap this in a LimitedInputStream 
        // if 'length' is provided, but basic skip works for simple seeking.
        return stream
    }
}
```

### **Step 2: Implement `MediaContentProvider`**
This defines the folder structure the TV sees when it browses your phone.

```kotlin
class MyLocalContentProvider(private val context: Context) : MediaContentProvider {

    // Simple hardcoded structure: Root -> [Video 1, Video 2]
    override suspend fun list(containerId: String): List<MediaObject> {
        if (containerId == "0") { // "0" is always Root
            return listOf(
                MediaItem(
                    id = "local_video_1",
                    parentId = "0",
                    title = "My Vacation",
                    upnpClass = "object.item.videoItem",
                    mediaType = MediaType.VIDEO,
                    resources = listOf(
                        MediaResource(
                            // URI is ignored here; the Library generates the HTTP URL automatically
                            uri = "", 
                            protocolInfo = "http-get:*:video/mp4:*",
                            mimeType = "video/mp4"
                        )
                    )
                )
            )
        }
        return emptyList()
    }

    override fun openMedia(mediaId: String): MediaDataSource {
        if (mediaId == "local_video_1") {
            // In a real app, query MediaStore to get real Uri and Size
            val uri = Uri.parse("content://media/external/video/media/12345")
            val size = 50_000_000L 
            return AndroidUriDataSource(context, uri, size)
        }
        throw FileNotFoundException("Unknown media ID")
    }
}
```

---

## 6. Browsing Remote Servers

If you find a NAS or PC (a device with `ContentDirectory` service), you can browse it.

```kotlin
// 1. Get Root Folder
val rootResult = DlnaManager.browser.browseRoot(deviceId = "uuid:nas-device...")

// 2. Display Folders/Items
rootResult.containers.forEach { folder ->
    println("Folder: ${folder.title} (ID: ${folder.id})")
}
rootResult.items.forEach { file ->
    println("File: ${file.title}")
}

// 3. Drill down into a folder
val subResult = DlnaManager.browser.browse(
    deviceId = "uuid:nas-device...",
    containerId = "folder_id_from_above",
    startIndex = 0,
    count = 50
)
```

---

## 7. Lifecycle Cleanup

When your app closes or the user logs out:

```kotlin
DlnaManager.stop()
```

This releases the Multicast Lock, stops the HTTP Server, and closes all sockets.

---

### **Summary of Key Classes**

| Class | Purpose |
| :--- | :--- |
| **`DlnaManager`** | The main singleton you interact with. |
| **`DlnaConfig`** | Setup options (Server name, Content provider). |
| **`AndroidDlnaPlatform`** | Android-specific networking logic. |
| **`MediaItem`** | Represents a video/song. Used for Playing and Browsing. |
| **`Device`** | Represents a physical TV or Speaker. |
| **`MediaContentProvider`** | Interface you implement to share local files. |