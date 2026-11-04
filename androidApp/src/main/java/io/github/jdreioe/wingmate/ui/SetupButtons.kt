package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hojmoseit.wingmate.R

// Setup's buttons: one look per role, so a Communicator learns them once. Every button is
// at least 56dp tall and full width (or shares a row), which suits touch, dwell and switch.

/** Leaves the current page. */
@Composable
internal fun SetupBackButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Text(stringResource(R.string.common_back))
    }
}

/** Moves setup forward: Continue, Save and continue, and the like. Shows progress while [busy]. */
@Composable
internal fun SetupPrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
) {
    Button(onClick = onClick, enabled = enabled && !busy, modifier = modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
        }
        Text(label)
    }
}

/** Any other action on a setup page. [opensBrowser] marks links that leave Wingmate. */
@Composable
internal fun SetupActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    opensBrowser: Boolean = false,
    /** Decorative leading icon; the label carries the meaning. */
    icon: ImageVector? = null,
) {
    FilledTonalButton(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        icon?.let {
            Icon(it, contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(label)
        if (opensBrowser) {
            Spacer(Modifier.width(8.dp))
            // Decorative: screen readers hear the label; the browser opening is announced by the system.
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(20.dp))
        }
    }
}

/** A way out that should not compete with the page's actions: Leave setup, Restore a backup. */
@Composable
internal fun SetupQuietButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    TextButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 56.dp)) {
        Text(label)
    }
}

/** Back and the primary action, pinned in the same place on every setup page. Either may be absent. */
@Composable
internal fun SetupBottomBar(
    onBack: (() -> Unit)?,
    primaryLabel: String?,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
    backEnabled: Boolean = true,
    primaryEnabled: Boolean = true,
    primaryBusy: Boolean = false,
) {
    if (onBack == null && primaryLabel == null) return
    Row(modifier = modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        if (onBack != null) {
            SetupBackButton(onClick = onBack, enabled = backEnabled, modifier = Modifier.weight(1f))
        }
        if (primaryLabel != null) {
            SetupPrimaryButton(
                label = primaryLabel,
                onClick = onPrimary,
                enabled = primaryEnabled,
                busy = primaryBusy,
                modifier = Modifier.weight(if (onBack != null) 2f else 1f),
            )
        }
    }
}
