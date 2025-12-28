package com.example.mysecondapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.mysecondapp.dlna_lib.DlnaConfig
import com.example.mysecondapp.dlna_lib.DlnaManager
import com.example.mysecondapp.dlna_lib.android.AndroidDlnaPlatform
import com.example.mysecondapp.dlna_lib.android.AndroidLocalContentProvider
import com.example.mysecondapp.dlna_lib.api.BrowseResult
import com.example.mysecondapp.dlna_lib.core.models.Device
import com.example.mysecondapp.dlna_lib.core.models.MediaContainer
import com.example.mysecondapp.dlna_lib.core.models.MediaItem
import com.example.mysecondapp.dlna_lib.core.models.MediaObject
import com.example.mysecondapp.dlna_lib.core.models.TransportState
import com.example.mysecondapp.ui.theme.MySecondAppTheme
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds


class MainActivity : ComponentActivity() {

    // Keep reference to provider
    private lateinit var localContentProvider: AndroidLocalContentProvider

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 1. Initialize the Local Content Provider
        localContentProvider = AndroidLocalContentProvider(applicationContext, "Videos")

        // 2. Pass it to the Config
        val config = DlnaConfig(
            enableMediaServer = true, // Must be true for Phase 9
            serverName = "My Android App",
            contentProvider = localContentProvider, // Inject provider here
            thumbnailProvider = null
        )

        val platform = AndroidDlnaPlatform(applicationContext)
        DlnaManager.start(config, platform)

        setContent {
            MySecondAppTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    // Pass provider to the UI
                    MainScreen(
                        modifier = Modifier.padding(innerPadding),
                        localProvider = localContentProvider
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        DlnaManager.stop()
    }
}

// Helper class to track navigation history
data class BrowseHistoryItem(val title: String, val containerId: String)

@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    localProvider: AndroidLocalContentProvider
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Devices", "Local Files", "Now Playing")

    Column(modifier = modifier.fillMaxSize()) {
        // --- TOP TAB ROW ---
        TabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title) },
                    icon = {
                        when (index) {
                            0 -> Icon(Icons.Default.Devices, null)
                            1 -> Icon(Icons.Default.VideoLibrary, null)
                            2 -> Icon(Icons.Default.PlayCircle, null)
                        }
                    }
                )
            }
        }

        // --- TAB CONTENT ---
        Box(modifier = Modifier.weight(1f)) {
            when (selectedTab) {
                0 -> DeviceScannerTab()
                1 -> LocalFilesTab(localProvider) // New Phase 9 Tab
                2 -> NowPlayingTab() // Moved Remote Control here
            }
        }
    }
}

// --- TAB 1: DEVICE SCANNER (Existing Logic) ---
@Composable
fun DeviceScannerTab() {
    val devices by DlnaManager.devices.devices.collectAsStateWithLifecycle(initialValue = emptyList())

    // Global Connect State (Shared via Manager, but tracked locally for UI feedback)
    // We'll trust the DlnaManager.playback internally knows the UDN.
    // We just track the name here for UI convenience.
    var connectedRendererName by remember { mutableStateOf<String?>(null) }

    // State for Browsing Remote Servers
    var currentDeviceId by remember { mutableStateOf<String?>(null) }
    var currentContent by remember { mutableStateOf<BrowseResult?>(null) }
    var navigationStack by remember { mutableStateOf(listOf<BrowseHistoryItem>()) }
    var isLoading by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Helper to load a specific folder
    fun loadFolder(deviceId: String, containerId: String) {
        scope.launch {
            isLoading = true
            val result = DlnaManager.browser.browse(deviceId, containerId, 0, 100)
            currentContent = result
            isLoading = false

            if (result.totalMatches == 0 && result.containers.isEmpty() && result.items.isEmpty()) {
                Toast.makeText(context, "Folder is empty", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Handle Back Button Logic inside the tab
    BackHandler(enabled = currentDeviceId != null) {
        if (navigationStack.size > 1) {
            val newStack = navigationStack.dropLast(1)
            navigationStack = newStack
            val parent = newStack.last()
            loadFolder(currentDeviceId!!, parent.containerId)
        } else {
            currentDeviceId = null
            currentContent = null
            navigationStack = emptyList()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Breadcrumb / Header
        if (currentDeviceId == null) {
            Column(Modifier.padding(16.dp)) {
                Text(text = "Discovered Devices: ${devices.size}", style = MaterialTheme.typography.titleMedium)
                if (connectedRendererName != null) {
                    Text("Connected to: $connectedRendererName", color = MaterialTheme.colorScheme.primary)
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                IconButton(onClick = {
                    if (navigationStack.size > 1) {
                        val newStack = navigationStack.dropLast(1)
                        navigationStack = newStack
                        loadFolder(currentDeviceId!!, newStack.last().containerId)
                    } else {
                        currentDeviceId = null
                        currentContent = null
                        navigationStack = emptyList()
                    }
                }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                }
                Text(
                    text = navigationStack.lastOrNull()?.title ?: "Browsing",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        HorizontalDivider()

        Box(modifier = Modifier.weight(1f)) {
            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (currentDeviceId != null && currentContent != null) {
                // BROWSE REMOTE FILES
                BrowseResultList(
                    result = currentContent!!,
                    onItemClick = { item ->
                        Toast.makeText(context, "Casting: ${item.title}", Toast.LENGTH_SHORT).show()
                        scope.launch { DlnaManager.playback.play(item) }
                    },
                    onContainerClick = { container ->
                        navigationStack = navigationStack + BrowseHistoryItem(container.title, container.id)
                        loadFolder(currentDeviceId!!, container.id)
                    }
                )
            } else {
                // DEVICE LIST
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(16.dp)
                ) {
                    items(devices) { device ->
                        DeviceCard(
                            device = device,
                            isSelected = device.friendlyName == connectedRendererName,
                            onBrowseClick = {
                                currentDeviceId = device.udn
                                navigationStack = listOf(BrowseHistoryItem(device.friendlyName, "0"))
                                loadFolder(device.udn, "0")
                            },
                            onConnectClick = {
                                connectedRendererName = device.friendlyName
                                scope.launch {
                                    DlnaManager.playback.setRenderer(device.udn)
                                    Toast.makeText(context, "Connected to ${device.friendlyName}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

// --- TAB 2: LOCAL FILES (Phase 9 New Feature) ---
@Composable
fun LocalFilesTab(provider: AndroidLocalContentProvider) {
    var videos by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var hasPermission by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Permission Launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasPermission = isGranted
        if (isGranted) {
            scope.launch {
                val items = provider.list("video-root")
                videos = items.filterIsInstance<MediaItem>()
            }
        }
    }

    LaunchedEffect(Unit) {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (androidx.core.content.ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED) {
            hasPermission = true
            scope.launch {
                val items = provider.list("video-root")
                videos = items.filterIsInstance<MediaItem>()
            }
        } else {
            permissionLauncher.launch(perm)
        }
    }

    if (!hasPermission) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Permission needed to load videos.")
                Button(onClick = {
                    val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        Manifest.permission.READ_MEDIA_VIDEO
                    } else {
                        Manifest.permission.READ_EXTERNAL_STORAGE
                    }
                    permissionLauncher.launch(perm)
                }) {
                    Text("Grant Permission")
                }
            }
        }
    } else {
        if (videos.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No videos found on this device.")
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(videos) { video ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                // MAGIC: Cast local file!
                                Toast.makeText(context, "Serving & Casting: ${video.title}", Toast.LENGTH_SHORT).show()
                                scope.launch { DlnaManager.playback.play(video) }
                            },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Movie, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text(video.title, fontWeight = FontWeight.SemiBold)
                                Text(formatDuration(video.duration ?: Duration.ZERO), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

// --- TAB 3: NOW PLAYING (Remote Control) ---
@Composable
fun NowPlayingTab() {
    val playbackState by DlnaManager.playback.playbackState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (playbackState.transportState == TransportState.STOPPED) {
            Icon(Icons.Default.TvOff, null, modifier = Modifier.size(100.dp), tint = Color.LightGray)
            Spacer(modifier = Modifier.height(16.dp))
            Text("No Media Playing", style = MaterialTheme.typography.headlineMedium, color = Color.Gray)
        } else {
            // Media Title
            Text(
                text = playbackState.mediaItem?.title ?: "Unknown Title",
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = playbackState.transportState.name,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(32.dp))

            // --- SEEK BAR ---
            val currentPos = playbackState.position ?: Duration.ZERO
            val totalDur = playbackState.duration ?: Duration.ZERO
            val maxVal = if (totalDur.inWholeSeconds > 0) totalDur.inWholeSeconds.toFloat() else 1f

            var seekPos by remember { mutableFloatStateOf(0f) }
            var isSeeking by remember { mutableStateOf(false) }

            // Sync Slider
            val networkPos = currentPos.inWholeSeconds.toFloat()
            LaunchedEffect(networkPos) {
                if (!isSeeking) seekPos = networkPos
            }

            Slider(
                value = seekPos,
                valueRange = 0f..maxVal,
                onValueChange = {
                    isSeeking = true
                    seekPos = it
                },
                onValueChangeFinished = {
                    isSeeking = false
                    scope.launch { DlnaManager.playback.seek(seekPos.toLong().seconds) }
                }
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatDuration(currentPos))
                Text(formatDuration(totalDur))
            }

            Spacer(modifier = Modifier.height(32.dp))

            // --- CONTROLS ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Stop
                FilledTonalIconButton(
                    onClick = { scope.launch { DlnaManager.playback.stop() } },
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(Icons.Default.Stop, "Stop")
                }

                // Play/Pause
                val isPlaying = playbackState.transportState == TransportState.PLAYING
                Button(
                    onClick = {
                        scope.launch {
                            if (isPlaying) DlnaManager.playback.pause()
                            else DlnaManager.playback.play(playbackState.mediaItem ?: return@launch)
                        }
                    },
                    modifier = Modifier.size(80.dp),
                    shape = MaterialTheme.shapes.large
                ) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play/Pause",
                        modifier = Modifier.size(40.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // --- VOLUME ---
            var sliderVolume by remember { mutableFloatStateOf(0f) }
            var isDraggingVolume by remember { mutableStateOf(false) }
            val realVolume = playbackState.volume?.toFloat() ?: 0f

            LaunchedEffect(realVolume) {
                if (!isDraggingVolume) sliderVolume = realVolume
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.VolumeDown, "Vol Down")
                Spacer(modifier = Modifier.width(8.dp))
                Slider(
                    value = sliderVolume,
                    valueRange = 0f..100f,
                    onValueChange = {
                        isDraggingVolume = true
                        sliderVolume = it
                        scope.launch { DlnaManager.playback.setVolume(it.toInt()) }
                    },
                    onValueChangeFinished = { isDraggingVolume = false },
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Icon(Icons.Default.VolumeUp, "Vol Up")
            }
        }
    }
}

// --- SHARED COMPONENTS (Preserved) ---

// Format Duration helper
fun formatDuration(duration: Duration): String {
    val totalSeconds = duration.inWholeSeconds
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) String.format("%d:%02d:%02d", hours, minutes, seconds)
    else String.format("%02d:%02d", minutes, seconds)
}

@Composable
fun BrowseResultList(
    result: BrowseResult,
    onItemClick: (MediaItem) -> Unit,
    onContainerClick: (MediaContainer) -> Unit
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(16.dp)) {
        items(result.containers) { container ->
            ListItem(
                headlineContent = { Text(container.title, fontWeight = FontWeight.Bold) },
                supportingContent = { Text("Folder (${container.childCount ?: "?"} items)") },
                leadingContent = { Icon(Icons.Default.Folder, contentDescription = null, tint = Color(0xFFFFC107)) },
                modifier = Modifier.clickable { onContainerClick(container) }
            )
            HorizontalDivider()
        }
        items(result.items) { item ->
            ListItem(
                headlineContent = { Text(item.title) },
//                supportingContent = { Text("Remote File") },
                supportingContent = {Column(){
                    Text("Remote File")
                    Text("Thumbnail: ${item.thumbnail?.uri}")
                    Text("Duration: ${item.duration}")
                    Text("Resources: ${item.resources}")
                } },
                leadingContent = { Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.primary) },
                modifier = Modifier.clickable { onItemClick(item) }
            )
            HorizontalDivider()
        }
    }
}


@Composable
fun DeviceCard(
    device: Device,
    isSelected: Boolean,
    onBrowseClick: () -> Unit,
    onConnectClick: () -> Unit
) {
    val isMediaServer = device.deviceType.contains("MediaServer", ignoreCase = true)
    val isRenderer = device.deviceType.contains("MediaRenderer", ignoreCase = true)

    val host = remember(device.locationUrl) {
        try { java.net.URL(device.locationUrl).host } catch (e: Exception) { "Unknown" }
    }

    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = if (isSelected) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.cardColors(),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isMediaServer) Icons.Default.Folder else Icons.Default.Tv,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = device.friendlyName, style = MaterialTheme.typography.titleMedium)
                    if (!device.modelName.isNullOrEmpty()) {
                        Text(text = "${device.manufacturer ?: ""} ${device.modelName}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isMediaServer) {
                    Button(onClick = onBrowseClick, modifier = Modifier.weight(1f)) { Text("Browse Files") }
                }
                if (isRenderer) {
                    Button(
                        onClick = onConnectClick,
                        modifier = Modifier.weight(1f),
                        enabled = !isSelected
                    ) {
                        Text(if (isSelected) "Connected" else "Connect Remote")
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = "IP: $host", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        }
    }
}