package com.example.mysecondapp

import android.Manifest
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mysecondapp.dlna_lib.android.AndroidDlnaPlatform
import com.example.mysecondapp.dlna_lib.api.DlnaConfig
import com.example.mysecondapp.dlna_lib.api.DlnaManager
import com.example.mysecondapp.dlna_lib.api.browse.BrowseResult
import com.example.mysecondapp.dlna_lib.api.device.Device
import com.example.mysecondapp.dlna_lib.api.media.MediaItem
import com.example.mysecondapp.dlna_lib.api.playback.PlaybackState
import com.example.mysecondapp.dlna_lib.api.playback.TransportState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DlnaApp()
        }
    }
}

enum class Screen { DEVICE_LIST, BROWSER, REMOTE_CONTROL }

@Composable
fun DlnaApp() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            PermissionWrapper {
                val viewModel = viewModel<DlnaViewModel>()
                val currentScreen by viewModel.currentScreen.collectAsState()
                val selectedMedia by viewModel.selectedMediaItem.collectAsState()

                Box(modifier = Modifier.fillMaxSize()) {
                    when (currentScreen) {
                        Screen.DEVICE_LIST -> DeviceListScreen(viewModel)
                        Screen.BROWSER -> BrowserScreen(viewModel)
                        Screen.REMOTE_CONTROL -> RemoteControlScreen(viewModel)
                    }

                    if (selectedMedia != null) {
                        PlayOptionsSheet(
                            mediaItem = selectedMedia!!,
                            viewModel = viewModel,
                            onDismiss = { viewModel.clearSelection() }
                        )
                    }
                }
            }
        }
    }
}

// --- UI: Remote Control Screen (NEW) ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteControlScreen(viewModel: DlnaViewModel) {
    val playbackState by viewModel.playbackState.collectAsState()

    val durationSeconds = playbackState.duration?.inWholeSeconds?.toFloat() ?: 1f
    val positionSeconds = playbackState.position?.inWholeSeconds?.toFloat() ?: 0f

    BackHandler { viewModel.closeRemote() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Remote Control") },
                navigationIcon = {
                    IconButton(onClick = { viewModel.closeRemote() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Default.Tv, null, modifier = Modifier.size(120.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = playbackState.mediaItem?.title ?: "No Media Playing",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            Text(
                text = "State: ${playbackState.transportState}",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Seek Bar
            Slider(
                value = positionSeconds,
                onValueChange = { viewModel.seekTo(it.toLong()) },
                valueRange = 0f..durationSeconds
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(positionSeconds.toLong()))
                Text(formatTime(durationSeconds.toLong()))
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Transport Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledIconButton(onClick = { viewModel.stop() }) {
                    Icon(Icons.Default.Stop, "Stop")
                }

                val isPlaying = playbackState.transportState == TransportState.PLAYING
                FilledIconButton(
                    onClick = { if (isPlaying) viewModel.pause() else viewModel.playResume() },
                    modifier = Modifier.size(64.dp)
                ) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        null,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text("Volume: ${playbackState.volume ?: 0}")
            Slider(
                value = (playbackState.volume ?: 0).toFloat(),
                onValueChange = { viewModel.setVolume(it.toInt()) },
                valueRange = 0f..100f
            )
        }
    }
}

fun formatTime(seconds: Long): String {
    val m = seconds / 60
    val s = seconds % 60
    return String.format("%02d:%02d", m, s)
}

// --- UI: Device List (Updated with Remote Button) ---
@Composable
fun DeviceListScreen(viewModel: DlnaViewModel) {
    val devices by viewModel.devices.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Discovered Devices", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 16.dp))

        if (devices.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text("Scanning...", modifier = Modifier.padding(top = 48.dp))
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(devices) { device ->
                    DeviceCard(
                        device = device,
                        onBrowse = { viewModel.openBrowser(device) },
                        onRemote = { viewModel.connectToRenderer(device) }
                    )
                }
            }
        }
    }
}

@Composable
fun DeviceCard(device: Device, onBrowse: () -> Unit, onRemote: () -> Unit) {
    val isServer = device.services.any { it.serviceType.contains("ContentDirectory") }
    val isRenderer = device.services.any { it.serviceType.contains("AVTransport") }
    val cardColor = if (isServer) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant

    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (isServer) Icons.Default.Storage else Icons.Default.Tv, null, modifier = Modifier.size(40.dp))
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(device.friendlyName, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(if(isRenderer) "Renderer" else "Server", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isServer) {
                    Button(onClick = onBrowse, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Folder, null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Browse")
                    }
                }
                if (isRenderer) {
                    FilledTonalButton(onClick = onRemote, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.SettingsRemote, null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Remote")
                    }
                }
            }
        }
    }
}

// --- ViewModel (Updated with Remote Logic) ---

class DlnaViewModel(application: Application) : AndroidViewModel(application) {

    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices.asStateFlow()

    private val _currentScreen = MutableStateFlow(Screen.DEVICE_LIST)
    val currentScreen: StateFlow<Screen> = _currentScreen.asStateFlow()

    val playbackState: StateFlow<PlaybackState> get() = DlnaManager.playback.playbackState

    // Browser State
    private val _browseResult = MutableStateFlow<BrowseResult?>(null)
    val browseResult: StateFlow<BrowseResult?> = _browseResult.asStateFlow()
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    private val _currentContainerTitle = MutableStateFlow("Root")
    val currentContainerTitle: StateFlow<String> = _currentContainerTitle.asStateFlow()

    // Selection
    private val _selectedMediaItem = MutableStateFlow<MediaItem?>(null)
    val selectedMediaItem: StateFlow<MediaItem?> = _selectedMediaItem.asStateFlow()

    private var currentDeviceId: String? = null
    private var activeContainerId: String = "0"
    private val historyStack = mutableListOf<Pair<String, String>>()

    init {
        startDlna()
    }

    private fun startDlna() {
        val context = getApplication<Application>().applicationContext
        val platform = AndroidDlnaPlatform(context)
        val config = DlnaConfig(false, "Scanner", null, null)
        DlnaManager.start(config, platform)

        viewModelScope.launch {
            DlnaManager.devices.devices.collect { _devices.value = it }
        }
    }

    override fun onCleared() {
        super.onCleared()
        DlnaManager.stop()
    }

    // --- Remote Control ---

    fun connectToRenderer(device: Device) {
        viewModelScope.launch {
            try {
                DlnaManager.playback.setRenderer(device.deviceId)
                _currentScreen.value = Screen.REMOTE_CONTROL
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun closeRemote() {
        _currentScreen.value = Screen.DEVICE_LIST
    }

    fun playResume() {
        // If state is PAUSED, we call Resume. If stopped/unknown but we have an item, try Play.
        val state = playbackState.value.transportState
        val item = playbackState.value.mediaItem

        viewModelScope.launch {
            try {
                if (state == TransportState.PAUSED_PLAYBACK) {
                    DlnaManager.playback.resume()
                } else if (item != null) {
                    DlnaManager.playback.play(item)
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    fun pause() { viewModelScope.launch { try { DlnaManager.playback.pause() } catch(e: Exception) {} } }
    fun stop() { viewModelScope.launch { try { DlnaManager.playback.stop() } catch(e: Exception) {} } }

    fun seekTo(seconds: Long) {
        viewModelScope.launch { try { DlnaManager.playback.seek(seconds.seconds) } catch (e: Exception) {} }
    }

    fun setVolume(vol: Int) {
        viewModelScope.launch { try { DlnaManager.playback.setVolume(vol) } catch(e: Exception) {} }
    }

    // --- Browser ---

    fun openBrowser(device: Device) {
        currentDeviceId = device.deviceId
        historyStack.clear()
        _currentScreen.value = Screen.BROWSER
        loadContainer("0", device.friendlyName)
    }

    fun browse(targetId: String, targetTitle: String) {
        historyStack.add(activeContainerId to _currentContainerTitle.value)
        loadContainer(targetId, targetTitle)
    }

    fun navigateUp() {
        if (historyStack.isNotEmpty()) {
            val (prevId, prevTitle) = historyStack.removeAt(historyStack.lastIndex)
            loadContainer(prevId, prevTitle)
        } else {
            _currentScreen.value = Screen.DEVICE_LIST
            _browseResult.value = null
            currentDeviceId = null
        }
    }

    private fun loadContainer(id: String, title: String) {
        val deviceId = currentDeviceId ?: return
        _isLoading.value = true
        _currentContainerTitle.value = title
        activeContainerId = id

        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    DlnaManager.browser.browse(deviceId, id, 0, 100)
                }
                _browseResult.value = result
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isLoading.value = false
            }
        }
    }

    // --- Selection ---
    fun selectMedia(item: MediaItem) { _selectedMediaItem.value = item }
    fun clearSelection() { _selectedMediaItem.value = null }

    fun playOnRenderer(renderer: Device, item: MediaItem) {
        viewModelScope.launch {
            try {
                DlnaManager.playback.setRenderer(renderer.deviceId)
                DlnaManager.playback.play(item)
                clearSelection()
                _currentScreen.value = Screen.REMOTE_CONTROL
            } catch (e: Exception) { e.printStackTrace() }
        }
    }
}

// ... Keep existing BrowserScreen and PlayOptionsSheet (Same as before) ...
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(viewModel: DlnaViewModel) {
    val browseResult by viewModel.browseResult.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val title by viewModel.currentContainerTitle.collectAsState()

    BackHandler { viewModel.navigateUp() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { viewModel.navigateUp() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                items(browseResult?.containers ?: emptyList()) { folder ->
                    ListItem(
                        headlineContent = { Text(folder.title, fontWeight = FontWeight.Medium) },
                        leadingContent = { Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.secondary) },
                        modifier = Modifier.clickable { viewModel.browse(folder.id, folder.title) }
                    )
                    Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }

                items(browseResult?.items ?: emptyList()) { file ->
                    ListItem(
                        headlineContent = { Text(file.title) },
                        supportingContent = {
                            val info = file.resources.firstOrNull()
                            Column {
                                Text(text = info?.mimeType ?: "Unknown Format", fontSize = 12.sp)
                                Text(text = "Dur: ${info?.duration} | Size: ${info?.size}", fontSize = 12.sp)
                            }
                        },
                        leadingContent = { Icon(Icons.Default.MusicNote, contentDescription = null) },
                        modifier = Modifier.clickable { viewModel.selectMedia(file) }
                    )
                    Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayOptionsSheet(mediaItem: MediaItem, viewModel: DlnaViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val devices by viewModel.devices.collectAsState()
    val renderers = remember(devices) { devices.filter { it.services.any { s -> s.serviceType.contains("AVTransport") } } }
    var showRendererList by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
            Text(mediaItem.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(16.dp))

            if (!showRendererList) {
                ListItem(
                    headlineContent = { Text("Play Locally") },
                    leadingContent = { Icon(Icons.Default.PhoneAndroid, null) },
                    modifier = Modifier.clickable {
                        val uri = Uri.parse(mediaItem.resources.first().uri)
                        val type = mediaItem.resources.first().mimeType
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, type)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        try { context.startActivity(intent) } catch (e: Exception) { Toast.makeText(context, "No app found", Toast.LENGTH_SHORT).show() }
                        onDismiss()
                    }
                )
                ListItem(
                    headlineContent = { Text("Cast to Device") },
                    leadingContent = { Icon(Icons.Default.Cast, null) },
                    modifier = Modifier.clickable { showRendererList = true }
                )
            } else {
                Text("Select Device", style = MaterialTheme.typography.titleMedium)
                LazyColumn(modifier = Modifier.fillMaxHeight(0.5f)) {
                    items(renderers) { device ->
                        ListItem(
                            headlineContent = { Text(device.friendlyName) },
                            leadingContent = { Icon(Icons.Default.Tv, null) },
                            modifier = Modifier.clickable {
                                viewModel.playOnRenderer(device, mediaItem)
                                onDismiss()
                            }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

// --- Permissions ---
@Composable
fun PermissionWrapper(content: @Composable () -> Unit) {
    var permissionsGranted by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (it.isEmpty()) permissionsGranted = true else permissionsGranted = it.values.all { granted -> granted }
    }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) launcher.launch(arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES))
        else permissionsGranted = true
    }
    if (permissionsGranted) content() else Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Waiting for permissions...") }
}