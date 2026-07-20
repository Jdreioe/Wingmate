package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.jdreioe.wingmate.domain.chatterbox.ChatterboxModel
import io.github.jdreioe.wingmate.domain.chatterbox.ClonedVoiceProfile
import io.github.jdreioe.wingmate.domain.chatterbox.ModelDownloader
import io.github.jdreioe.wingmate.domain.chatterbox.ModelInstallationStatus
import io.github.jdreioe.wingmate.domain.chatterbox.ModelRepository
import io.github.jdreioe.wingmate.domain.chatterbox.VoiceProfileRepository
import io.github.jdreioe.wingmate.domain.chatterbox.ChatterboxRuntimeStatus
import io.github.jdreioe.wingmate.domain.chatterbox.ChatterboxStatusProvider
import io.github.jdreioe.wingmate.infrastructure.chatterbox.ChatterboxModelManager
import io.github.jdreioe.wingmate.infrastructure.chatterbox.OfficialModelRegistry
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import org.koin.compose.koinInject
import io.github.jdreioe.wingmate.ui.ConsentPhrases

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatterboxVoiceSettingsScreen(
    onBack: () -> Unit,
    onContinue: (() -> Unit)? = null,
    tempDir: String = System.getProperty("java.io.tmpdir") ?: ".",
    languageCode: String = "en",
    onExtractConditionals: suspend (String) -> Result<Unit> = { Result.failure(UnsupportedOperationException("Voice cloning not available on this platform")) },
    onVerifyConsent: suspend (String) -> Result<String> = { Result.failure(UnsupportedOperationException("Speech verification not available on this platform")) },
    onVerifyFile: suspend (String, String) -> Result<String> = { _, _ -> Result.failure(UnsupportedOperationException("File verification not available on this platform")) },
    onExtractMp4Audio: suspend (String, String) -> Result<String> = { _, _ -> Result.failure(UnsupportedOperationException("MP4 extraction not available on this platform")) },
    modelManager: ChatterboxModelManager = koinInject(),
    modelRepository: ModelRepository = koinInject(),
    voiceProfileRepository: VoiceProfileRepository = koinInject(),
    modelDownloader: ModelDownloader = koinInject(),
    statusProvider: ChatterboxStatusProvider = koinInject(),
) {
    val scope = rememberCoroutineScope()
    var showModelManagement by remember { mutableStateOf(false) }
    var showVoiceManagement by remember { mutableStateOf(false) }

    val speechVerifier = remember {
        org.koin.core.context.GlobalContext.getOrNull()
            ?.getOrNull<io.github.jdreioe.wingmate.domain.chatterbox.SpeechVerifier>()
    }

    val effectiveVerifyConsent: suspend (String) -> Result<String> = remember(speechVerifier) {
        if (speechVerifier != null) { { lang -> speechVerifier.verify(lang) } }
        else onVerifyConsent
    }

    val effectiveVerifyFile: suspend (String, String) -> Result<String> = remember(speechVerifier) {
        if (speechVerifier != null) { { path, lang -> speechVerifier.verifyFromFile(path, lang) } }
        else onVerifyFile
    }

    val mp4Extractor = remember {
        org.koin.core.context.GlobalContext.getOrNull()
            ?.getOrNull<io.github.jdreioe.wingmate.domain.chatterbox.AudioExtractor>()
    }

    val voiceCloningService = remember {
        org.koin.core.context.GlobalContext.getOrNull()
            ?.getOrNull<io.github.jdreioe.wingmate.domain.chatterbox.VoiceCloningService>()
    }

    val effectiveExtractConditionals: suspend (String) -> Result<Unit> = remember(voiceCloningService) {
        if (voiceCloningService != null) { { path -> voiceCloningService.extractConditionals(path) } }
        else onExtractConditionals
    }

    val effectiveExtractMp4: suspend (String, String) -> Result<String> = remember(mp4Extractor) {
        if (mp4Extractor != null) { { mp4Path, outPath ->
            mp4Extractor.extractToWav(mp4Path, outPath)
        } }
        else onExtractMp4Audio
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            TopAppBar(
                title = { Text("Chatterbox Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    "On-device neural TTS with voice cloning",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // ─── Model Management Card ───────────────────────────────
                ElevatedCard(
                    onClick = { showModelManagement = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.Memory,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Models", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Download, activate, and manage speech models",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(Icons.Filled.ArrowForward, contentDescription = null)
                    }
                }

                // ─── Voice Management Card ──────────────────────────────
                ElevatedCard(
                    onClick = { showVoiceManagement = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.RecordVoiceOver,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Voices", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Record, clone, import, and manage voices",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(Icons.Filled.ArrowForward, contentDescription = null)
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Quick status summary
                ModelsStatusCard(modelDownloader, statusProvider)
                VoicesStatusCard(voiceProfileRepository)
                if (onContinue != null) {
                    Button(
                        onClick = onContinue,
                        enabled = modelDownloader.installationStatus(OfficialModelRegistry.Q4_MODEL_ID) is ModelInstallationStatus.Installed,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Continue to voice test")
                    }
                }
            }
        }
    }

    if (showModelManagement) {
        ModelManagementSheet(
            show = showModelManagement,
            onDismiss = { showModelManagement = false },
            modelManager = modelManager,
            modelRepository = modelRepository,
            modelDownloader = modelDownloader,
            statusProvider = statusProvider,
        )
    }

    if (showVoiceManagement) {
        val audioRecorder: io.github.jdreioe.wingmate.platform.AudioRecorder = koinInject()
        val filePicker: io.github.jdreioe.wingmate.platform.FilePicker = koinInject()
        val modelDownloaderForVoice: ModelDownloader = modelDownloader
        VoiceManagementSheet(
            show = showVoiceManagement,
            onDismiss = { showVoiceManagement = false },
            voiceProfileRepository = voiceProfileRepository,
            audioRecorder = audioRecorder,
            filePicker = filePicker,
            tempDir = tempDir,
            languageCode = languageCode,
            onExtractConditionals = effectiveExtractConditionals,
            onVerifyConsent = effectiveVerifyConsent,
            onVerifyFile = effectiveVerifyFile,
            onExtractMp4Audio = effectiveExtractMp4,
        )
    }
}

@Composable
private fun ModelsStatusCard(
    modelDownloader: ModelDownloader,
    statusProvider: ChatterboxStatusProvider,
) {
    val runtimeStatus by statusProvider.status.collectAsState()
    val installation = modelDownloader.installationStatus(OfficialModelRegistry.Q4_MODEL_ID)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Status", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Text(
                when (installation) {
                    is ModelInstallationStatus.Installed -> "Q4 model installed and verified"
                    is ModelInstallationStatus.Partial -> "Partial download saved"
                    is ModelInstallationStatus.Invalid -> "Model needs repair"
                    ModelInstallationStatus.NotInstalled -> "Q4 model not installed"
                }
            )
            Text(runtimeStatus.label(), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun VoicesStatusCard(
    voiceProfileRepository: VoiceProfileRepository,
) {
    var voiceCount by remember { mutableStateOf(0) }
    var activeVoiceName by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        loading = true
        val voices = voiceProfileRepository.list()
        voiceCount = voices.size
        activeVoiceName = voiceProfileRepository.getActive()?.name
        loading = false
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Voices", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp))
            } else {
                Text("${voiceCount} voice(s) saved")
                if (activeVoiceName != null) {
                    Text("Active: $activeVoiceName", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("No voice selected", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

// ─── Model Management Sheet ─────────────────────────────────────────────────

@Composable
private fun ModelManagementSheet(
    show: Boolean,
    onDismiss: () -> Unit,
    modelManager: ChatterboxModelManager,
    modelRepository: ModelRepository,
    modelDownloader: ModelDownloader,
    statusProvider: ChatterboxStatusProvider,
) {
    if (!show) return

    val scope = rememberCoroutineScope()
    var models by remember { mutableStateOf<List<ChatterboxModel>>(emptyList()) }
    var activeModel by remember { mutableStateOf<ChatterboxModel?>(null) }
    var loading by remember { mutableStateOf(true) }
    var downloadProgress by remember { mutableStateOf<Float?>(null) }
    var downloadingModelId by remember { mutableStateOf<String?>(null) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var hasLegacyDownload by remember { mutableStateOf(modelDownloader.hasLegacyDownload()) }

    LaunchedEffect(show) {
        loading = true
        val official = OfficialModelRegistry.models
        val saved = modelRepository.list()
        val merged = official.map { officialModel ->
            val status = modelDownloader.installationStatus(officialModel.id)
            val stored = saved.find { it.id == officialModel.id }
            (stored ?: officialModel).copy(
                isInstalled = status is ModelInstallationStatus.Installed,
                storagePath = (status as? ModelInstallationStatus.Installed)?.storagePath,
            )
        }
        models = merged
        activeModel = modelRepository.getActive()
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .fillMaxWidth(0.95f)
            .fillMaxHeight(0.85f),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Memory, contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Text("Voice Models", style = MaterialTheme.typography.headlineSmall)
            }
        },
        text = {
            if (loading) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (hasLegacyDownload) {
                        item {
                            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp)) {
                                    Text("Old or incomplete Chatterbox files found")
                                    Text(
                                        "These files use the previous unsupported layout.",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    TextButton(onClick = {
                                        scope.launch {
                                            modelDownloader.deleteLegacyDownload().onSuccess {
                                                hasLegacyDownload = false
                                            }.onFailure { errorMessage = it.message }
                                        }
                                    }) { Text("Remove old download") }
                                }
                            }
                        }
                    }
                    items(models, key = { it.id }) { model ->
                        ModelCard(
                            model = model,
                            isActive = activeModel?.id == model.id,
                            isDownloading = downloadingModelId == model.id,
                            downloadProgress = downloadProgress,
                            onActivate = {
                                scope.launch {
                                    if (modelDownloader.installationStatus(model.id) is ModelInstallationStatus.Installed) {
                                        modelRepository.setActive(model)
                                        activeModel = model
                                    }
                                }
                            },
                            onDownload = {
                                downloadJob = scope.launch {
                                    errorMessage = null
                                    downloadingModelId = model.id
                                    downloadProgress = 0f
                                    modelDownloader.downloadModel(model.id) { progress ->
                                        downloadProgress = progress
                                    }.onSuccess {
                                        val status = modelDownloader.installationStatus(model.id)
                                        val installedModel = model.copy(
                                            isInstalled = status is ModelInstallationStatus.Installed,
                                            storagePath = (status as? ModelInstallationStatus.Installed)?.storagePath,
                                        )
                                        modelRepository.save(installedModel)
                                        val updated = models.toMutableList()
                                        val idx = updated.indexOfFirst { it.id == model.id }
                                        if (idx >= 0) updated[idx] = installedModel
                                        models = updated
                                    }.onFailure {
                                        errorMessage = it.message ?: "Model download failed"
                                    }
                                    downloadingModelId = null
                                    downloadProgress = null
                                    downloadJob = null
                                }
                            },
                            onCancel = { downloadJob?.cancel() },
                            onDelete = {
                                scope.launch {
                                    errorMessage = null
                                    statusProvider.release()
                                    modelDownloader.deleteModel(model.id).onSuccess {
                                        modelRepository.delete(model.id)
                                        if (activeModel?.id == model.id) activeModel = null
                                        models = models.map {
                                            if (it.id == model.id) it.copy(isInstalled = false, storagePath = null) else it
                                        }
                                    }.onFailure { errorMessage = it.message }
                                }
                            },
                        )
                    }
                    errorMessage?.let { message ->
                        item {
                            Text(message, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun ModelCard(
    model: ChatterboxModel,
    isActive: Boolean,
    isDownloading: Boolean,
    downloadProgress: Float?,
    onActivate: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = if (isActive) {
            CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        } else {
            CardDefaults.elevatedCardColors()
        }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = model.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "v${model.version}  |  ${formatSize(model.sizeBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (model.languages.isNotEmpty()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = model.languages.joinToString(", "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            if (isDownloading && downloadProgress != null) {
                LinearProgressIndicator(
                    progress = { downloadProgress },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "${(downloadProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (model.isInstalled || (downloadProgress != null && downloadProgress >= 1f)) {
                    if (isActive) {
                        FilledTonalButton(onClick = onActivate) {
                            Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Active")
                        }
                    } else {
                        OutlinedButton(onClick = onActivate) {
                            Text("Activate")
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete", modifier = Modifier.size(18.dp))
                    }
                } else if (isDownloading) {
                    OutlinedButton(onClick = onCancel) { Text("Cancel") }
                } else {
                    FilledTonalButton(onClick = onDownload) {
                        Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Download")
                    }
                }
            }
        }
    }
}

private fun ChatterboxRuntimeStatus.label(): String = when (this) {
    ChatterboxRuntimeStatus.NotInstalled -> "Runtime: not installed"
    is ChatterboxRuntimeStatus.Downloading -> "Runtime: downloading ${(progress * 100).toInt()}%"
    ChatterboxRuntimeStatus.Verifying -> "Runtime: verifying model"
    ChatterboxRuntimeStatus.Loading -> "Runtime: loading"
    is ChatterboxRuntimeStatus.Ready -> "Runtime: ready"
    ChatterboxRuntimeStatus.Speaking -> "Runtime: speaking"
    is ChatterboxRuntimeStatus.Error -> if (fallbackUsed) "$message (system voice used)" else message
}

private enum class RecordingState { Idle, Recording, Processing }

@Composable
private fun ConsentVerificationCard(
    languageCode: String,
    onVerified: () -> Unit,
    onCancel: () -> Unit,
    onVerify: suspend () -> Result<String>,
) {
    val scope = rememberCoroutineScope()
    var verifying by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf("") }
    val phrase = ConsentPhrases.get(languageCode)

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Gavel, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Consent Required",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Please read the following phrase aloud to consent to voice cloning:",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    phrase,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(12.dp),
                )
            }
            Spacer(Modifier.height(12.dp))

            if (result != null) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("You said:", style = MaterialTheme.typography.labelSmall)
                        Text(
                            result!!,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onVerified,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Consent Verified — Proceed")
                }
            } else if (verifying) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Listening...")
                }
            } else {
                Button(
                    onClick = {
                        scope.launch {
                            verifying = true
                            error = ""
                            onVerify().onSuccess { transcript ->
                                result = transcript
                            }.onFailure { e ->
                                error = e.message ?: "Verification failed"
                            }
                            verifying = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("I Agree — Speak Phrase")
                }
            }

            if (error.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Error, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel")
            }
        }
    }
}

// ─── Voice Management Sheet ─────────────────────────────────────────────────

@Composable
private fun VoiceManagementSheet(
    show: Boolean,
    onDismiss: () -> Unit,
    voiceProfileRepository: VoiceProfileRepository,
    audioRecorder: io.github.jdreioe.wingmate.platform.AudioRecorder,
    filePicker: io.github.jdreioe.wingmate.platform.FilePicker,
    tempDir: String = ".",
    languageCode: String = "en",
    onExtractConditionals: suspend (String) -> Result<Unit> = { Result.failure(UnsupportedOperationException("Voice cloning not available")) },
    onVerifyConsent: suspend (String) -> Result<String> = { Result.failure(UnsupportedOperationException("Speech verification not available")) },
    onVerifyFile: suspend (String, String) -> Result<String> = { _, _ -> Result.failure(UnsupportedOperationException("File verification not available")) },
    onExtractMp4Audio: suspend (String, String) -> Result<String> = { _, _ -> Result.failure(UnsupportedOperationException("MP4 extraction not available")) },
) {
    if (!show) return

    val scope = rememberCoroutineScope()
    var voices by remember { mutableStateOf<List<ClonedVoiceProfile>>(emptyList()) }
    var activeVoice by remember { mutableStateOf<ClonedVoiceProfile?>(null) }
    var loading by remember { mutableStateOf(true) }
    var recordingState by remember { mutableStateOf<RecordingState>(RecordingState.Idle) }
    var recordingFileName by remember { mutableStateOf("") }
    var consentError by remember { mutableStateOf("") }
    var showConsentDialog by remember { mutableStateOf(false) }
    var showRecordingUI by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf("") }
    var importSuccess by remember { mutableStateOf(false) }
    var previewResult by remember { mutableStateOf<String?>(null) }

    val speechService = remember {
        org.koin.core.context.GlobalContext.getOrNull()
            ?.getOrNull<io.github.jdreioe.wingmate.domain.SpeechService>()
    }

    LaunchedEffect(show) {
        loading = true
        voices = voiceProfileRepository.list()
        activeVoice = voiceProfileRepository.getActive()
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .fillMaxWidth(0.95f)
            .fillMaxHeight(0.85f),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.RecordVoiceOver, contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Text("My Voices", style = MaterialTheme.typography.headlineSmall)
            }
        },
        text = {
            if (loading) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                } else if (voices.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(Modifier.height(32.dp))
                    Icon(
                        Icons.Filled.Mic,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "No cloned voices yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Record a voice sample or import an existing voice to get started.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(24.dp))
                    // Step 1: Consent verification
                    var showConsentBeforeRecord by remember { mutableStateOf(false) }
                    if (!showRecordingUI) {
                        Button(
                            onClick = { showConsentBeforeRecord = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Filled.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Clone Voice")
                        }
                    }
                    if (showConsentBeforeRecord) {
                        ConsentVerificationCard(
                            languageCode = languageCode,
                            onVerified = { showRecordingUI = true; showConsentBeforeRecord = false },
                            onCancel = { showConsentBeforeRecord = false },
                            onVerify = { onVerifyConsent(languageCode) },
                        )
                    }
                    if (showRecordingUI) {
                        // Step 2: Record reference audio for cloning
                        when (recordingState) {
                            RecordingState.Idle -> {
                                Button(
                                    onClick = {
                                        scope.launch {
                                            recordingState = RecordingState.Recording
                                            val path = "$tempDir/voice_clone_${System.currentTimeMillis()}.wav"
                                            recordingFileName = path
                                            audioRecorder.start(path)
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.tertiary
                                    ),
                                ) {
                                    Icon(Icons.Filled.FiberManualRecord, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Record Reference Audio")
                                }
                            }
                            RecordingState.Recording -> {
                                Button(
                                    onClick = {
                                        scope.launch {
                                            recordingState = RecordingState.Processing
                                            audioRecorder.stop()
                                            runCatching {
                                                onExtractConditionals(recordingFileName).getOrThrow()
                                                val profile = ClonedVoiceProfile(
                                                    id = java.util.UUID.randomUUID().toString(),
                                                    name = "Voice ${voices.size + 1}",
                                                    modelId = OfficialModelRegistry.Q4_MODEL_ID,
                                                    createdAt = System.currentTimeMillis(),
                                                    sourceRecordingPath = recordingFileName,
                                                    profilePath = recordingFileName,
                                                    metadataPath = recordingFileName,
                                                )
                                                voiceProfileRepository.save(profile)
                                                voices = voiceProfileRepository.list()
                                                showRecordingUI = false
                                                speechService?.speak(
                                                    "Hello, this is my cloned voice. How does it sound?",
                                                )
                                            }
                                            recordingState = RecordingState.Idle
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error
                                    ),
                                ) {
                                    Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Stop Recording")
                                }
                            }
                            RecordingState.Processing -> {
                                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { showRecordingUI = false },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Cancel")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    if (importing) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Extracting and verifying audio...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        OutlinedButton(onClick = {
                            scope.launch {
                                importing = true
                                importError = ""
                                val mediaPath = filePicker.pickFile("Import voice audio", listOf("mp3", "mp4", "MP3", "MP4", "m4a", "M4A", "wav", "WAV"))
                                if (mediaPath != null) {
                                    val wavPath = if (mediaPath.lowercase().endsWith(".wav")) {
                                        mediaPath
                                    } else {
                                        "$tempDir/imported_voice_${System.currentTimeMillis()}.wav"
                                    }
                                    val extractStep: Result<String> = if (mediaPath.lowercase().endsWith(".wav")) {
                                        Result.success(mediaPath)
                                    } else {
                                        onExtractMp4Audio(mediaPath, wavPath)
                                    }
                                    extractStep.onSuccess { extractedPath ->
                                        onVerifyFile(extractedPath, languageCode).onSuccess { transcript ->
                                            if (io.github.jdreioe.wingmate.infrastructure.ConsentPhrases.matches(transcript, languageCode)) {
                                                onExtractConditionals(extractedPath).onSuccess {
                                                    val profile = ClonedVoiceProfile(
                                                        id = java.util.UUID.randomUUID().toString(),
                                                        name = "Imported Voice ${voices.size + 1}",
                                                        modelId = OfficialModelRegistry.Q4_MODEL_ID,
                                                        createdAt = System.currentTimeMillis(),
                                                        sourceRecordingPath = extractedPath,
                                                        profilePath = extractedPath,
                                                        metadataPath = extractedPath,
                                                    )
                                                    voiceProfileRepository.save(profile)
                                                    voices = voiceProfileRepository.list()
                                                    speechService?.speak("Hello, this is my cloned voice. How does it sound?")
                                                }.onFailure { e ->
                                                    importError = "Voice processing failed: ${e.message}"
                                                }
                                            } else {
                                                importError = "Consent phrase not detected. Please ensure the audio contains: \"${ConsentPhrases.get(languageCode)}\""
                                            }
                                        }.onFailure { e ->
                                            importError = e.message ?: "Speech verification failed"
                                        }
                                    }.onFailure { e ->
                                        importError = "Audio extraction failed: ${e.message}"
                                    }
                                }
                                importing = false
                            }
                        }) {
                            Icon(Icons.Filled.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Import Audio File")
                        }
                    }
                    if (importError.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Error, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(importError, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    showConsentDialog = true
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Filled.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Record")
                            }
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        importing = true
                                        importError = ""
                                        val mediaPath = filePicker.pickFile("Import voice audio", listOf("mp3", "mp4", "MP3", "MP4", "m4a", "M4A", "wav", "WAV"))
                                        if (mediaPath != null) {
                                            val wavPath = if (mediaPath.lowercase().endsWith(".wav")) {
                                                mediaPath
                                            } else {
                                                "$tempDir/imported_voice_${System.currentTimeMillis()}.wav"
                                            }
                                            val extractStep: Result<String> = if (mediaPath.lowercase().endsWith(".wav")) {
                                                Result.success(mediaPath)
                                            } else {
                                                onExtractMp4Audio(mediaPath, wavPath)
                                            }
                                            extractStep.onSuccess { extractedPath ->
                                                onVerifyFile(extractedPath, languageCode).onSuccess { transcript ->
                                                    if (io.github.jdreioe.wingmate.infrastructure.ConsentPhrases.matches(transcript, languageCode)) {
                                                        onExtractConditionals(extractedPath).onSuccess {
                                                            val profile = ClonedVoiceProfile(
                                                                id = java.util.UUID.randomUUID().toString(),
                                                                name = "Imported Voice ${voices.size + 1}",
                                                                modelId = OfficialModelRegistry.Q4_MODEL_ID,
                                                                createdAt = System.currentTimeMillis(),
                                                                sourceRecordingPath = extractedPath,
                                                                profilePath = extractedPath,
                                                                metadataPath = extractedPath,
                                                            )
                                                             voiceProfileRepository.save(profile)
                                                             voices = voiceProfileRepository.list()
                                                             speechService?.speak("Hello, this is my cloned voice. How does it sound?")
                                                         }.onFailure { e ->
                                                             importError = "Voice processing failed: ${e.message}"
                                                         }
                                                     } else {
                                                         importError = "Consent phrase not detected in the audio."
                                                    }
                                                }.onFailure { e ->
                                                    importError = e.message ?: "Speech verification failed"
                                                }
                                            }.onFailure { e ->
                                                importError = "Audio extraction failed: ${e.message}"
                                            }
                                        }
                                        importing = false
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Filled.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Import Audio")
                            }
                        }
                        if (importing) {
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Processing video...", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (importError.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Error, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(importError, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    item {
                        if (previewResult != null) {
                            Surface(
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(previewResult!!, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                    item {
                        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("Default Chatterbox voice", style = MaterialTheme.typography.titleMedium)
                                    Text("Included with the verified Q4 model", style = MaterialTheme.typography.bodySmall)
                                }
                                if (activeVoice == null) {
                                    FilledTonalButton(onClick = {}) { Text("Active") }
                                } else {
                                    OutlinedButton(onClick = {
                                        scope.launch {
                                            voiceProfileRepository.clearActive()
                                            activeVoice = null
                                        }
                                    }) { Text("Use") }
                                }
                            }
                        }
                    }
                    items(voices, key = { it.id }) { voice ->
                        VoiceCard(
                            voice = voice,
                            isActive = activeVoice?.id == voice.id,
                            onActivate = {
                                scope.launch {
                                    voiceProfileRepository.setActive(voice)
                                    activeVoice = voice
                                    voice.sourceRecordingPath?.let { path ->
                                        onExtractConditionals(path).onFailure { e ->
                                            importError = "Failed to load voice: ${e.message}"
                                        }
                                    }
                                }
                            },
                            onPreview = {
                                scope.launch {
                                    previewResult = null
                                    voice.sourceRecordingPath?.let { path ->
                                        onExtractConditionals(path).onFailure {
                                            previewResult = "Preview failed: ${it.message}"
                                            return@launch
                                        }
                                    }
                                    speechService?.speak(
                                        "Hello, this is my cloned voice. How does it sound?",
                                    )
                                    previewResult = "Preview played"
                                }
                            },
                            onRename = { /* Phase 4 */ },
                            onExport = { /* Phase 4 */ },
                            onDelete = {
                                scope.launch {
                                    voiceProfileRepository.delete(voice.id)
                                    if (activeVoice?.id == voice.id) activeVoice = null
                                    voices = voiceProfileRepository.list()
                                }
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )

    if (showConsentDialog) {
        AlertDialog(
            onDismissRequest = { showConsentDialog = false },
            title = { Text("Voice Cloning Consent") },
            text = {
                ConsentVerificationCard(
                    languageCode = languageCode,
                    onVerified = {
                        showConsentDialog = false
                        scope.launch {
                            recordingState = RecordingState.Recording
                            val path = "$tempDir/voice_clone_${System.currentTimeMillis()}.wav"
                            recordingFileName = path
                            audioRecorder.start(path)
                        }
                    },
                    onCancel = { showConsentDialog = false },
                    onVerify = { onVerifyConsent(languageCode) },
                )
            },
            confirmButton = {},
        )
    }
}

@Composable
private fun VoiceCard(
    voice: ClonedVoiceProfile,
    isActive: Boolean,
    onActivate: () -> Unit,
    onPreview: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = if (isActive) {
            CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        } else {
            CardDefaults.elevatedCardColors()
        }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Person,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = voice.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Created ${formatTimestamp(voice.createdAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isActive) {
                    FilledTonalButton(onClick = onActivate) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Active")
                    }
                    Spacer(Modifier.width(4.dp))
                } else {
                    OutlinedButton(onClick = onActivate) {
                        Text("Activate")
                    }
                    Spacer(Modifier.width(4.dp))
                }
                IconButton(onClick = onPreview) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Preview")
                }
                IconButton(onClick = onRename) {
                    Icon(Icons.Filled.Edit, contentDescription = "Rename")
                }
                IconButton(onClick = onExport) {
                    Icon(Icons.Filled.FileDownload, contentDescription = "Export")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete")
                }
            }
        }
    }
}

// ─── Helpers ─────────────────────────────────────────────────────────────────

private fun formatSize(bytes: Long): String {
    return when {
        bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> "%.0f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000 -> "%.0f KB".format(bytes / 1_000.0)
        else -> "$bytes B"
    }
}

private fun formatTimestamp(epochMs: Long): String {
    if (epochMs <= 0) return "unknown"
    val seconds = epochMs / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24
    return when {
        days > 0 -> "${days}d ago"
        hours > 0 -> "${hours}h ago"
        minutes > 0 -> "${minutes}m ago"
        else -> "just now"
    }
}
