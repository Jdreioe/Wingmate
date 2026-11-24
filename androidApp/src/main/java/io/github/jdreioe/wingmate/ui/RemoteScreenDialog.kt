package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.hojmoseit.wingmate.R
import io.github.jdreioe.wingmate.application.RemoteScreenActions
import io.github.jdreioe.wingmate.application.RemoteScreenRequest
import io.github.jdreioe.wingmate.application.RemoteScreenResult
import io.github.jdreioe.wingmate.domain.obf.ObfBoardSet
import io.github.jdreioe.wingmate.domain.obf.RemoteFileChoice
import kotlinx.coroutines.launch

@Composable
internal fun RemoteScreenDialog(
    target: ObfBoardSet?,
    actions: RemoteScreenActions,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val cloudDriveProviders = remember(actions) { actions.cloudDriveProviders }
    val providers = buildList {
        add("webdav" to stringResource(R.string.remote_screen_provider_webdav))
        add("github" to stringResource(R.string.remote_screen_provider_github))
        add("https_url" to stringResource(R.string.remote_screen_provider_https))
        if (actions.isLinkedFileSupported) {
            add("linked_file" to stringResource(R.string.remote_screen_provider_linked_file))
        }
        if ("google_drive" in cloudDriveProviders) {
            add("google_drive" to stringResource(R.string.remote_screen_provider_google_drive))
        }
        if ("onedrive" in cloudDriveProviders) {
            add("onedrive" to stringResource(R.string.remote_screen_provider_onedrive))
        }
    }
    var provider by remember { mutableStateOf("webdav") }
    var chooseProvider by remember { mutableStateOf(false) }
    var location by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var branch by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<RemoteScreenResult?>(null) }
    // Track link & writability immediately after submit so UI doesn't flicker while RetryLoad runs.
    var connectedPendingRefresh by remember { mutableStateOf(false) }
    var connectedWritablePending by remember { mutableStateOf(true) }
    // An `open` that succeeded: the new Screen is in the library and the form is done.
    var opened by remember { mutableStateOf(false) }
    // Google Drive / OneDrive: a reference to the signed-in account (tokens stay in Kotlin).
    var accountRef by remember { mutableStateOf<String?>(null) }
    var signInMessage by remember { mutableStateOf<String?>(null) }
    var cloudFiles by remember { mutableStateOf<List<RemoteFileChoice>?>(null) }
    var cloudFilesMessage by remember { mutableStateOf("") }
    var selectedCloudFile by remember { mutableStateOf<RemoteFileChoice?>(null) }
    val isCloudDrive = provider == "google_drive" || provider == "onedrive"

    fun resetProviderInput() {
        location = ""
        displayName = ""
        secret = ""
        accountRef = null
        signInMessage = null
        cloudFiles = null
        cloudFilesMessage = ""
        selectedCloudFile = null
        result = null
    }

    fun signIn() {
        busy = true
        result = null
        scope.launch {
            try {
                val response = actions.perform(RemoteScreenRequest(action = "sign_in", provider = provider))
                val ref = response.accountRef
                if (response.status == "ok" && ref != null) {
                    accountRef = ref
                    signInMessage = response.message
                    cloudFiles = null
                    selectedCloudFile = null
                    if (target == null) {
                        val listed = actions.perform(
                            RemoteScreenRequest(action = "list_files", provider = provider, accountRef = ref),
                        )
                        if (listed.status == "ok") {
                            cloudFiles = listed.files
                            cloudFilesMessage = listed.message
                        } else {
                            result = listed
                        }
                    }
                } else {
                    result = response
                }
            } finally {
                busy = false
            }
        }
    }

    fun submit(action: String) {
        busy = true
        val chosenFile = selectedCloudFile
        val request = if (isCloudDrive && (action == "open" || action == "connect")) {
            RemoteScreenRequest(
                action = action,
                screenId = target?.id,
                provider = provider,
                // Blank on connect: a new file named after the Screen.
                location = if (action == "open") chosenFile?.location.orEmpty() else "",
                displayName = if (action == "open") chosenFile?.name.orEmpty() else "",
                accountRef = accountRef.orEmpty(),
            )
        } else {
            RemoteScreenRequest(
                action = action,
                screenId = target?.id,
                provider = provider,
                location = location,
                displayName = displayName,
                username = username,
                secret = secret,
                branch = branch,
                filePath = path,
            )
        }
        scope.launch {
            try {
                val response = actions.perform(request)
                result = response
                if (action == "open" && response.status == "ok") opened = true
                if (action == "connect" && response.status in listOf("ok", "conflict", "read_only_conflict")) {
                    connectedPendingRefresh = true
                    connectedWritablePending = response.status != "read_only_conflict" && provider != "https_url"
                } else if (action == "disconnect" && response.status == "ok") {
                    connectedPendingRefresh = false
                }
                if (response.status != "error" && response.status != "locked") secret = ""
                onRefresh()
            } finally {
                busy = false
            }
        }
    }

    fun pickLinkedFile() {
        busy = true
        scope.launch {
            try {
                val handle = if (target == null) {
                    actions.pickExistingLinkedFile()
                } else {
                    actions.createLinkedFile(target.name)
                }
                if (handle != null) {
                    location = handle.location
                    displayName = handle.displayName
                }
            } catch (error: Exception) {
                result = RemoteScreenResult("error", error.message ?: "Could not select file")
            } finally {
                busy = false
            }
        }
    }

    val link = target?.remoteLink
    val isLinked = link != null || connectedPendingRefresh
    val isWritable = link?.isWritable ?: connectedWritablePending
    val conflict = result?.status in listOf("conflict", "read_only_conflict") || link?.hasConflict == true
    val providerLabel = providers.firstOrNull { it.first == provider }?.second ?: provider

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = {
            Text(
                target?.let { stringResource(R.string.remote_screen_title_for, it.name) }
                    ?: stringResource(R.string.remote_screen_open),
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isLinked) {
                    Text(link?.resolvedDisplayName() ?: displayName.ifBlank { location })
                    Text(
                        stringResource(R.string.remote_screen_auto_sync_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (conflict && !busy) {
                        TextButton(onClick = { submit("use_remote") }) {
                            Text(stringResource(R.string.remote_screen_conflict_use_remote))
                        }
                        if (isWritable) {
                            TextButton(onClick = { submit("keep_local") }) {
                                Text(stringResource(R.string.remote_screen_conflict_keep_local))
                            }
                        }
                        TextButton(onClick = { submit("save_remote_as_copy") }) {
                            Text(stringResource(R.string.remote_screen_conflict_save_copy))
                        }
                    }
                    TextButton(enabled = !busy, onClick = { submit("disconnect") }) {
                        Text(stringResource(R.string.remote_screen_disconnect))
                    }
                } else if (!opened) {
                    TextButton(enabled = !busy, onClick = { chooseProvider = true }) { Text(providerLabel) }
                    DropdownMenu(expanded = chooseProvider, onDismissRequest = { chooseProvider = false }) {
                        providers.forEach { (kind, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = {
                                provider = kind
                                chooseProvider = false
                                resetProviderInput()
                            })
                        }
                    }
                    if (isCloudDrive) {
                        OutlinedButton(enabled = !busy, onClick = ::signIn) {
                            Text(stringResource(R.string.remote_screen_sign_in))
                        }
                        signInMessage?.let { Text(it) }
                        val files = cloudFiles
                        if (target == null && files != null) {
                            if (files.isEmpty()) {
                                Text(
                                    cloudFilesMessage,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Text(
                                    stringResource(R.string.remote_screen_choose_cloud_file),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Column(Modifier.selectableGroup()) {
                                    files.forEach { file ->
                                        val selected = selectedCloudFile?.location == file.location
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(min = 48.dp)
                                                .selectable(
                                                    selected = selected,
                                                    enabled = !busy,
                                                    role = Role.RadioButton,
                                                    onClick = { selectedCloudFile = file },
                                                ),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            RadioButton(selected = selected, onClick = null)
                                            Text(file.name, Modifier.padding(start = 12.dp))
                                        }
                                    }
                                }
                            }
                        }
                    } else if (provider == "linked_file") {
                        OutlinedButton(enabled = !busy, onClick = ::pickLinkedFile) {
                            Text(
                                stringResource(
                                    if (target == null) R.string.remote_screen_choose_file
                                    else R.string.remote_screen_create_file,
                                ),
                            )
                        }
                        if (displayName.isNotBlank()) {
                            Text(stringResource(R.string.remote_screen_selected_file, displayName))
                        }
                    } else {
                        OutlinedTextField(
                            location,
                            { location = it },
                            label = {
                                Text(
                                    stringResource(
                                        if (provider == "github") R.string.remote_screen_location_repo
                                        else R.string.remote_screen_location_url,
                                    ),
                                )
                            },
                            enabled = !busy,
                            singleLine = true,
                        )
                        if (provider == "webdav") {
                            OutlinedTextField(
                                username,
                                { username = it },
                                label = { Text(stringResource(R.string.remote_screen_username)) },
                                enabled = !busy,
                                singleLine = true,
                            )
                        }
                        if (provider == "github") {
                            OutlinedTextField(
                                branch,
                                { branch = it },
                                label = { Text(stringResource(R.string.remote_screen_branch)) },
                                enabled = !busy,
                                singleLine = true,
                            )
                            OutlinedTextField(
                                path,
                                { path = it },
                                label = { Text(stringResource(R.string.remote_screen_file_path)) },
                                enabled = !busy,
                                singleLine = true,
                            )
                        }
                        OutlinedTextField(
                            secret,
                            { secret = it },
                            label = { Text(stringResource(R.string.remote_screen_secret)) },
                            enabled = !busy,
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                        )
                    }
                }
                result?.message?.takeIf { it.isNotBlank() }?.let { Text(it) }
                if (busy) CircularProgressIndicator()
            }
        },
        confirmButton = {
            when {
                isLinked && !conflict -> TextButton(enabled = !busy, onClick = { submit("sync") }) {
                    Text(stringResource(R.string.remote_screen_sync_now))
                }
                !isLinked && !opened && isCloudDrive && target != null -> TextButton(
                    enabled = !busy && accountRef != null,
                    onClick = { submit("connect") },
                ) {
                    Text(stringResource(R.string.remote_screen_save_to_cloud, providerLabel))
                }
                !isLinked && !opened && isCloudDrive -> TextButton(
                    enabled = !busy && accountRef != null && selectedCloudFile != null,
                    onClick = { submit("open") },
                ) {
                    Text(stringResource(R.string.remote_screen_open_action))
                }
                !isLinked && !opened -> TextButton(
                    enabled = !busy && location.isNotBlank(),
                    onClick = { submit(if (target == null) "open" else "connect") },
                ) {
                    Text(stringResource(if (target == null) R.string.remote_screen_open_action else R.string.remote_screen_connect))
                }
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) {
                Text(stringResource(R.string.remote_screen_close))
            }
        },
    )
}
