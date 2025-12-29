package com.example.mysecondapp

import android.Manifest
import android.app.Application
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
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tv
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DlnaApp()
        }
    }
}

// --- Navigation Enum ---
enum class Screen {
    DEVICE_LIST,
    BROWSER
}

@Composable
fun DlnaApp() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            PermissionWrapper {
                val viewModel = viewModel<DlnaViewModel>()
                val currentScreen by viewModel.currentScreen.collectAsState()

                when (currentScreen) {
                    Screen.DEVICE_LIST -> DeviceListScreen(viewModel)
                    Screen.BROWSER -> BrowserScreen(viewModel)
                }
            }
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
        Text(
            text = "Discovered Devices",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        if (devices.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text("Scanning...", modifier = Modifier.padding(top = 48.dp))
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(devices) { device ->
                    DeviceCard(device) {
                        viewModel.openBrowser(device)
                    }
                }
            }
        }
    }
}

@Composable
fun DeviceCard(device: Device, onClick: () -> Unit) {
    // Determine Type
    val isServer = device.services.any { it.serviceType.contains("ContentDirectory") }
    val isRenderer = device.services.any { it.serviceType.contains("AVTransport") }

    // UI Logic
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
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = isServer, onClick = onClick)
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(40.dp))
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(text = device.friendlyName, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(text = typeLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                Text(text = locationDisplay, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                if (isServer) {
                    Text(text = "Tap to Browse", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
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
    val context = LocalContext.current

    // Handle Hardware Back Button
    BackHandler {
        viewModel.navigateUp()
    }

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
                // 1. Folders
                items(browseResult?.containers ?: emptyList()) { folder ->
                    ListItem(
                        headlineContent = { Text(folder.title, fontWeight = FontWeight.Medium) },
                        leadingContent = { Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.secondary) },
                        modifier = Modifier.clickable {
                            viewModel.browse(folder.id, folder.title)
                        }
                    )
                    Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }

                // 2. Files
                items(browseResult?.items ?: emptyList()) { file ->
                    ListItem(
                        headlineContent = { Text(file.title) },
                        supportingContent = {
                            val info = file.resources.firstOrNull()
//                            Text(text = info?.mimeType ?: "Unknown Format", fontSize = 12.sp)
                            Column() {
                                Text(text = info?.mimeType ?: "Unknown Format", fontSize = 12.sp)
                                Text(text = "Duration: ${info?.duration}", fontSize = 12.sp)
                                Text(text = "Uri: ${info?.uri}", fontSize = 12.sp)
                                Text(text = "Resolution: ${info?.resolution}", fontSize = 12.sp)
                                Text(text = "Size: ${info?.size}", fontSize = 12.sp)
                                Text(text = "Protocol Info: ${info?.protocolInfo}", fontSize = 12.sp)
                            }
                        },
                        leadingContent = { Icon(Icons.Default.MusicNote, contentDescription = null) },
                        modifier = Modifier.clickable {
                            val url = file.resources.firstOrNull()?.uri
                            Toast.makeText(context, "URL: $url", Toast.LENGTH_SHORT).show()
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

    // --- State ---
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

    // --- Internal Tracking ---
    private var currentDeviceId: String? = null

    // Tracks the ID of the folder currently displayed on screen
    private var activeContainerId: String = "0"

    // Stack: Pair<ContainerId, ContainerTitle>
    // Stores the history of folders we have visited to enable "Back" navigation
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

    // --- Actions ---

    fun openBrowser(device: Device) {
        currentDeviceId = device.deviceId
        historyStack.clear()

        // Switch Screen
        _currentScreen.value = Screen.BROWSER

        // Load Root
        loadContainer("0", device.friendlyName)
    }

    // Called when User clicks a Folder to drill down
    fun browse(targetId: String, targetTitle: String) {
        // Push the folder we are currently LEAVING to the stack
        historyStack.add(activeContainerId to _currentContainerTitle.value)

        // Load the new folder
        loadContainer(targetId, targetTitle)
    }

    // Called on Back Button
    fun navigateUp() {
        if (historyStack.isNotEmpty()) {
            // Pop the previous folder state
            val (prevId, prevTitle) = historyStack.removeAt(historyStack.lastIndex)
            loadContainer(prevId, prevTitle)
        } else {
            // Stack is empty, exit Browser and return to Device List
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
                // Network call on IO
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
}