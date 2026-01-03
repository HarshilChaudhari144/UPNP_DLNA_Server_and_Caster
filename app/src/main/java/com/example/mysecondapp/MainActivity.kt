package com.example.mysecondapp

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import com.example.mysecondapp.dlna_lib.api.media.MediaContentProvider
import com.example.mysecondapp.dlna_lib.api.media.MediaItem
import com.example.mysecondapp.dlna_lib.api.playback.PlaybackState
import com.example.mysecondapp.dlna_lib.api.playback.TransportState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
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

            PermissionWrapper(onPermissionsGranted = {
                viewModel.initializeClient() // Client engine starts with the UI
            }) {
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

@Composable
fun PermissionWrapper(
    onPermissionsGranted: () -> Unit,
    content: @Composable () -> Unit
) {
    var permissionsGranted by remember { mutableStateOf(false) }
    val requiredPermissions = remember {
        if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(
//                Manifest.permission.NEARBY_WIFI_DEVICES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            arrayOf(
//                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
        }
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) {
            permissionsGranted = true
            onPermissionsGranted()
        }
    }

    LaunchedEffect(Unit) { launcher.launch(requiredPermissions) }

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

class DlnaViewModel(application: Application) : AndroidViewModel(application) {
    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices.asStateFlow()

    private val _currentScreen = MutableStateFlow(Screen.DEVICE_LIST)
    val currentScreen: StateFlow<Screen> = _currentScreen.asStateFlow()

    private val _playbackState = MutableStateFlow(PlaybackState(transportState = TransportState.STOPPED))
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val _browseResult = MutableStateFlow<BrowseResult?>(null)
    val browseResult: StateFlow<BrowseResult?> = _browseResult.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _currentContainerTitle = MutableStateFlow("Root")
    val currentContainerTitle: StateFlow<String> = _currentContainerTitle.asStateFlow()

    private val _selectedMediaItem = MutableStateFlow<MediaItem?>(null)
    val selectedMediaItem: StateFlow<MediaItem?> = _selectedMediaItem.asStateFlow()

    private val _sharedFolderPaths = MutableStateFlow<Set<String>>(emptySet())
    val sharedFolderPaths: StateFlow<Set<String>> = _sharedFolderPaths.asStateFlow()

    private val _isIgnoringBatteryOptimizations = MutableStateFlow(isIgnoringBatteryOptimizations())
    val isIgnoringBatteryOptimizations: StateFlow<Boolean> = _isIgnoringBatteryOptimizations.asStateFlow()

    val isServerRunning: StateFlow<Boolean> = DlnaService.isRunning
    private var dataCollectorJob: Job? = null

    // --- CHANGE: Use the interface type to support any provider ---
    private var contentProvider: MediaContentProvider? = null
    private var currentDeviceId: String? = null
    private var activeContainerId: String = "0"
    private val historyStack = mutableListOf<Pair<String, String>>()
    private var isClientInitialized = false

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val powerManager = getApplication<Application>().getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(getApplication<Application>().packageName)
    }

    fun checkForBatteryOptimizations() {
        _isIgnoringBatteryOptimizations.value = isIgnoringBatteryOptimizations()
    }

    fun requestDisableBatteryOptimizations(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent)
        }
    }

    fun initializeClient() {
        if (isClientInitialized) return
        isClientInitialized = true

        val context = getApplication<Application>().applicationContext
        checkForBatteryOptimizations()

        val prefs = context.getSharedPreferences("dlna_prefs", Context.MODE_PRIVATE)
        val savedFolders = prefs.getStringSet("shared_folders", emptySet()) ?: emptySet()

        _sharedFolderPaths.value = savedFolders
        restartDlnaManager()
    }

    // --- NEW: Centralized function to start/restart the DLNA engine with current settings ---
    private fun restartDlnaManager() {
        // Stop any existing engine first.
        if (DlnaManager.isInitialized()) {
            DlnaManager.stopClientEngine()
        }

        val context = getApplication<Application>().applicationContext
        val prefs = context.getSharedPreferences("dlna_prefs", Context.MODE_PRIVATE)

        // Convert the saved path strings to a set of File objects.
        val rootFiles = _sharedFolderPaths.value.map { File(it) }.toSet()

        // --- CHANGE: Use the new FileSystemContentProvider ---
        val myContentProvider = FileSystemContentProvider(rootFiles)
        this.contentProvider = myContentProvider

        val config = DlnaConfig(
            enableMediaServer = true,
            serverName = "Android (${Build.MODEL})",
            serverUdn = prefs.getString("server_udn", null) ?: UUID.randomUUID().toString().also {
                prefs.edit().putString("server_udn", it).apply()
            },
            contentProvider = myContentProvider // Pass the new provider
        )
        val platform = AndroidDlnaPlatform(context)

        DlnaManager.startClientEngine(config, platform)
        startDataCollectors() // Re-subscribe to data flows from the new engine instance
    }

    private fun startDataCollectors() {
        dataCollectorJob?.cancel()
        dataCollectorJob = viewModelScope.launch {
            while (!DlnaManager.isInitialized()) {
                delay(100)
            }
            launch { DlnaManager.devices.devices.collect { _devices.value = it } }
            launch { DlnaManager.playback.playbackState.collect { _playbackState.value = it } }
        }
    }

    fun startServer() {
        val context = getApplication<Application>().applicationContext
        val intent = Intent(context, DlnaService::class.java).apply { action = DlnaService.ACTION_START }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stopServer() {
        val context = getApplication<Application>().applicationContext
        val intent = Intent(context, DlnaService::class.java).apply { action = DlnaService.ACTION_STOP }
        context.startService(intent)
    }

    fun addSharedFolder(uri: Uri) {
        val path = uriToPath(uri) ?: return // Convert URI to a usable file path

        val currentPaths = _sharedFolderPaths.value.toMutableSet()
        if (currentPaths.add(path)) {
            _sharedFolderPaths.value = currentPaths

            getApplication<Application>().applicationContext
                .getSharedPreferences("dlna_prefs", Context.MODE_PRIVATE)
                .edit().putStringSet("shared_folders", currentPaths).apply()

            // --- CHANGE: Restart the engine to apply the new folder ---
            restartDlnaManager()
        }
    }

    fun removeSharedFolder(path: String) {
        val currentPaths = _sharedFolderPaths.value.toMutableSet()
        if (currentPaths.remove(path)) {
            _sharedFolderPaths.value = currentPaths

            getApplication<Application>().applicationContext
                .getSharedPreferences("dlna_prefs", Context.MODE_PRIVATE)
                .edit().putStringSet("shared_folders", currentPaths).apply()

            // --- CHANGE: Restart the engine to apply the removal ---
            restartDlnaManager()
        }
    }

    private fun uriToPath(uri: Uri): String? {
        if (uri.authority != "com.android.externalstorage.documents") {
            return null
        }
        val docId = uri.pathSegments.last()
        val split = docId.split(":")
        val type = split.getOrNull(0)
        val path = split.getOrNull(1)

        return when (type) {
            "primary" -> {
                val root = Environment.getExternalStorageDirectory().absolutePath
                if (path != null) "$root/$path" else root
            }
            else -> null
        }
    }

    fun openSettings() {
        _currentScreen.value = Screen.SERVER_SETTINGS
    }

    fun closeSettings() { _currentScreen.value = Screen.DEVICE_LIST }

    fun connectToRenderer(device: Device) {
        if (!DlnaManager.isInitialized()) return
        viewModelScope.launch {
            try {
                DlnaManager.playback.setRenderer(device.deviceId)
                _currentScreen.value = Screen.REMOTE_CONTROL
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    fun closeRemote() { _currentScreen.value = Screen.DEVICE_LIST }

    fun playResume() {
        if (!DlnaManager.isInitialized()) return
        val state = playbackState.value.transportState
        val item = playbackState.value.mediaItem
        viewModelScope.launch {
            try {
                if (state == TransportState.PAUSED_PLAYBACK) DlnaManager.playback.resume()
                else if (item != null) DlnaManager.playback.play(item)
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    fun pause() {
        if (!DlnaManager.isInitialized()) return
        viewModelScope.launch { try { DlnaManager.playback.pause() } catch(e: Exception) {} }
    }

    fun stop() {
        if (!DlnaManager.isInitialized()) return
        viewModelScope.launch { try { DlnaManager.playback.stop() } catch(e: Exception) {} }
    }

    fun seekTo(seconds: Long) {
        if (!DlnaManager.isInitialized()) return
        viewModelScope.launch { try { DlnaManager.playback.seek(seconds.seconds) } catch (e: Exception) {} }
    }

    fun setVolume(vol: Int) {
        if (!DlnaManager.isInitialized()) return
        viewModelScope.launch { try { DlnaManager.playback.setVolume(vol) } catch(e: Exception) {} }
    }

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
        if (!DlnaManager.isInitialized()) return

        _isLoading.value = true
        _currentContainerTitle.value = title
        activeContainerId = id
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { DlnaManager.browser.browse(deviceId, id, 0, 100) }
                _browseResult.value = result
            } catch (e: Exception) { e.printStackTrace() } finally { _isLoading.value = false }
        }
    }

    fun selectMedia(item: MediaItem) { _selectedMediaItem.value = item }
    fun clearSelection() { _selectedMediaItem.value = null }

    fun playOnRenderer(renderer: Device, item: MediaItem) {
        if (!DlnaManager.isInitialized()) return
        viewModelScope.launch {
            try {
                DlnaManager.playback.setRenderer(renderer.deviceId)
                DlnaManager.playback.play(item)
                clearSelection()
                _currentScreen.value = Screen.REMOTE_CONTROL
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    override fun onCleared() {
        super.onCleared()
        if (DlnaManager.isInitialized()) {
            DlnaManager.stopClientEngine()
        }
    }
}