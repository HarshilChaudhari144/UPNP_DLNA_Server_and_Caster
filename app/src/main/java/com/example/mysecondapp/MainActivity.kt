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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DlnaApp()
        }
    }
}

enum class Screen { DEVICE_LIST, BROWSER }

@Composable
fun DlnaApp() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            PermissionWrapper {
                val viewModel = viewModel<DlnaViewModel>()
                val currentScreen by viewModel.currentScreen.collectAsState()

                // Bottom Sheet State
                val selectedMedia by viewModel.selectedMediaItem.collectAsState()

                Box(modifier = Modifier.fillMaxSize()) {
                    // Main Content
                    when (currentScreen) {
                        Screen.DEVICE_LIST -> DeviceListScreen(viewModel)
                        Screen.BROWSER -> BrowserScreen(viewModel)
                    }

                    // Playback Options Bottom Sheet
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
fun PlayOptionsSheet(
    mediaItem: MediaItem,
    viewModel: DlnaViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val devices by viewModel.devices.collectAsState()

    // Filter for Renderers only (TVs/Speakers)
    val renderers = remember(devices) {
        devices.filter { it.services.any { s -> s.serviceType.contains("AVTransport") } }
    }

    var showRendererList by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
            Text(
                text = mediaItem.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = mediaItem.resources.firstOrNull()?.mimeType ?: "Unknown Type",
                color = Color.Gray
            )
            Divider(modifier = Modifier.padding(vertical = 16.dp))

            if (!showRendererList) {
                // --- Initial Options ---

                // Option 1: Play Locally
                ListItem(
                    headlineContent = { Text("Play Locally") },
                    supportingContent = { Text("Open in VLC / MX Player") },
                    leadingContent = { Icon(Icons.Default.PhoneAndroid, null) },
                    modifier = Modifier.clickable {
                        val uri = Uri.parse(mediaItem.resources.first().uri)
                        val type = mediaItem.resources.first().mimeType
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, type)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        try {
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(context, "No app found to play this content", Toast.LENGTH_SHORT).show()
                        }
                        onDismiss()
                    }
                )

                // Option 2: Cast
                ListItem(
                    headlineContent = { Text("Play on Device (Cast)") },
                    supportingContent = { Text("Stream to TV or Speaker") },
                    leadingContent = { Icon(Icons.Default.Cast, null) },
                    modifier = Modifier.clickable {
                        showRendererList = true
                    }
                )
            } else {
                // --- Renderer Selection List ---
                Text("Select Device", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))

                if (renderers.isEmpty()) {
                    Text("No Renderers found", modifier = Modifier.padding(16.dp), color = Color.Gray)
                }

                LazyColumn(modifier = Modifier.fillMaxHeight(0.5f)) {
                    items(renderers) { device ->
                        ListItem(
                            headlineContent = { Text(device.friendlyName) },
                            leadingContent = { Icon(Icons.Default.Tv, null) },
                            modifier = Modifier.clickable {
                                viewModel.playOnRenderer(device, mediaItem)
                                Toast.makeText(context, "Casting to ${device.friendlyName}...", Toast.LENGTH_SHORT).show()
                                onDismiss()
                            }
                        )
                    }
                }

                Button(
                    onClick = { showRendererList = false },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) {
                    Text("Back")
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

// --- UI: Permission Handling ---
@Composable
fun PermissionWrapper(content: @Composable () -> Unit) {
    var permissionsGranted by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.isEmpty()) permissionsGranted = true
        else permissionsGranted = result.values.all { it }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            launcher.launch(arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES))
        } else {
            permissionsGranted = true
        }
    }

    if (permissionsGranted) {
        content()
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Waiting for permissions...")
        }
    }
}

// --- UI: Device List ---
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
                    DeviceCard(device) { viewModel.openBrowser(device) }
                }
            }
        }
    }
}

@Composable
fun DeviceCard(device: Device, onClick: () -> Unit) {
    val isServer = device.services.any { it.serviceType.contains("ContentDirectory") }
    val isRenderer = device.services.any { it.serviceType.contains("AVTransport") }
    val icon = if (isServer) Icons.Default.Storage else Icons.Default.Tv
    val cardColor = if (isServer) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant

    val typeLabel = when {
        isServer && isRenderer -> "Server & Renderer"
        isRenderer -> "Renderer (TV/Speaker)"
        isServer -> "Media Server"
        else -> "DLNA Device"
    }

    val locationDisplay = remember(device.locationUrl) {
        if (device.locationUrl.isNotEmpty()) device.locationUrl else "Unknown IP"
    }

    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor),
        modifier = Modifier.fillMaxWidth().clickable(enabled = isServer, onClick = onClick)
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(40.dp))
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(text = device.friendlyName, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(text = typeLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                Text(text = locationDisplay, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                if (isServer) Text(text = "Tap to Browse", fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// --- UI: Browser Screen ---
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
                                Text(text = "Protocol: ${info?.protocolInfo}", fontSize = 10.sp, maxLines = 1)
                            }
                        },
                        leadingContent = { Icon(Icons.Default.MusicNote, contentDescription = null) },
                        modifier = Modifier.clickable {
                            // Instead of playing immediately, select it to show options
                            viewModel.selectMedia(file)
                        }
                    )
                    Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        }
    }
}

// --- ViewModel ---

class DlnaViewModel(application: Application) : AndroidViewModel(application) {

    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices.asStateFlow()

    private val _currentScreen = MutableStateFlow(Screen.DEVICE_LIST)
    val currentScreen: StateFlow<Screen> = _currentScreen.asStateFlow()

    private val _browseResult = MutableStateFlow<BrowseResult?>(null)
    val browseResult: StateFlow<BrowseResult?> = _browseResult.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _currentContainerTitle = MutableStateFlow("Root")
    val currentContainerTitle: StateFlow<String> = _currentContainerTitle.asStateFlow()

    // Selection state for Bottom Sheet
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

    // --- Browser Actions ---

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

    // --- Selection & Playback Actions ---

    fun selectMedia(item: MediaItem) {
        _selectedMediaItem.value = item
    }

    fun clearSelection() {
        _selectedMediaItem.value = null
    }

    fun playOnRenderer(renderer: Device, item: MediaItem) {
        viewModelScope.launch {
            try {
                // 1. Connect to Renderer
                DlnaManager.playback.setRenderer(renderer.deviceId)

                // 2. Play
                DlnaManager.playback.play(item)

                // 3. Clear UI selection
                clearSelection()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}