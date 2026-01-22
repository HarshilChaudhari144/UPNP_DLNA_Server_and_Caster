package com.example.mysecondapp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mysecondapp.dlna_lib.api.device.Device
import com.example.mysecondapp.dlna_lib.api.media.MediaItem
import com.example.mysecondapp.dlna_lib.api.playback.TransportState
// Add these imports to the top of the file
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
// Add these imports if they are missing
import java.io.File


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
                        Icon(Icons.Default.Settings, "Server Settings")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // This card correctly handles starting and stopping the local server
            ServerControlCard(viewModel = viewModel)

            HorizontalDivider()
            Text("Discovered Devices", style = MaterialTheme.typography.titleLarge)

            // --- CORRECTED LOGIC ---
            // The client engine is always running, so if the device list is empty,
            // it means we are actively scanning.
            if (devices.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text("Scanning for devices on your network...")
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
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
                Icon(if (isServer) Icons.Default.Storage else Icons.Default.Tv, null, Modifier.size(40.dp))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(device.friendlyName, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(if(isRenderer) "Renderer" else "Server", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    Text("LocationUrl: ${device.locationUrl}", fontSize = 12.sp)
                    Text("Services: ${device.services}", fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isServer) {
                    Button(onClick = onBrowse, Modifier.weight(1f)) {
                        Icon(Icons.Default.Folder, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Browse")
                    }
                }
                if (isRenderer) {
                    FilledTonalButton(onClick = onRemote, Modifier.weight(1f)) {
                        Icon(Icons.Default.SettingsRemote, null)
                        Spacer(Modifier.width(8.dp))
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(browseResult?.containers ?: emptyList()) { folder ->
                    ListItem(
                        headlineContent = { Text(folder.title, fontWeight = FontWeight.Medium) },
                        supportingContent = {
                            Column {
                                Text("ChildCount: ${folder.childCount}", fontSize = 12.sp)
                                Text("Searchable: ${folder.searchable}", fontSize = 12.sp)
                            }
                        },
                        leadingContent = { Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.secondary) },
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
                                Text("Mime: ${info?.mimeType ?: "Unknown"}", fontSize = 12.sp)
                                Text("Dur: ${info?.duration} | Size: ${info?.size}", fontSize = 12.sp)
                                Text("Date: ${file.date}", fontSize = 12.sp)
                                Text("Resources: ${file.resources}", fontSize = 12.sp)
                                Text("Thumbnail: ${file.thumbnail}", fontSize = 12.sp)
                                Text("Uri: ${info?.uri}", fontSize = 12.sp)
                                Text("Res: ${info?.resolution} | Protocol: ${info?.protocolInfo}", fontSize = 12.sp)
                            }
                        },
                        leadingContent = { Icon(Icons.Default.MusicNote, null) },
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
fun RemoteControlScreen(viewModel: DlnaViewModel) {
    val playbackState by viewModel.playbackState.collectAsState()
    val durationSeconds = playbackState.duration?.inWholeSeconds ?: 0L
    val positionSeconds = playbackState.position?.inWholeSeconds ?: 0L

    // State to control the visibility of the numeric input dialog
    var showSeekDialog by remember { mutableStateOf(false) }

    BackHandler { viewModel.closeRemote() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Remote Control") },
                navigationIcon = {
                    IconButton(onClick = { viewModel.closeRemote() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Default.Tv, null, Modifier.size(120.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(24.dp))
            Text(
                playbackState.mediaItem?.title ?: "No Media Playing",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Text("State: ${playbackState.transportState}", color = Color.Gray)

            // Slider remains for quick scrubbing
            Slider(
                value = positionSeconds.toFloat(),
                onValueChange = { viewModel.seekTo(it.toLong()) },
                valueRange = 0f..(if (durationSeconds > 0) durationSeconds.toFloat() else 1f)
            )

            // --- CHANGE: Make the time display clickable to open the numeric seeker ---
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { showSeekDialog = true }
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(formatTime(positionSeconds), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.Gray)
                    Spacer(Modifier.width(4.dp))
                    Text("Tap to jump", fontSize = 12.sp, color = Color.Gray)
                }
                Text(formatTime(durationSeconds))
            }

            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                FilledIconButton(onClick = { viewModel.stop() }) { Icon(Icons.Default.Stop, "Stop") }
                val isPlaying = playbackState.transportState == TransportState.PLAYING
                FilledIconButton(onClick = { if (isPlaying) viewModel.pause() else viewModel.playResume() }, Modifier.size(64.dp)) {
                    Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, Modifier.size(32.dp))
                }
            }
            Spacer(Modifier.height(32.dp))
            Text("Volume: ${playbackState.volume ?: 0}")
            Slider(value = (playbackState.volume ?: 0).toFloat(), onValueChange = { viewModel.setVolume(it.toInt()) }, valueRange = 0f..100f)
        }
    }

    // Logic to show the Dialog
    if (showSeekDialog) {
        SeekTimeDialog(
            initialSeconds = positionSeconds,
            onDismiss = { showSeekDialog = false },
            onConfirm = { totalSeconds ->
                viewModel.seekTo(totalSeconds)
                showSeekDialog = false
            }
        )
    }
}

// --- NEW COMPONENT: Add this to the bottom of DlnaUiComponents.kt ---

@Composable
fun SeekTimeDialog(
    initialSeconds: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit
) {
    val h = (initialSeconds / 3600).toString()
    val m = ((initialSeconds % 3600) / 60).toString()
    val s = (initialSeconds % 60).toString()

    var hours by remember { mutableStateOf(h) }
    var minutes by remember { mutableStateOf(m) }
    var seconds by remember { mutableStateOf(s) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Jump to Time") },
        text = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TimeInput(value = hours, label = "HH", onValueChange = { hours = it.take(2) })
                Text(":", fontWeight = FontWeight.Bold)
                TimeInput(value = minutes, label = "MM", onValueChange = { minutes = it.take(2) })
                Text(":", fontWeight = FontWeight.Bold)
                TimeInput(value = seconds, label = "SS", onValueChange = { seconds = it.take(2) })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val hLong = hours.toLongOrNull() ?: 0L
                val mLong = minutes.toLongOrNull() ?: 0L
                val sLong = seconds.toLongOrNull() ?: 0L
                val total = (hLong * 3600) + (mLong * 60) + sLong
                onConfirm(total)
            }) {
                Text("Seek")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun TimeInput(value: String, label: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.all { char -> char.isDigit() }) onValueChange(it) },
        label = { Text(label) },
        modifier = Modifier.width(65.dp),
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
        )
    )
}

// Replace the existing ServerSettingsScreen with this new one
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerSettingsScreen(viewModel: DlnaViewModel) {
    // FIX 1: Use the correct property name `sharedFolderPaths`
    val sharedFolderPaths by viewModel.sharedFolderPaths.collectAsState()

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { uri ->
            if (uri != null) {
                viewModel.addSharedFolder(uri)
            }
        }
    )

    BackHandler { viewModel.closeSettings() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Server Settings") },
                navigationIcon = {
                    IconButton(onClick = { viewModel.closeSettings() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { folderPickerLauncher.launch(null) },
                icon = { Icon(Icons.Default.Add, "Add Folder") },
                text = { Text("Add Folder") }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                BatteryOptimizationCard(viewModel = viewModel)
            }

            item {
                Column {
                    Text(
                        "Shared Folders",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                    Text(
                        "Add or remove folders to make their content visible on your network.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Gray,
                    )
                }
            }

            if (sharedFolderPaths.isEmpty()) {
                item {
                    Text(
                        "No folders are currently shared. Tap 'Add Folder' to begin.",
                        modifier = Modifier.padding(vertical = 24.dp),
                        textAlign = TextAlign.Center,
                        color = Color.Gray
                    )
                }
            }

            // FIX 2: Iterate over the correct list of path strings
            items(sharedFolderPaths.toList()) { pathString ->
                val displayName = getDisplayNameFromPath(pathString)
                Card(modifier = Modifier.fillMaxWidth()) {
                    ListItem(
                        headlineContent = { Text(displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        // FIX 3: Both `displayName` and `pathString` are now correctly typed as String
                        supportingContent = { Text(pathString, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 12.sp) },
                        leadingContent = { Icon(Icons.Default.Folder, null) },
                        trailingContent = {
                            IconButton(onClick = { viewModel.removeSharedFolder(pathString) }) {
                                Icon(Icons.Default.Delete, "Remove Folder", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    )
                }
            }
        }
    }
}

// Add this new, simpler helper function at the bottom of the file
// (You can delete the old getDisplayNameFromUri function)
private fun getDisplayNameFromPath(path: String): String {
    return path.substringAfterLast('/')
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayOptionsSheet(mediaItem: MediaItem, viewModel: DlnaViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val devices by viewModel.devices.collectAsState()
    val manualSubPath by viewModel.manualSubtitlePath.collectAsState()

    val renderers = remember(devices) {
        devices.filter { it.services.any { s -> s.serviceType.contains("AVTransport") } }
    }
    var showRendererList by remember { mutableStateOf(false) }

    // Launcher for manual subtitle selection
    val subPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            if (uri != null) {
                viewModel.setManualSubtitle(uri)
            }
        }
    )

    ModalBottomSheet(onDismissRequest = {
        viewModel.clearManualSubtitle()
        onDismiss()
    }) {
        Column(Modifier.padding(16.dp).fillMaxWidth()) {
            Text(mediaItem.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

            // Subtitle Selection UI
            Card(
                modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Subtitles, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = if (manualSubPath != null) "Manual Subtitle Attached" else "No manual subtitle",
                            style = MaterialTheme.typography.labelLarge
                        )
                        if (manualSubPath != null) {
                            Text(
                                text = manualSubPath!!.substringAfterLast('/'),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    if (manualSubPath != null) {
                        IconButton(onClick = { viewModel.clearManualSubtitle() }) {
                            Icon(Icons.Default.Clear, "Clear", tint = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        TextButton(onClick = {
                            subPickerLauncher.launch(arrayOf("application/x-subrip", "text/plain", "*/*"))
                        }) {
                            Text("Select File")
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            if (!showRendererList) {
                // ... (Existing "Play Locally" ListItem remains unchanged) ...
                ListItem(
                    headlineContent = { Text("Play Locally") },
                    leadingContent = { Icon(Icons.Default.PhoneAndroid, null) },
                    modifier = Modifier.clickable {
                        val res = mediaItem.resources.first()
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(Uri.parse(res.uri), res.mimeType)
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
                // ... (Existing Renderer List code remains unchanged) ...
                Text("Select Device", style = MaterialTheme.typography.titleMedium)
                LazyColumn(Modifier.fillMaxHeight(0.5f)) {
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
            Spacer(Modifier.height(24.dp))
        }
    }
}

fun formatTime(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) {
        String.format("%02d:%02d:%02d", h, m, s)
    } else {
        String.format("%02d:%02d", m, s)
    }
}

@Composable
fun BatteryOptimizationCard(viewModel: DlnaViewModel) {
    val context = LocalContext.current
    val isIgnoringOptimizations by viewModel.isIgnoringBatteryOptimizations.collectAsState()

    // This effect re-checks the battery optimization status every time the user
    // returns to the app, so the card disappears if they grant the permission.
    val lifecycleOwner = LocalContext.current as androidx.lifecycle.LifecycleOwner
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.checkForBatteryOptimizations()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Only show the card if the app is being optimized by the system.
    if (!isIgnoringOptimizations) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.BatteryAlert,
                        contentDescription = "Battery Alert",
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Action Required",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "To ensure the server runs reliably in the background, please disable battery optimizations for this app.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { viewModel.requestDisableBatteryOptimizations(context) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Open System Settings")
                }
            }
        }
    }
}

@Composable
fun ServerControlCard(viewModel: DlnaViewModel) {
    val isServerRunning by viewModel.isServerRunning.collectAsState()

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text("Local Server", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = if (isServerRunning) "Running" else "Stopped",
                    color = if (isServerRunning) MaterialTheme.colorScheme.primary else Color.Gray,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            if (isServerRunning) {
                Button(
                    onClick = { viewModel.stopServer() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.Stop, contentDescription = "Stop")
                    Spacer(Modifier.width(8.dp))
                    Text("Stop")
                }
            } else {
                Button(onClick = { viewModel.startServer() }) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Start")
                    Spacer(Modifier.width(8.dp))
                    Text("Start")
                }
            }
        }
    }
}