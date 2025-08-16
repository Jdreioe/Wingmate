package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Language
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun PlaybackControls(onPlay: () -> Unit, onPause: () -> Unit, onStop: () -> Unit, onPlaySecondary: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Container surface to match M3 inset style
        Surface(
            modifier = Modifier.wrapContentWidth(),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            shape = MaterialTheme.shapes.medium
        ) {
            Row(modifier = Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SmallIconButton(icon = Icons.Filled.PlayArrow, tint = MaterialTheme.colorScheme.onSurface, onClick = onPlay)
                // Speak using secondary language (if provided)
                SmallIconButton(icon = Icons.Filled.Language, tint = MaterialTheme.colorScheme.onSurface, onClick = { onPlaySecondary?.invoke() })
                SmallIconButton(icon = Icons.Filled.Pause, tint = MaterialTheme.colorScheme.onSurface, onClick = onPause)
                SmallIconButton(icon = Icons.Filled.Stop, tint = MaterialTheme.colorScheme.onSurface, onClick = onStop)
            }
        }
    }
}

@Composable
private fun SmallIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)),
        contentAlignment = Alignment.Center
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(44.dp)) {
            Icon(imageVector = icon, contentDescription = null, tint = tint)
        }
    }
}
