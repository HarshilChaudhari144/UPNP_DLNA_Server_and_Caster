package com.example.mysecondapp

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
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
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mysecondapp.dlna_lib.android.AndroidDlnaPlatform
import com.example.mysecondapp.dlna_lib.api.DlnaConfig
import com.example.mysecondapp.dlna_lib.api.DlnaManager
import com.example.mysecondapp.dlna_lib.api.browse.BrowseResult
import com.example.mysecondapp.dlna_lib.api.device.Device
import com.example.mysecondapp.dlna_lib.api.media.*
import com.example.mysecondapp.dlna_lib.api.playback.PlaybackState
import com.example.mysecondapp.dlna_lib.api.playback.TransportState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.InputStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import android.media.MediaMetadataRetriever

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DlnaApp()
        }
    }
}

enum class Screen { DEVICE_LIST, BROWSER, REMOTE_CONTROL, SERVER_CONFIG }

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
                        Screen.SERVER_CONFIG -> ServerConfigScreen(viewModel)
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

// --- NEW: Server Config Screen ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerConfigScreen(viewModel: DlnaViewModel) {
    val folders by viewModel.sharedFolders.collectAsState()
    val isRunning by viewModel.isServerRunning.collectAsState()

    val folderPicker = rememberLauncherForActivityResult(contract = ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.addSharedFolder(uri)
    }

    BackHandler { viewModel.closeServerConfig() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Local Media Server") },
                navigationIcon = {
                    IconButton(onClick = { viewModel.closeServerConfig() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { folderPicker.launch(null) }) {
                Icon(Icons.Default.Add, "Add Folder")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp)) {
            // Toggle Server
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Server Status", fontWeight = FontWeight.Bold)
                        Text(if (isRunning) "Running" else "Stopped", fontSize = 12.sp)
                    }
                    Switch(checked = isRunning, onCheckedChange = { viewModel.toggleServer(it) })
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text("Shared Folders", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            if (folders.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                    Text("No folders shared yet", color = Color.Gray)
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(folders) { uriStr ->
                        val uri = Uri.parse(uriStr)
                        ListItem(
                            headlineContent = { Text(DocumentFile.fromTreeUri(LocalContext.current, uri)?.name ?: "Unknown Folder") },
                            supportingContent = { Text(uri.toString(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingContent = { Icon(Icons.Default.Folder, null) },
                            trailingContent = {
                                IconButton(onClick = { viewModel.removeSharedFolder(uriStr) }) {
                                    Icon(Icons.Default.Delete, "Remove")
                                }
                            }
                        )
                        Divider()
                    }
                }
            }
        }
    }
}

// --- UI: Remote Control Screen ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteControlScreen(viewModel: DlnaViewModel) {
    // ... (RemoteControlScreen code remains the same) ...
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledIconButton(onClick = { viewModel.stop() }) { Icon(Icons.Default.Stop, "Stop") }

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

// --- UI: Device List ---
@Composable
fun DeviceListScreen(viewModel: DlnaViewModel) {
    // ... (DeviceListScreen code remains the same) ...
    val devices by viewModel.devices.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // Header Row with "Server" button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Devices", fontSize = 24.sp, fontWeight = FontWeight.Bold)

            Button(onClick = { viewModel.openServerConfig() }) {
                Icon(Icons.Default.Settings, null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("My Server")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

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
    // ... (DeviceCard code remains the same) ...
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
                    Text("Location Url: ${device.locationUrl}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    Text("Services: ${device.services}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
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

// --- UI: Browser Screen ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(viewModel: DlnaViewModel) {
    // ... (BrowserScreen code remains the same) ...
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
                                Text("Resources: ${file.resources}", fontSize = 12.sp)
                                Text("Title: ${file.title}", fontSize = 12.sp)
                                Text("Thumbnail: ${file.thumbnail}", fontSize = 12.sp)
                                Text("Media Type: ${file.mediaType}", fontSize = 12.sp)
                                Text("Id: ${file.id}", fontSize = 12.sp)
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
    // ... (PlayOptionsSheet code remains the same) ...
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
    // ... (PermissionWrapper code remains the same) ...
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


// --- ViewModel ---

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

    // Server State
    private val _sharedFolders = MutableStateFlow<List<String>>(emptyList())
    val sharedFolders: StateFlow<List<String>> = _sharedFolders.asStateFlow()

    val isServerRunning: StateFlow<Boolean> get() = DlnaManager.mediaServer.isRunning

    private var currentDeviceId: String? = null
    private var activeContainerId: String = "0"
    private val historyStack = mutableListOf<Pair<String, String>>()

    // Content Provider Implementation
    private val contentProvider: MediaContentProvider

    init {
        val prefs: SharedPreferences = application.getSharedPreferences("dlna_prefs", Context.MODE_PRIVATE)
        val saved = prefs.getStringSet("shared_folders", emptySet()) ?: emptySet()
        _sharedFolders.value = saved.toList()

        contentProvider = AppContentProvider(application, _sharedFolders.value)

        startDlna()
    }

    private fun startDlna() {
        val context = getApplication<Application>().applicationContext
        val platform = AndroidDlnaPlatform(context)

        val config = DlnaConfig(
            enableMediaServer = true,
            serverName = "My Android Server",
            contentProvider = contentProvider,
            thumbnailProvider = null
        )

        DlnaManager.start(config, platform)

        if (_sharedFolders.value.isNotEmpty()) DlnaManager.mediaServer.start()

        viewModelScope.launch {
            DlnaManager.devices.devices.collect { _devices.value = it }
        }
    }

    override fun onCleared() {
        super.onCleared()
        DlnaManager.stop()
    }

    // --- Server Management ---

    fun openServerConfig() {
        _currentScreen.value = Screen.SERVER_CONFIG
    }

    fun closeServerConfig() {
        _currentScreen.value = Screen.DEVICE_LIST
    }

    fun toggleServer(enable: Boolean) {
        if (enable) DlnaManager.mediaServer.start()
        else DlnaManager.mediaServer.stop()
    }

    fun addSharedFolder(uri: Uri) {
        val current = _sharedFolders.value.toMutableList()
        try {
            getApplication<Application>().contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: Exception) { e.printStackTrace() }

        val uriStr = uri.toString()
        if (!current.contains(uriStr)) {
            current.add(uriStr)
            _sharedFolders.value = current
            saveFolders()
            updateProvider()
        }
    }

    fun removeSharedFolder(uriStr: String) {
        val current = _sharedFolders.value.toMutableList()
        current.remove(uriStr)
        _sharedFolders.value = current
        saveFolders()
        updateProvider()
    }

    private fun saveFolders() {
        val prefs = getApplication<Application>().getSharedPreferences("dlna_prefs", Context.MODE_PRIVATE)
        prefs.edit().putStringSet("shared_folders", _sharedFolders.value.toSet()).apply()
    }

    private fun updateProvider() {
        (contentProvider as AppContentProvider).updateFolders(_sharedFolders.value)
        DlnaManager.mediaServer.refreshContent()
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

// --- Content Provider Logic ---
class AppContentProvider(
    private val context: Context,
    initialFolders: List<String>
) : MediaContentProvider {

    private var folders = initialFolders

    fun updateFolders(newFolders: List<String>) {
        folders = newFolders
    }

    override suspend fun list(containerId: String): List<MediaObject> {
        // Root (0) -> List of Folders (Containers)
        if (containerId == "0") {
            return folders.mapIndexed { index, uriStr ->
                val uri = Uri.parse(uriStr)
                val doc = DocumentFile.fromTreeUri(context, uri)
                val name = doc?.name ?: "Folder $index"
                MediaContainer(id = "folder_$index", parentId = "0", title = name, childCount = null)
            }
        }

        // Folder -> List of Files
        if (containerId.startsWith("folder_")) {
            val index = containerId.removePrefix("folder_").toIntOrNull() ?: return emptyList()
            if (index >= folders.size) return emptyList()

            val uriStr = folders[index]
            val rootDoc = DocumentFile.fromTreeUri(context, Uri.parse(uriStr)) ?: return emptyList()

            val items = mutableListOf<MediaItem>()

            rootDoc.listFiles().forEach { file ->
                if (!file.isDirectory && file.type?.startsWith("video") == true) {
                    val id = "file_${index}_${file.name.hashCode()}"

                    val fileUri = file.uri
                    val details = getMediaDetails(fileUri, file.length()) // NEW: Fetch details

                    FileMap.put(id, fileUri)

                    items.add(MediaItem(
                        id = id,
                        parentId = containerId,
                        title = file.name ?: "Unknown",
                        upnpClass = "object.item.videoItem",
                        mediaType = MediaType.VIDEO,
                        resources = listOf(
                            MediaResource(
                                uri = "",
                                protocolInfo = "http-get:*:${file.type}:*",
                                mimeType = file.type ?: "video/mp4",
                                size = details.size,
                                duration = details.duration,
                                resolution = details.resolution
                            )
                        )
                    ))
                }
            }
            return items
        }

        return emptyList()
    }

    override fun openMedia(mediaId: String): MediaDataSource {
        val uri = FileMap.get(mediaId) ?: throw FileNotFoundException("ID $mediaId not found")
        val size = try {
            context.contentResolver.openFileDescriptor(uri, "r")?.statSize ?: 0L
        } catch (e: Exception) { 0L }

        return AndroidUriDataSource(context, uri, size)
    }

    // NEW HELPER: Fetch detailed metadata (Duration/Resolution) for video files
    private fun getMediaDetails(uri: Uri, defaultSize: Long): FileDetails {
        var duration: Duration? = null
        var resolution: String? = null
        var size = defaultSize

        // This tool is the most reliable way to read metadata from a URI/FileDescriptor
        val retriever = MediaMetadataRetriever()

        try {
            // CRITICAL: Set the Data Source using the Content URI
            retriever.setDataSource(context, uri)

            // 1. Get Duration
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val ms = durationStr?.toLongOrNull() ?: 0L
            if (ms > 0) duration = ms.milliseconds

            // 2. Get Resolution
            val widthStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val heightStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)

            val w = widthStr?.toIntOrNull() ?: 0
            val h = heightStr?.toIntOrNull() ?: 0
            if (w > 0 && h > 0) resolution = "${w}x${h}"

            // 3. Get Size (Use the URI size as MediaMetadataRetriever can be flaky for size)
            // No change needed for size here, defaultSize is used or statSize in openMedia.

        } catch (e: Exception) {
            // Log.e("MainActivity", "MediaMetadataRetriever failed: ${e.message}")
            // Log this error, but return null/default values
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) { /* ignore */ }
        }

        Log.d("MainActivity", "size: ${size}, duration: ${duration}, resolution: ${resolution}")
        return FileDetails(size, duration, resolution)
    }
}

data class FileDetails(val size: Long, val duration: Duration?, val resolution: String?)

object FileMap {
    private val map = mutableMapOf<String, Uri>()
    fun put(id: String, uri: Uri) { map[id] = uri }
    fun get(id: String): Uri? = map[id]
}

class AndroidUriDataSource(
    private val context: Context,
    private val uri: Uri,
    override val size: Long
) : MediaDataSource {
    override fun openFull(): InputStream {
        return context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException()
    }

    override fun openRange(start: Long, length: Long?): InputStream {
        // Simple skip is unreliable for large files; requires proper SeekableStream
        // However, this is the standard fallback for non-byte-range streams.
        val fd = context.contentResolver.openFileDescriptor(uri, "r")
        val stream = java.io.FileInputStream(fd!!.fileDescriptor)
        stream.skip(start)
        return stream
    }
}