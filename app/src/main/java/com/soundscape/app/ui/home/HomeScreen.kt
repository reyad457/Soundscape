package com.soundscape.app.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.soundscape.analysis.FidelityScanner
import com.soundscape.app.ui.nowplaying.NowPlayingBar

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(viewModel: HomeViewModel = hiltViewModel()) {
    val tracks by viewModel.tracks.collectAsState()
    val playback by viewModel.playback.collectAsState()
    val usbDevices by viewModel.usbDevices.collectAsState()
    val eqPreset by viewModel.activeEqPreset.collectAsState()
    val fidelityResult by viewModel.fidelityResult.collectAsState()
    val fidelityScanError by viewModel.fidelityScanError.collectAsState()
    val scanningTrackId by viewModel.fidelityScanInProgress.collectAsState()

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Soundscape") },
                    actions = {
                        // Phase 3 minimal hook: cycles EQ presets so the DSP
                        // chain is actually testable end to end. A real
                        // per-band editor screen is future UI work — see
                        // HomeViewModel.cycleEqPreset's kdoc.
                        TextButton(onClick = { viewModel.cycleEqPreset() }) {
                            Icon(Icons.Filled.Tune, contentDescription = "Cycle EQ preset")
                            Spacer(Modifier.width(4.dp))
                            Text(eqPreset.name.lowercase().replaceFirstChar { it.uppercase() })
                        }
                        IconButton(onClick = { viewModel.rescanLibrary() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Rescan library")
                        }
                    }
                )
                usbDevices.firstOrNull()?.let { device ->
                    UsbDacBanner(device = device, onRequestPermission = {
                        viewModel.requestUsbPermission(device.deviceName)
                    })
                }
            }
        },
        bottomBar = {
            if (playback.currentTrack != null) {
                NowPlayingBar(
                    state = playback,
                    onTogglePlayPause = { viewModel.togglePlayPause() }
                )
            }
        }
    ) { padding ->
        if (tracks.isEmpty()) {
            EmptyLibraryState(
                modifier = Modifier.padding(padding),
                onScan = { viewModel.rescanLibrary() }
            )
        } else {
            LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
                items(tracks, key = { it.id }) { track ->
                    ListItem(
                        headlineContent = { Text(track.title) },
                        supportingContent = { Text("${track.artist} — ${track.album}") },
                        trailingContent = {
                            if (scanningTrackId == track.id) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Text(track.format.name, style = MaterialTheme.typography.labelSmall)
                            }
                        },
                        modifier = Modifier.combinedClickable(
                            onClick = { viewModel.play(track) },
                            // Phase 4 minimal hook: long-press to run a fidelity
                            // scan, same "just enough UI to test it" scope as
                            // Phase 1's USB banner and Phase 3's preset button.
                            // A dedicated per-track details screen is future UI work.
                            onLongClick = { viewModel.scanFidelity(track) }
                        )
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    if (fidelityResult != null || fidelityScanError != null) {
        FidelityResultDialog(
            result = fidelityResult,
            error = fidelityScanError,
            onDismiss = { viewModel.dismissFidelityResult() }
        )
    }
}

@Composable
private fun FidelityResultDialog(
    result: FidelityScanner.FidelityResult?,
    error: String?,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Fidelity Scan") },
        text = {
            if (error != null) {
                Text(error)
            } else if (result != null) {
                Column {
                    Text("Integrated loudness: %.1f LUFS".format(result.integratedLoudnessLufs))
                    Text("Loudness range: %.1f LU".format(result.loudnessRangeLu))
                    Text("True peak: %.1f dBTP".format(result.truePeakDbtp))
                }
            }
        }
    )
}

@Composable
private fun UsbDacBanner(
    device: com.soundscape.usb.UsbAudioDevice,
    onRequestPermission: () -> Unit
) {
    Surface(tonalElevation = 2.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    device.productName ?: "USB Audio Device",
                    style = MaterialTheme.typography.labelLarge
                )
                Text(
                    when {
                        !device.hasPermission -> "Tap to allow access"
                        !device.isRecognizedAudioInterface -> "Connected — format not recognized, using standard output"
                        else -> "Ready for exclusive-mode playback"
                    },
                    style = MaterialTheme.typography.labelSmall
                )
            }
            if (!device.hasPermission) {
                TextButton(onClick = onRequestPermission) { Text("Allow") }
            }
        }
    }
}

@Composable
private fun EmptyLibraryState(modifier: Modifier = Modifier, onScan: () -> Unit) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("No tracks yet", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Scan your device for local audio to get started.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onScan) { Text("Scan library") }
    }
}
