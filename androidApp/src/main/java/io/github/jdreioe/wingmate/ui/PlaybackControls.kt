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
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource

import com.hojmoseit.wingmate.R
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
    val playLabel = stringResource(R.string.playback_play)
    val resumeLabel = stringResource(R.string.playback_resume)
    val pauseLabel = stringResource(R.string.playback_pause)
    val stopLabel = stringResource(R.string.playback_stop)
    val secondaryLabel = stringResource(R.string.playback_secondary_language)
    val thoughtLabel = stringResource(
        if (isOnThatThoughtActive) R.string.playback_previous_thought
        else R.string.playback_new_thought
    )
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
                SmallIconButton(icon = Icons.Rounded.SkipNext, label = resumeLabel, tint = MaterialTheme.colorScheme.primary, onClick = onResume)
            } else {
                SmallIconButton(icon = Icons.Rounded.PlayArrow, label = playLabel, tint = MaterialTheme.colorScheme.onSurface, onClick = onPlay)
            }
            
            if (onPlaySecondary != null) {
                SmallIconButton(
                    icon = Icons.Rounded.Language,
                    label = secondaryLabel,
                    tint = if (isSecondarySelectionActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    onClick = { onPlaySecondary.invoke() },
                    selected = isSecondarySelectionActive,
                    enabled = isSecondaryActionEnabled
                )
            }
            SmallIconButton(icon = Icons.Rounded.Pause, label = pauseLabel, tint = MaterialTheme.colorScheme.onSurface, onClick = onPause)
            SmallIconButton(icon = Icons.Rounded.Stop, label = stopLabel, tint = MaterialTheme.colorScheme.onSurface, onClick = onStop)
            SmallIconButton(
                icon = Icons.Rounded.Bookmark,
                label = thoughtLabel,
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
    label: String,
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
            .size(48.dp)
            .clip(CircleShape)
            .background(color = background),
        contentAlignment = Alignment.Center
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier
                .size(48.dp)
                .semantics {
                    contentDescription = label
                    role = Role.Button
                    this.selected = selected
                }
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = contentTint)
        }
    }
}
