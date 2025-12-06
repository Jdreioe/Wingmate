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
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun PlaybackControls(
    onPlay: () -> Unit, 
    onPause: () -> Unit, 
    onStop: () -> Unit, 
    onPlaySecondary: (() -> Unit)? = null,
    onResume: (() -> Unit)? = null,
    isPaused: Boolean = false,
    isSecondarySelectionActive: Boolean = false,
    isSecondaryActionEnabled: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Simple row without elevated surface for a more subtle appearance
        Row(
            modifier = Modifier.wrapContentWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Show Resume button if paused, otherwise show Play button
            if (isPaused && onResume != null) {
                SmallIconButton(icon = Icons.Filled.SkipNext, tint = MaterialTheme.colorScheme.primary, onClick = onResume)
            } else {
                SmallIconButton(icon = Icons.Filled.PlayArrow, tint = MaterialTheme.colorScheme.onSurface, onClick = onPlay)
            }
            
            // Speak using secondary language (if provided)
            SmallIconButton(
                icon = Icons.Filled.Language,
                tint = if (isSecondarySelectionActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                onClick = { onPlaySecondary?.invoke() },
                selected = isSecondarySelectionActive,
                enabled = isSecondaryActionEnabled && onPlaySecondary != null
            )
            SmallIconButton(icon = Icons.Filled.Pause, tint = MaterialTheme.colorScheme.onSurface, onClick = onPause)
            SmallIconButton(icon = Icons.Filled.Stop, tint = MaterialTheme.colorScheme.onSurface, onClick = onStop)
        }
    }
}

@Composable
private fun SmallIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    onClick: () -> Unit,
    selected: Boolean = false,
    enabled: Boolean = true
) {
    val background = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        selected -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.3f)
        else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
    }
    val contentTint = if (enabled) tint else tint.copy(alpha = 0.3f)
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(color = background),
        contentAlignment = Alignment.Center
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier
                .size(44.dp)
                .focusProperties { canFocus = false }
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = contentTint)
        }
    }
}
