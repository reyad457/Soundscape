package com.soundscape.app.ui.nowplaying

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.soundscape.audio.playback.PlaybackState

@Composable
fun NowPlayingBar(
    state: PlaybackState,
    onTogglePlayPause: () -> Unit
) {
    val track = state.currentTrack ?: return

    Surface(tonalElevation = 4.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(track.title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(track.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }

            // Phase 0 honesty: this bar will only ever show a bit-perfect badge
            // once state.isBitPerfectConfirmed is actually true (Phase 1+).
            if (state.isBitPerfectConfirmed) {
                AssistChip(onClick = {}, label = { Text("Bit-perfect") })
                Spacer(Modifier.width(8.dp))
            }

            IconButton(onClick = onTogglePlayPause) {
                Icon(
                    imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (state.isPlaying) "Pause" else "Play"
                )
            }
        }
    }
}
