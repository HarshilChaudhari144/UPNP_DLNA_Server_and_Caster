package com.example.mysecondapp

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
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues).padding(16.dp)) {
            Text("Discovered Devices", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 16.dp))

            if (devices.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text("Scanning...")
                    }
                }
            } else {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
    val durationSeconds = playbackState.duration?.inWholeSeconds?.toFloat() ?: 1f
    val positionSeconds = playbackState.position?.inWholeSeconds?.toFloat() ?: 0f

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
        Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Default.Tv, null, Modifier.size(120.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(24.dp))
            Text(playbackState.mediaItem?.title ?: "No Media Playing", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text("State: ${playbackState.transportState}", color = Color.Gray)

            Slider(value = positionSeconds, onValueChange = { viewModel.seekTo(it.toLong()) }, valueRange = 0f..durationSeconds)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(positionSeconds.toLong()))
                Text(formatTime(durationSeconds.toLong()))
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
                title = { Text("Server Settings") },
                navigationIcon = {
                    IconButton(onClick = { viewModel.closeSettings() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                }
            )
        }
    ) { padding ->
        // Use a LazyColumn for the whole screen to accommodate all content
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Item 1: The new battery optimization card
            item {
                BatteryOptimizationCard(viewModel = viewModel)
            }

            // Item 2: The shared folders section
            item {
                Column {
                    Text(
                        "Shared Folders",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                    Text(
                        "Select folders to make visible on your network:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Gray,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                }
            }

            // Items 3+: The list of folders
            items(allFolders) { folder ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.toggleFolderSharing(folder.id) }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = selectedIds.contains(folder.id),
                        onCheckedChange = { viewModel.toggleFolderSharing(folder.id) }
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(folder.title, style = MaterialTheme.typography.bodyLarge)
                }
                Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
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
        Column(Modifier.padding(16.dp).fillMaxWidth()) {
            Text(mediaItem.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))

            if (!showRendererList) {
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
    val m = seconds / 60
    val s = seconds % 60
    return String.format("%02d:%02d", m, s)
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