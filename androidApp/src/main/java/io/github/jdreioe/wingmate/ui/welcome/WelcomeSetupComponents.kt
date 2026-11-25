package io.github.jdreioe.wingmate.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hojmoseit.wingmate.R
import io.github.jdreioe.wingmate.application.BackupRestoreResult
import io.github.jdreioe.wingmate.application.CompleteBackupManager
import io.github.jdreioe.wingmate.application.FeatureUsageEvents
import io.github.jdreioe.wingmate.application.FeatureUsageReporter
import io.github.jdreioe.wingmate.application.PhraseUseCase
import io.github.jdreioe.wingmate.application.SettingsUseCase
import io.github.jdreioe.wingmate.application.VoiceUseCase
import io.github.jdreioe.wingmate.application.reportEvent
import io.github.jdreioe.wingmate.domain.ConfigRepository
import io.github.jdreioe.wingmate.domain.OperationalLogger
import io.github.jdreioe.wingmate.domain.Phrase
import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.domain.Settings
import io.github.jdreioe.wingmate.domain.StartupMode
import io.github.jdreioe.wingmate.domain.TtsEngine
import io.github.jdreioe.wingmate.domain.loggingClassName
import io.github.jdreioe.wingmate.platform.FilePicker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.getKoin

/**
 * Shows where the Communicator is in setup, beside the page on wide windows. Information
 * only: it has no actions, so switch and keyboard scanning go straight to the page.
 */
@Composable
internal fun SetupProgressPane(step: WelcomeStep, modifier: Modifier = Modifier) {
    val current = step.section
    val progressDescription = current?.let {
        stringResource(R.string.setup_progress_description, it.ordinal + 1, SetupSection.entries.size, stringResource(it.label))
    }
    Surface(
        modifier = modifier.padding(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(40.dp)
                .semantics(mergeDescendants = true) { progressDescription?.let { contentDescription = it } },
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                painterResource(R.drawable.ic_launcher_monochrome),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(96.dp).offset(x = (-16).dp),
            )
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(24.dp))
            SetupSection.entries.forEach { section ->
                SetupProgressRow(
                    number = section.ordinal + 1,
                    label = stringResource(section.label),
                    isCurrent = section == current,
                    isDone = current != null && section.ordinal < current.ordinal,
                )
            }
        }
    }
}

/** One part of setup. The current part is shown by a filled circle and bold text, not colour alone. */
@Composable
internal fun SetupProgressRow(number: Int, label: String, isCurrent: Boolean, isDone: Boolean) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 56.dp)) {
        Surface(
            shape = CircleShape,
            color = when {
                isCurrent -> colors.primary
                isDone -> colors.secondaryContainer
                else -> Color.Transparent
            },
            contentColor = when {
                isCurrent -> colors.onPrimary
                isDone -> colors.onSecondaryContainer
                else -> colors.onSurfaceVariant
            },
            border = if (isCurrent || isDone) null else BorderStroke(2.dp, colors.outline),
            modifier = Modifier.size(40.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (isDone) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(24.dp))
                } else {
                    Text("$number", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        Spacer(Modifier.width(16.dp))
        Text(
            label,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal),
            color = if (isCurrent) colors.onSurface else colors.onSurfaceVariant,
        )
    }
}

/**
 * Wraps a page shared with Settings in setup's frame: a heading, and Back, an optional
 * second action, and an optional primary action pinned where every setup page has them.
 */
@Composable
internal fun PageWithBack(
    title: String,
    onBack: () -> Unit,
    subtitle: String? = null,
    secondaryLabel: String? = null,
    onSecondary: () -> Unit = {},
    primaryLabel: String? = null,
    onPrimary: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp).semantics { heading() },
        )
        subtitle?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp),
            )
        }
        Box(Modifier.weight(1f)) { content() }
        secondaryLabel?.let {
            SetupActionButton(label = it, onClick = onSecondary, modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp))
        }
        SetupBottomBar(onBack = onBack, primaryLabel = primaryLabel, onPrimary = onPrimary)
    }
}

/**
 * Common frame for setup pages: a heading, scrollable content, and large Back and
 * primary buttons that stay in the same place on every page.
 */
@Composable
internal fun WelcomePage(
    title: String,
    subtitle: String?,
    primaryLabel: String,
    onPrimary: () -> Unit,
    onBack: (() -> Unit)?,
    primaryEnabled: Boolean = true,
    /** Shows progress in the primary button, announced as [primaryLabel] still. */
    primaryBusy: Boolean = false,
    /** An optional quiet action under the bottom bar (e.g. Restore a backup). */
    quietLabel: String? = null,
    quietEnabled: Boolean = true,
    onQuiet: () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { heading() },
                )
                subtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                content()
            }
            SetupBottomBar(
                onBack = onBack,
                primaryLabel = primaryLabel,
                onPrimary = onPrimary,
                primaryEnabled = primaryEnabled,
                primaryBusy = primaryBusy,
            )
            quietLabel?.let {
                SetupQuietButton(
                    label = it,
                    onClick = onQuiet,
                    enabled = quietEnabled,
                    modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
                )
            }
        }
    }
}

/** A large, single-choice card. Selection is shown by border, icon, and semantics, not colour alone. */
@Composable
internal fun ChoiceCard(
    title: String,
    description: String,
    icon: ImageVector,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        border = BorderStroke(
            if (selected) 3.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        ),
    ) {
        Row(
            modifier = Modifier.padding(20.dp).heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(description, style = MaterialTheme.typography.bodyMedium)
            }
            if (selected) {
                Spacer(Modifier.width(12.dp))
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}

/** A labelled switch whose whole row is the touch target. */
@Composable
internal fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

// ─── Pages ───────────────────────────────────────────────────────────────────

/** Where a privacy card's data goes. Shown as text and icon so it never relies on colour. */
internal enum class DataLocation(val label: Int, val icon: ImageVector) {
    OnDevice(R.string.welcome_privacy_on_device, Icons.Default.PhoneAndroid),
    /** The installed system speech engine; Wingmate cannot promise it never uses the network. */
    DeviceEngine(R.string.welcome_privacy_device_engine, Icons.Default.PhoneAndroid),
    Online(R.string.welcome_privacy_online, Icons.Default.Cloud),
    OnlineWhenOn(R.string.welcome_privacy_online_when_on, Icons.Default.Cloud),
}

@Composable
internal fun PrivacyCard(
    title: String,
    location: DataLocation,
    description: String,
    toggle: (@Composable () -> Unit)? = null,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(location.icon, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(location.label), style = MaterialTheme.typography.labelLarge)
            }
            Text(description, style = MaterialTheme.typography.bodyMedium)
            toggle?.invoke()
        }
    }
}

/** One answer on Review. [detail] is a quieter second line; no [onChange] means nothing to choose. */
@Composable
internal fun ReviewRow(label: String, value: String, enabled: Boolean, onChange: (() -> Unit)?, detail: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium)
            detail?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (onChange != null) {
            val changeLabel = stringResource(R.string.welcome_review_change_label, label)
            FilledTonalButton(
                onClick = onChange,
                enabled = enabled,
                modifier = Modifier.heightIn(min = 56.dp).semantics { contentDescription = changeLabel },
            ) {
                Text(stringResource(R.string.welcome_review_change))
            }
        }
    }
}
