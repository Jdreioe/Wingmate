package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun PlaybackControls(
    onThatThought: () -> Unit,
    onPlay: () -> Unit, 
    onPause: () -> Unit, 
    onStop: () -> Unit, 
    onPlaySecondary: (() -> Unit)? = null,
    onResume: (() -> Unit)? = null,
    isPaused: Boolean = false,
    isSecondarySelectionActive: Boolean = false,
    isSecondaryActionEnabled: Boolean = true,
    isOnThatThoughtActive: Boolean = false,
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
                SmallIconButton(icon = Icons.Rounded.SkipNext, tint = MaterialTheme.colorScheme.primary, onClick = onResume)
            } else {
                SmallIconButton(icon = Icons.Rounded.PlayArrow, tint = MaterialTheme.colorScheme.onSurface, onClick = onPlay)
            }
            
            // Speak using secondary language (if provided)
            SmallIconButton(
                icon = Icons.Rounded.Language,
                tint = if (isSecondarySelectionActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                onClick = { onPlaySecondary?.invoke() },
                selected = isSecondarySelectionActive,
                enabled = isSecondaryActionEnabled && onPlaySecondary != null
            )
            SmallIconButton(icon = Icons.Rounded.Pause, tint = MaterialTheme.colorScheme.onSurface, onClick = onPause)
            SmallIconButton(icon = Icons.Rounded.Stop, tint = MaterialTheme.colorScheme.onSurface, onClick = onStop)
            SmallIconButton(
                icon = Icons.Rounded.MoreVert,
                tint = if (isOnThatThoughtActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                onClick = onThatThought,
                selected = isOnThatThoughtActive
            )
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
