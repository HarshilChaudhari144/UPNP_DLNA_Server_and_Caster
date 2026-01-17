# 📱 Kotlin DLNA Hub

Turn your Android Device into a Powerhouse Media Center!

Welcome to **Kotlin DLNA Hub**, a high-performance Android app implementing the UPnP/DLNA protocol. This project allows you to **discover, serve, and control media** across your home network, making any Android device a fully functional media server, controller, and player.

---

## 🌟 Key Features

### 📡 Media Server (DMS)
Transform your phone into a **Digital Media Server**.
* **Live Indexing:** Share your local folders with smart TVs, speakers, or gaming consoles.
* **Adaptive Metadata:** Generates rich DIDL-Lite XML responses so your media displays beautifully.
* **On-the-Fly Streaming:** Built-in HTTP server with Byte-Range support for smooth seeking.

### 🔍 Media Browser & Player (DMP)
Your window into the entire network's library.
* **Network Explorer:** Seamlessly discover and browse other Media Servers (like Plex, Jellyfin, or other PCs) on your Wi-Fi.
* **Local Playback:** Not just for casting—consume media content from remote servers directly on your phone using your favorite local player.
* **Hierarchical Navigation:** Drill down through deep folder structures on remote devices with a responsive, cached browsing interface.

### 📺 Remote Control Point (DMC)
Control playback on any device in your network.
* **Instant Discovery:** SSDP detects all devices in seconds.
* **Universal Casting:** Stream videos, audio, and images from your phone—or even from one server to another renderer.
* **Full Playback Control:** Play, pause, seek, adjust volume, and more with an intuitive UI.

### 📝 Subtitles Support (Fails when Browsing through TV but Works while Casting from App)
Subtitles are supported as part of the media server capabilities:
* Detects common formats like `.srt` and `.vtt`.
* Includes proper metadata for compatible TVs during the casting flow.
* Supports manual subtitle selection for those hard-to-match filenames.

---

## 🏗️ Architecture at a Glance

**Core Library (`dlna_lib`)**
* `core/ssdp`: Handles multicast discovery and device announcements.
* `core/soap`: Processes SOAP requests and responses for device control.
* `core/mediaserver`: Manages media indexing and delivery.
* `core/browse`: Logic for fetching and parsing remote server content.
* `core/playback`: Controls remote playback sessions.

**App (`app`)**
* **Jetpack Compose UI:** Fully reactive interface for browsing devices and media.
* **Foreground Service:** Keeps the server alive in the background.
* **Battery Aware:** Optimized streaming with minimal battery impact.

---

## 🛠️ Technical Highlights

| Component   | Technology         | Purpose                            |
| ----------- | ------------------ | ---------------------------------- |
| Language    | Kotlin             | Modern, safe, expressive code      |
| UI          | Jetpack Compose    | Smooth and responsive interface    |
| Concurrency | Kotlin Coroutines  | High-performance, non-blocking I/O |
| Networking  | Ktor / Raw Sockets | Efficient SSDP & HTTP handling     |
| Parsing     | W3C DOM / Regex    | Robust UPnP XML processing         |

---

## 🚀 Getting Started

1. **Permissions:** Grant storage access so the app can find your media.
2. **Add Folders:** Pick directories to share in Server Settings.
3. **Start Server:** Tap "Start" to make your device visible on the network.
4. **Discover:** Watch as other servers and TVs appear in the device list.
5. **Consume:** Select a remote server to browse its files, then choose to play them locally on your phone or cast them to a TV.

---

## 🎨 Project UI

* **Device List:** Overview of discovered Servers and Renderers on your network.
* **Media Browser:** Explore folders from your local storage or remote network devices.
* **Remote Control:** Elegant interface with sliders and volume wheels for managing your TV.

Built with ❤️ using **Kotlin & DLNA magic**.

Happy Casting! 🍿🎮🎧
