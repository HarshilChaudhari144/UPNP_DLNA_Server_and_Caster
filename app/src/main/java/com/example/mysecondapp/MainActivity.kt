package com.example.mysecondapp

import android.Manifest
import android.app.Application
import android.content.Context
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
import com.example.mysecondapp.dlna_lib.api.media.MediaContainer
import com.example.mysecondapp.dlna_lib.api.media.MediaItem
import com.example.mysecondapp.dlna_lib.api.playback.PlaybackState
import com.example.mysecondapp.dlna_lib.api.playback.TransportState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DlnaApp()
        }
    }
}

enum class Screen { DEVICE_LIST, BROWSER, REMOTE_CONTROL, SERVER_SETTINGS }

@Composable
fun DlnaApp() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val viewModel = viewModel<DlnaViewModel>()
            val context = LocalContext.current

            // Pass the start callback to the permission wrapper
            PermissionWrapper(onPermissionsGranted = {
                viewModel.startDlna(context)
            }) {
                // UI Content
                val currentScreen by viewModel.currentScreen.collectAsState()
                val selectedMedia by viewModel.selectedMediaItem.collectAsState()

                Box(modifier = Modifier.fillMaxSize()) {
                    when (currentScreen) {
                        Screen.DEVICE_LIST -> DeviceListScreen(viewModel)
                        Screen.BROWSER -> BrowserScreen(viewModel)
                        Screen.REMOTE_CONTROL -> RemoteControlScreen(viewModel)
                        Screen.SERVER_SETTINGS -> ServerSettingsScreen(viewModel)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerSettingsScreen(viewModel: DlnaViewModel) {
    val allFolders by viewModel.allLocalFolders.collectAsState()
    val selectedIds by viewModel.sharedFolderIds.collectAsState()

    BackHandler { viewModel.closeSettings() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Shared Folders") },
                navigationIcon = {
                    IconButton(onClick = { viewModel.closeSettings() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp)) {
            Text(
                "Select folders to make visible on TV:",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            LazyColumn {
                items(allFolders) { folder ->
                    val isChecked = selectedIds.contains(folder.id)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.toggleFolderSharing(folder.id) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = isChecked,
                            onCheckedChange = { viewModel.toggleFolderSharing(folder.id) }
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(folder.title, style = MaterialTheme.typography.bodyLarge)
                    }
                    Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        }
    }
}

// --- Updated Permission Wrapper ---
@Composable
fun PermissionWrapper(
    onPermissionsGranted: () -> Unit,
    content: @Composable () -> Unit
) {
    var permissionsGranted by remember { mutableStateOf(false) }

    // Determine permissions based on Android Version
    val requiredPermissions = remember {
        if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(
                Manifest.permission.NEARBY_WIFI_DEVICES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.READ_MEDIA_IMAGES
            )
        } else {
            // Android 12 and below need Location for SSDP and Storage for files
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
        }
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val allGranted = result.values.all { it }
        if (allGranted) {
            permissionsGranted = true
            onPermissionsGranted()
        }
    }

    LaunchedEffect(Unit) {
        launcher.launch(requiredPermissions)
    }

    if (permissionsGranted) {
        content()
    } else {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text("Requesting Permissions...")
                Text("(Storage needed to serve files)", fontSize = 12.sp, color = Color.Gray)
            }
        }
    }
}

// --- ViewModel (Updated Logic) ---

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

    // --- NEW: Server Settings State ---
    private val _allLocalFolders = MutableStateFlow<List<MediaContainer>>(emptyList())
    val allLocalFolders: StateFlow<List<MediaContainer>> = _allLocalFolders.asStateFlow()

    private val _sharedFolderIds = MutableStateFlow<Set<String>>(emptySet())
    val sharedFolderIds: StateFlow<Set<String>> = _sharedFolderIds.asStateFlow()

    private var contentProvider: MediaStoreContentProvider? = null

    private var currentDeviceId: String? = null
    private var activeContainerId: String = "0"
    private val historyStack = mutableListOf<Pair<String, String>>()
    private var isStarted = false

    // Removed init block to prevent starting before permissions

    fun startDlna(context: Context) {
        if (isStarted) return
        isStarted = true

        val platform = AndroidDlnaPlatform(context)

        // Load Saved Shared Folders
        val prefs = context.getSharedPreferences("dlna_prefs", Context.MODE_PRIVATE)
        val savedFolders = prefs.getStringSet("shared_folders", emptySet()) ?: emptySet()
        _sharedFolderIds.value = savedFolders

        // Init Provider
        val myContentProvider = MediaStoreContentProvider(context)
        myContentProvider.setAllowedFolders(savedFolders)
        this.contentProvider = myContentProvider

        // Generate UDN
        var serverUdn = prefs.getString("server_udn", null)
        if (serverUdn == null) {
            serverUdn = UUID.randomUUID().toString()
            prefs.edit().putString("server_udn", serverUdn).apply()
        }

        val config = DlnaConfig(
            enableMediaServer = true,
            serverName = "Android (${Build.MODEL})",
            serverUdn = serverUdn!!,
            contentProvider = myContentProvider
        )

        DlnaManager.start(config, platform)

        viewModelScope.launch {
            DlnaManager.devices.devices.collect { _devices.value = it }
        }
    }

    fun openSettings() {
        viewModelScope.launch(Dispatchers.IO) {
            val folders = contentProvider?.getAllFolders() ?: emptyList()
            withContext(Dispatchers.Main) {
                _allLocalFolders.value = folders
                _currentScreen.value = Screen.SERVER_SETTINGS
            }
        }
    }

    fun closeSettings() {
        _currentScreen.value = Screen.DEVICE_LIST
    }

    fun toggleFolderSharing(folderId: String) {
        val current = _sharedFolderIds.value.toMutableSet()
        if (current.contains(folderId)) {
            current.remove(folderId)
        } else {
            current.add(folderId)
        }
        _sharedFolderIds.value = current

        // Update Provider
        contentProvider?.setAllowedFolders(current)

        // Persist
        val context = getApplication<Application>().applicationContext
        val prefs = context.getSharedPreferences("dlna_prefs", Context.MODE_PRIVATE)
        prefs.edit().putStringSet("shared_folders", current).apply()

        // Notify library to refresh (Optional: DlnaManager.mediaServer.refreshContent())
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

// ... UI Composable for RemoteControlScreen, DeviceListScreen, DeviceCard, BrowserScreen, PlayOptionsSheet ...
// (These remain exactly the same as your previous code, no changes needed there)
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


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceListScreen(viewModel: DlnaViewModel) {
    val devices by viewModel.devices.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("DLNA App") },
                actions = {
                    IconButton(onClick = { viewModel.openSettings() }) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Server Settings"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            Text(
                text = "Discovered Devices",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            if (devices.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Scanning...")
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
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
                    Text(text = "LocationUrl: ${device.locationUrl}", fontSize = 12.sp)
                    Text(text = "Services: ${device.services}", fontSize = 12.sp)
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
                                Text(text = "Resources: ${file.resources}", fontSize = 12.sp)
                                Text(text = "Thumbnail: ${file.thumbnail}", fontSize = 12.sp)
                                Text(text = "Uri: ${info?.uri}", fontSize = 12.sp)
                                Text(text = "Resolution: ${info?.resolution}", fontSize = 12.sp)
                                Text(text = "ProtocolInfo: ${info?.protocolInfo}", fontSize = 12.sp)
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



// ... Keep RemoteControlScreen, DeviceListScreen, DeviceCard, BrowserScreen, PlayOptionsSheet ...
// Just ensure you include the 'RemoteControlScreen' and 'DeviceListScreen' UI code blocks I didn't repeat here to save space,
// as they were correct in your previous message.