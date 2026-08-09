package io.github.jdreioe.wingmate.kde

import io.github.jdreioe.wingmate.initKoin
import org.koin.dsl.module
import io.ktor.http.*
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.core.readBytes
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.github.jdreioe.wingmate.domain.ConfigRepository
import io.github.jdreioe.wingmate.domain.PronunciationDictionaryRepository
import io.github.jdreioe.wingmate.domain.PronunciationEntry
import io.github.jdreioe.wingmate.domain.SpeechServiceConfig
import io.github.jdreioe.wingmate.domain.SpeechTextProcessor
import io.github.jdreioe.wingmate.domain.TextPredictionService
import io.github.jdreioe.wingmate.domain.SaidTextRepository
import io.github.jdreioe.wingmate.domain.SaidText
import io.github.jdreioe.wingmate.domain.Voice
import io.github.jdreioe.wingmate.domain.TtsEngine
import io.github.jdreioe.wingmate.domain.VoiceRepository
import io.github.jdreioe.wingmate.domain.Settings
import io.github.jdreioe.wingmate.domain.StartupMode
import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfBoardSet
import io.github.jdreioe.wingmate.domain.obf.backspaceSentenceSelection
import io.github.jdreioe.wingmate.domain.obf.fieldItems
import io.github.jdreioe.wingmate.domain.obf.joinSentenceText
import io.github.jdreioe.wingmate.domain.obf.nGramPredictionInsertion
import io.github.jdreioe.wingmate.domain.obf.ObfButtonActionEffect
import io.github.jdreioe.wingmate.domain.obf.orderedPredictionButtonIds
import io.github.jdreioe.wingmate.domain.obf.pageSettingsOverrides
import io.github.jdreioe.wingmate.domain.obf.parseObfButtonActions
import io.github.jdreioe.wingmate.domain.obf.resolveBoardSettings
import io.github.jdreioe.wingmate.domain.obf.resolveObfLocalizedString
import io.github.jdreioe.wingmate.domain.obf.shouldAddBoardSelection
import io.github.jdreioe.wingmate.domain.obf.shouldSpeakBoardSelection
import io.github.jdreioe.wingmate.domain.obf.BoardActivationBehavior
import io.github.jdreioe.wingmate.domain.obf.BoardReturnBehavior
import io.github.jdreioe.wingmate.domain.obf.BoardSettingsOverrides
import io.github.jdreioe.wingmate.domain.obf.withPageSettingsOverrides
import io.github.jdreioe.wingmate.infrastructure.OpenSymbolsClient
import io.github.jdreioe.wingmate.application.BoardSetUseCase
import io.github.jdreioe.wingmate.application.EditingAccessController
import io.github.jdreioe.wingmate.application.FeatureUsageReporter
import io.github.jdreioe.wingmate.application.SecureEditingCredentialStorage
import io.github.jdreioe.wingmate.infrastructure.BoardImportService
import io.github.jdreioe.wingmate.infrastructure.ObfParser
import io.github.jdreioe.wingmate.domain.BoardRepository
import io.github.jdreioe.wingmate.domain.BoardSetRepository
import io.github.jdreioe.wingmate.domain.UserDataManager
import io.github.jdreioe.wingmate.infrastructure.SimpleNGramPredictionService
import io.github.jdreioe.wingmate.infrastructure.DictionaryLoader
import org.koin.core.context.GlobalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicLong

/**
 * HTTP server that bridges the native UI with Kotlin business logic.
 * The native UI makes REST calls to this local server.
 */
class KotlinBridge(private val port: Int = 8765) {
    private val scope = CoroutineScope(Dispatchers.Default + kotlinx.coroutines.SupervisorJob())
    private val phraseViewModel = PhraseViewModel()
    private val settingsManager = SettingsManager()
    private val configRepository: ConfigRepository by lazy { GlobalContext.get().get() }
    private val azureConfigManager = AzureConfigManager()
    private val speechService = LinuxSpeechService()
    private val voiceRepository: VoiceRepository by lazy { GlobalContext.get().get() }
    private val pronunciationRepository: PronunciationDictionaryRepository by lazy { GlobalContext.get().get() }
    private val azureSpeechService by lazy { AzureSpeechService(configRepository, pronunciationRepository) }
    private val predictionService: TextPredictionService by lazy { GlobalContext.get().get() }
    private val saidTextRepository: SaidTextRepository by lazy { GlobalContext.get().get() }
    private val dictionaryLoader: DictionaryLoader by lazy { GlobalContext.get().get() }
    private val boardSetUseCase: BoardSetUseCase by lazy {
        BoardSetUseCase(
            GlobalContext.get().get<BoardSetRepository>(),
            GlobalContext.get().get<BoardRepository>(),
            GlobalContext.get().get<FeatureUsageReporter>()
        )
    }
    private val boardRepository: BoardRepository by lazy { GlobalContext.get().get() }
    private val boardSetRepository: BoardSetRepository by lazy { GlobalContext.get().get() }
    private val editingAccessController: EditingAccessController by lazy { GlobalContext.get().get() }
    private val boardImportService: BoardImportService by lazy {
        BoardImportService(
            GlobalContext.get().get<ObfParser>(),
            GlobalContext.get().get<BoardRepository>(),
            GlobalContext.get().get<BoardSetRepository>(),
            LinuxFilePicker()
        )
    }
    private val partnerWindowManager = PartnerWindowManager(settingsManager)
    private val userDataManager = UserDataManager(saidTextRepository)
    private val speechGeneration = AtomicLong(0)
    @Volatile
    private var speechJob: Job? = null
    @Volatile
    private var speechState = SpeechStateResponse()
    
    private val json = Json { 
        ignoreUnknownKeys = true 
        isLenient = true
        encodeDefaults = true
    }
    
    private val server = embeddedServer(Netty, port = port) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
                encodeDefaults = true
            })
        }
        
        install(CORS) {
            allowMethod(HttpMethod.Get)
            allowMethod(HttpMethod.Post)
            allowMethod(HttpMethod.Put)
            allowMethod(HttpMethod.Delete)
            allowHeader(HttpHeaders.ContentType)
            anyHost()
        }
        
        routing {
            // Phrases
            get("/api/phrases") {
                val phrases = phraseViewModel.phrases.firstOrNull() ?: emptyList()
                call.respond(phrases)
            }
            
            post("/api/phrases") {
                val body = call.receiveText()
                val jsonObj = json.parseToJsonElement(body).jsonObject
                val text = jsonObj["text"]?.jsonPrimitive?.contentOrNull ?: ""
                val imageUrl = jsonObj["imageUrl"]?.jsonPrimitive?.contentOrNull
                phraseViewModel.addPhrase(text, imageUrl)
                call.respond(HttpStatusCode.Created, mapOf("status" to "ok"))
            }
            
            delete("/api/phrases/{id}") {
                val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
                phraseViewModel.deletePhrase(id)
                call.respond(HttpStatusCode.OK)
            }

            put("/api/phrases/{id}") {
                val id = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest)
                val body = json.parseToJsonElement(call.receiveText()).jsonObject
                val updated = phraseViewModel.updateDetails(
                    id = id,
                    text = body["text"]?.jsonPrimitive?.contentOrNull,
                    name = body["name"]?.jsonPrimitive?.contentOrNull,
                    imageUrl = body["imageUrl"]?.jsonPrimitive?.contentOrNull,
                    parentId = body["parentId"]?.jsonPrimitive?.contentOrNull,
                    linkedBoardId = body["linkedBoardId"]?.jsonPrimitive?.contentOrNull,
                    recordingPath = body["recordingPath"]?.jsonPrimitive?.contentOrNull,
                    isHidden = body["isHidden"]?.jsonPrimitive?.booleanOrNull,
                ) ?: return@put call.respond(HttpStatusCode.NotFound)
                call.respond(updated)
            }

            put("/api/phrases/{id}/move") {
                val id = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest)
                val delta = json.parseToJsonElement(call.receiveText()).jsonObject["delta"]?.jsonPrimitive?.intOrNull ?: 0
                if (!phraseViewModel.moveItem(id, delta)) return@put call.respond(HttpStatusCode.NotFound)
                call.respond(HttpStatusCode.OK)
            }
            
            // Categories
            get("/api/categories") {
                val categories = phraseViewModel.categories.firstOrNull() ?: emptyList()
                call.respond(categories)
            }
            
            post("/api/categories") {
                val body = call.receiveText()
                val jsonObj = json.parseToJsonElement(body).jsonObject
                val name = jsonObj["name"]?.jsonPrimitive?.contentOrNull ?: ""
                phraseViewModel.addCategory(name)
                call.respond(HttpStatusCode.Created, mapOf("status" to "ok"))
            }
            
            post("/api/categories/select") {
                val body = call.receiveText()
                val jsonObj = json.parseToJsonElement(body).jsonObject
                val categoryId = jsonObj["categoryId"]?.jsonPrimitive?.contentOrNull
                phraseViewModel.selectCategory(categoryId)
                call.respond(HttpStatusCode.OK)
            }

            delete("/api/categories/{id}") {
                val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
                phraseViewModel.deleteCategory(id)
                call.respond(HttpStatusCode.OK)
            }

            put("/api/categories/{id}") {
                val id = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest)
                val name = json.parseToJsonElement(call.receiveText()).jsonObject["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val updated = phraseViewModel.renameCategory(id, name)
                    ?: return@put call.respond(HttpStatusCode.BadRequest)
                call.respond(updated)
            }

            put("/api/categories/{id}/move") {
                val id = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest)
                val delta = json.parseToJsonElement(call.receiveText()).jsonObject["delta"]?.jsonPrimitive?.intOrNull ?: 0
                if (!phraseViewModel.moveItem(id, delta)) return@put call.respond(HttpStatusCode.NotFound)
                call.respond(HttpStatusCode.OK)
            }
            
            // Settings
            get("/api/settings") {
                val settings = settingsManager.settings.firstOrNull()
                if (settings == null) {
                    call.respond(HttpStatusCode.ServiceUnavailable, mapOf("status" to "loading"))
                } else {
                    call.respondText(
                        json.encodeToString(settings),
                        ContentType.Application.Json,
                        HttpStatusCode.OK
                    )
                }
            }
            
            put("/api/settings") {
                val body = call.receiveText()
                println("[API] PUT /api/settings payload: $body")
                val jsonObj = json.parseToJsonElement(body).jsonObject
                val current = settingsManager.settings.value ?: io.github.jdreioe.wingmate.domain.Settings()
                var newSettings = current
                
                if (jsonObj.containsKey("welcomeFlowCompleted")) {
                    val completed = jsonObj["welcomeFlowCompleted"]?.jsonPrimitive?.booleanOrNull ?: false
                    newSettings = newSettings.copy(welcomeFlowCompleted = completed)
                }
                
                if (jsonObj.containsKey("primaryLanguage")) {
                    val lang = jsonObj["primaryLanguage"]?.jsonPrimitive?.contentOrNull
                    if (lang != null) newSettings = newSettings.copy(primaryLanguage = lang, language = lang)
                }
                
                if (jsonObj.containsKey("secondaryLanguage")) {
                    val lang = jsonObj["secondaryLanguage"]?.jsonPrimitive?.contentOrNull
                    if (lang != null) newSettings = newSettings.copy(secondaryLanguage = lang)
                }

                jsonObj["startupMode"]?.jsonPrimitive?.contentOrNull?.let { value ->
                    newSettings = newSettings.copy(startupMode = runCatching { StartupMode.valueOf(value) }.getOrDefault(newSettings.startupMode))
                }
                jsonObj["startupBoardSetId"]?.jsonPrimitive?.contentOrNull?.let { value ->
                    newSettings = newSettings.copy(startupBoardSetId = value.takeIf { it.isNotBlank() })
                }
                if (jsonObj.containsKey("forceDarkTheme")) {
                    newSettings = newSettings.copy(forceDarkTheme = jsonObj["forceDarkTheme"]?.jsonPrimitive?.booleanOrNull)
                }
                jsonObj["featureUsageReportingEnabled"]?.jsonPrimitive?.booleanOrNull?.let { newSettings = newSettings.copy(featureUsageReportingEnabled = it) }
                jsonObj["showLabels"]?.jsonPrimitive?.booleanOrNull?.let { newSettings = newSettings.copy(showLabels = it) }
                jsonObj["showSymbols"]?.jsonPrimitive?.booleanOrNull?.let { newSettings = newSettings.copy(showSymbols = it) }
                jsonObj["labelAtTop"]?.jsonPrimitive?.booleanOrNull?.let { newSettings = newSettings.copy(labelAtTop = it) }
                jsonObj["gridColumns"]?.jsonPrimitive?.intOrNull?.let { newSettings = newSettings.copy(gridColumns = it.coerceIn(1, 12)) }
                jsonObj["highContrastMode"]?.jsonPrimitive?.booleanOrNull?.let { newSettings = newSettings.copy(highContrastMode = it) }
                jsonObj["holdToSelectMillis"]?.jsonPrimitive?.longOrNull?.let { newSettings = newSettings.copy(holdToSelectMillis = it.coerceAtLeast(0)) }
                jsonObj["dwellToSelectMillis"]?.jsonPrimitive?.longOrNull?.let { newSettings = newSettings.copy(dwellToSelectMillis = it.coerceAtLeast(0)) }
                jsonObj["selectionDebounceMillis"]?.jsonPrimitive?.longOrNull?.let { newSettings = newSettings.copy(selectionDebounceMillis = it.coerceAtLeast(0)) }
                jsonObj["selectionHighlightMillis"]?.jsonPrimitive?.longOrNull?.let { newSettings = newSettings.copy(selectionHighlightMillis = it.coerceAtLeast(0)) }
                jsonObj["selectionSoundEnabled"]?.jsonPrimitive?.booleanOrNull?.let { newSettings = newSettings.copy(selectionSoundEnabled = it) }
                jsonObj["auditoryFishingEnabled"]?.jsonPrimitive?.booleanOrNull?.let { newSettings = newSettings.copy(auditoryFishingEnabled = it) }
                jsonObj["usageLoggingEnabled"]?.jsonPrimitive?.booleanOrNull?.let { newSettings = newSettings.copy(usageLoggingEnabled = it) }
                jsonObj["historyVisible"]?.jsonPrimitive?.booleanOrNull?.let { newSettings = newSettings.copy(historyVisible = it) }
                jsonObj["boardShowMessageBar"]?.jsonPrimitive?.booleanOrNull?.let { newSettings = newSettings.copy(boardShowMessageBar = it) }
                jsonObj["fontSizeScale"]?.jsonPrimitive?.floatOrNull?.let { newSettings = newSettings.copy(fontSizeScale = it.coerceIn(0.5f, 2f)) }
                jsonObj["buttonScale"]?.jsonPrimitive?.floatOrNull?.let { newSettings = newSettings.copy(buttonScale = it.coerceIn(0.5f, 2f)) }
                jsonObj["inputFieldScale"]?.jsonPrimitive?.floatOrNull?.let { newSettings = newSettings.copy(inputFieldScale = it.coerceIn(0.5f, 2f)) }

                // Switch scanning / switch-access configuration
                jsonObj["scanningEnabled"]?.jsonPrimitive?.booleanOrNull?.let { newSettings = newSettings.copy(scanningEnabled = it) }
                jsonObj["scanPlaybackAreaEnabled"]?.jsonPrimitive?.booleanOrNull?.let { newSettings = newSettings.copy(scanPlaybackAreaEnabled = it) }
                jsonObj["scanInputFieldEnabled"]?.jsonPrimitive?.booleanOrNull?.let { newSettings = newSettings.copy(scanInputFieldEnabled = it) }
                jsonObj["scanPhraseGridEnabled"]?.jsonPrimitive?.booleanOrNull?.let { newSettings = newSettings.copy(scanPhraseGridEnabled = it) }
                jsonObj["scanCategoryItemsEnabled"]?.jsonPrimitive?.booleanOrNull?.let { newSettings = newSettings.copy(scanCategoryItemsEnabled = it) }
                jsonObj["scanTopBarEnabled"]?.jsonPrimitive?.booleanOrNull?.let { newSettings = newSettings.copy(scanTopBarEnabled = it) }
                jsonObj["scanPhraseGridOrder"]?.jsonPrimitive?.contentOrNull?.let { newSettings = newSettings.copy(scanPhraseGridOrder = it) }
                jsonObj["scanDwellTimeSeconds"]?.jsonPrimitive?.floatOrNull?.let { newSettings = newSettings.copy(scanDwellTimeSeconds = it) }
                jsonObj["scanAutoAdvanceSeconds"]?.jsonPrimitive?.floatOrNull?.let { newSettings = newSettings.copy(scanAutoAdvanceSeconds = it) }

                println("[API] Updating settings to: $newSettings")
                settingsManager.updateSettings(newSettings)
                call.respond(HttpStatusCode.OK)
            }

            put("/api/settings/full") {
                val updated = json.decodeFromString<Settings>(call.receiveText())
                settingsManager.updateSettings(updated)
                call.respond(HttpStatusCode.OK)
            }
            
            put("/api/settings/language") {
                val body = call.receiveText()
                val jsonObj = json.parseToJsonElement(body).jsonObject
                val language = jsonObj["language"]?.jsonPrimitive?.contentOrNull ?: "en-US"
                settingsManager.updateLanguage(language)
                call.respond(HttpStatusCode.OK)
            }
            
            put("/api/settings/voice") {
                try {
                    val body = call.receiveText()
                    println("[API] PUT /api/settings/voice RAW body: '$body'")
                    val jsonObj = json.parseToJsonElement(body).jsonObject
                    val voice = jsonObj["voice"]?.jsonPrimitive?.contentOrNull ?: "default"
                    println("[API] PUT /api/settings/voice parsed voice: '$voice'")
                    voiceRepository.getVoices().firstOrNull { it.name == voice }?.let {
                        voiceRepository.saveSelected(it)
                    }
                    settingsManager.updateVoice(voice)
                    call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
                } catch (e: Exception) {
                    println("[API] PUT /api/settings/voice ERROR: ${e.message}")
                    e.printStackTrace()
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "unknown")))
                }
            }
            
            put("/api/settings/rate") {
                val params = call.receive<Map<String, Float>>()
                val rate = params["rate"] ?: 1.0f
                settingsManager.updateSpeechRate(rate)
                call.respond(HttpStatusCode.OK)
            }
            
            put("/api/settings/systemtts") {
                val params = call.receive<Map<String, String>>()
                val engineStr = params["ttsEngine"] ?: "SYSTEM"
                val current = settingsManager.settings.value ?: io.github.jdreioe.wingmate.domain.Settings()
                settingsManager.updateSettings(current.copy(ttsEngine = if (engineStr == "SYSTEM") TtsEngine.SYSTEM else TtsEngine.AZURE_USER_RESOURCE))
                call.respond(HttpStatusCode.OK)
            }

            // Editing access. Communication remains available while editing is locked.
            get("/api/editing-access") {
                val state = editingAccessController.refresh()
                call.respond(
                    EditingAccessResponse(
                        enabled = state.enabled,
                        unlocked = state.unlocked,
                        supported = state.supported,
                        failedAttempts = state.failedAttempts,
                    )
                )
            }

            put("/api/editing-access/code") {
                val code = json.parseToJsonElement(call.receiveText()).jsonObject["code"]
                    ?.jsonPrimitive?.contentOrNull.orEmpty()
                runCatching { editingAccessController.configure(code) }
                    .onSuccess { call.respond(editingAccessController.refresh().toResponse()) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, mapOf("error" to (it.message ?: "Could not configure editing access"))) }
            }

            post("/api/editing-access/unlock") {
                val code = json.parseToJsonElement(call.receiveText()).jsonObject["code"]
                    ?.jsonPrimitive?.contentOrNull.orEmpty()
                val unlocked = editingAccessController.unlock(code)
                if (unlocked) call.respond(editingAccessController.refresh().toResponse())
                else call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Incorrect access code"))
            }

            post("/api/editing-access/lock") {
                editingAccessController.lock()
                call.respond(editingAccessController.refresh().toResponse())
            }

            post("/api/editing-access/disable") {
                val code = json.parseToJsonElement(call.receiveText()).jsonObject["code"]
                    ?.jsonPrimitive?.contentOrNull.orEmpty()
                if (editingAccessController.disable(code)) {
                    call.respond(editingAccessController.refresh().toResponse())
                } else {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Incorrect access code"))
                }
            }
            
            // Speech
            post("/api/speak") {
                try {
                    val body = call.receiveText()
                    val jsonObj = json.parseToJsonElement(body).jsonObject
                    val text = jsonObj["text"]?.jsonPrimitive?.contentOrNull ?: ""
                    if (text.isBlank()) {
                        return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "text is required"))
                    }

                    val generation = speechGeneration.incrementAndGet()
                    speechJob?.cancel()
                    speechState = SpeechStateResponse(state = "playing", playing = true)
                    speechJob = scope.launch {
                        performSpeech(generation, text)
                    }
                    call.respond(HttpStatusCode.Accepted, speechState)
                } catch (e: Exception) {
                    println("[SPEECH] /api/speak error: ${e.message}")
                    e.printStackTrace()
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
                }
            }
            
            // Azure Config
            get("/api/azure-config") {
                val status = configRepository.getSpeechConfigStatus()
                call.respond(
                    mapOf(
                        "endpoint" to status.endpoint,
                        "credentialConfigured" to status.credentialConfigured
                    )
                )
            }
            
            post("/api/azure-config") {
                val params = call.receive<Map<String, String>>()
                val endpoint = params["endpoint"] ?: ""
                val key = params["key"] ?: ""
                azureConfigManager.updateConfig(endpoint, key)
                
                try {
                    // Sync fetch voices
                    azureConfigManager.fetchAndSaveVoices(SpeechServiceConfig(endpoint, key))
                } catch (e: Exception) {
                    println("Failed to fetch voices: ${e.message}")
                }
                
                call.respond(HttpStatusCode.OK)
            }

            delete("/api/azure-config") {
                configRepository.clearSpeechConfig()
                call.respond(HttpStatusCode.OK)
            }
            
            // Voices
            get("/api/voices") {
                val voices = voiceRepository.getVoices()
                call.respond(voices)
            }
            get("/api/voices/selected") {
                val selected = voiceRepository.getSelected()
                if (selected == null) {
                    call.respond(HttpStatusCode.OK, mapOf<String, Any?>())
                } else {
                    call.respond(selected)
                }
            }
            post("/api/voices/preview") {
                val body = json.parseToJsonElement(call.receiveText()).jsonObject
                val voiceName = body["voice"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val text = body["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (voiceName.isBlank() || text.isBlank()) {
                    return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "voice and text are required"))
                }
                if (voiceRepository.getVoices().none { it.name == voiceName }) {
                    return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Voice is unavailable"))
                }
                val generation = speechGeneration.incrementAndGet()
                speechJob?.cancel()
                speechState = SpeechStateResponse(state = "playing", playing = true)
                speechJob = scope.launch {
                    performSpeech(generation, text, voiceName, recordHistory = false)
                }
                call.respond(HttpStatusCode.Accepted, speechState)
            }
            post("/api/speak/stop") {
                speechGeneration.incrementAndGet()
                speechJob?.cancel()
                speechJob = null
                speechService.stop()
                azureSpeechService.stop()
                speechState = SpeechStateResponse(state = "cancelled")
                call.respond(HttpStatusCode.OK)
            }

            post("/api/speak/pause") {
                speechService.pause()
                azureSpeechService.pause()
                val paused = speechService.isPaused() || azureSpeechService.isPaused()
                if (!paused) {
                    return@post call.respond(HttpStatusCode.Conflict, mapOf("error" to "Playback is not ready to pause"))
                }
                speechState = speechState.copy(state = "paused", playing = false, paused = true)
                call.respond(HttpStatusCode.OK)
            }

            post("/api/speak/resume") {
                speechService.resume()
                azureSpeechService.resume()
                val playing = speechService.isPlaying() || azureSpeechService.isPlaying()
                if (!playing) {
                    return@post call.respond(HttpStatusCode.Conflict, mapOf("error" to "Playback is not paused"))
                }
                speechState = speechState.copy(state = "playing", playing = true, paused = false)
                call.respond(HttpStatusCode.OK)
            }
            
            get("/api/speak/status") {
                call.respond(speechState)
            }
            
            // Pronunciation Dictionary
            get("/api/pronunciation") {
                val entries = pronunciationRepository.getAll()
                call.respond(entries)
            }

            get("/api/pronunciation/export") {
                val entries = pronunciationRepository.getAll()
                if (call.request.queryParameters["format"] == "csv") {
                    val csv = buildString {
                        appendLine("word,phoneme,alphabet")
                        entries.forEach { entry ->
                            appendLine(listOf(entry.word, entry.phoneme, entry.alphabet).joinToString(",", transform = ::csvField))
                        }
                    }
                    call.respondText(csv, ContentType.parse("text/csv"))
                } else {
                    call.respondText(json.encodeToString(entries), ContentType.Application.Json)
                }
            }

            post("/api/pronunciation/import") {
                try {
                    val path = json.parseToJsonElement(call.receiveText()).jsonObject["path"]
                        ?.jsonPrimitive?.contentOrNull.orEmpty()
                    val file = File(path)
                    require(file.isFile) { "Dictionary file does not exist" }
                    require(file.length() <= 5L * 1024L * 1024L) { "Dictionary file is too large" }
                    val entries = if (file.extension.equals("csv", ignoreCase = true)) {
                        file.readLines().dropWhile { it.isBlank() }.drop(1).mapNotNull { line ->
                            val fields = parseCsvLine(line)
                            if (fields.size < 2) null else PronunciationEntry(
                                word = fields[0].trim(),
                                phoneme = fields[1].trim(),
                                alphabet = fields.getOrNull(2)?.trim()?.ifBlank { "text" } ?: "text",
                            )
                        }
                    } else {
                        json.decodeFromString<List<PronunciationEntry>>(file.readText())
                    }
                    entries.filter { it.word.isNotBlank() && it.phoneme.isNotBlank() }
                        .forEach { pronunciationRepository.add(it) }
                    call.respond(HttpStatusCode.OK, mapOf("imported" to entries.size))
                } catch (error: Throwable) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (error.message ?: "dictionary import failed")))
                }
            }

            get("/api/history") {
                call.respond(saidTextRepository.list().filter { it.visibleInHistory }.sortedByDescending { it.date ?: it.createdAt ?: 0L })
            }

            delete("/api/history") {
                saidTextRepository.deleteAll()
                call.respond(HttpStatusCode.OK)
            }

            get("/api/history/export") {
                call.respondText(userDataManager.exportData(), ContentType.Application.Json, HttpStatusCode.OK)
            }

            post("/api/history/import") {
                userDataManager.importData(call.receiveText())
                trainPredictionModel()
                call.respond(HttpStatusCode.OK)
            }

            // Full backup/restore (settings, phrases, boards, categories, voices, history)
            get("/api/backup/export") {
                try {
                    val bytes = GlobalContext.get().get<io.github.jdreioe.wingmate.application.CompleteBackupManager>().exportBackup()
                    call.respond(
                        HttpStatusCode.OK,
                        mapOf("fileName" to "wingmate-backup.wingmate-backup", "data" to java.util.Base64.getEncoder().encodeToString(bytes))
                    )
                } catch (error: Throwable) {
                    error.printStackTrace()
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (error.message ?: "export failed")))
                }
            }

            post("/api/backup/import") {
                try {
                    val body = json.parseToJsonElement(call.receiveText()).jsonObject
                    val path = body["path"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (path.isBlank()) return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing path"))
                    val result = GlobalContext.get().get<io.github.jdreioe.wingmate.application.CompleteBackupManager>().restoreBackup(path)
                    val message = when (result) {
                        is io.github.jdreioe.wingmate.application.BackupRestoreResult.Success -> {
                            trainPredictionModel()
                            "ok"
                        }
                        is io.github.jdreioe.wingmate.application.BackupRestoreResult.Failure -> result.message
                    }
                    call.respond(HttpStatusCode.OK, mapOf("status" to message))
                } catch (error: Throwable) {
                    error.printStackTrace()
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (error.message ?: "import failed")))
                }
            }

            // OpenSymbols symbol search
            post("/api/symbols/search") {
                try {
                    val body = json.parseToJsonElement(call.receiveText()).jsonObject
                    val query = body["query"]?.jsonPrimitive?.contentOrNull ?: ""
                    val locale = body["locale"]?.jsonPrimitive?.contentOrNull ?: "en"
                    val result = OpenSymbolsClient.search(query, locale)
                    val symbols = when (result) {
                        is OpenSymbolsClient.SearchResponse.Success -> result.symbols.map {
                            mapOf("id" to it.id, "name" to it.name, "imageUrl" to it.image_url)
                        }
                        is OpenSymbolsClient.SearchResponse.Failure -> emptyList<Map<String, Any?>>()
                    }
                    call.respond(mapOf("symbols" to symbols))
                } catch (error: Throwable) {
                    call.respond(HttpStatusCode.OK, mapOf("symbols" to emptyList<Map<String, Any?>>()))
                }
            }

            // Proxy-fetch an image URL through the shared HTTP client, returning base64 bytes.
            // Lets the Rust UI render remote symbol images without its own HTTP/network stack.
            post("/api/images/fetch") {
                try {
                    val body = json.parseToJsonElement(call.receiveText()).jsonObject
                    val url = body["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (url.isBlank()) return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing url"))
                    val localFile = localImageFile(url)
                    val cacheFile = File(imageCacheDirectory(), sha256(url))
                    val bytes: ByteArray
                    val contentType: String
                    if (localFile != null) {
                        require(localFile.isFile) { "Image file does not exist" }
                        require(localFile.length() <= MAX_IMAGE_BYTES) { "Image is too large" }
                        bytes = localFile.readBytes()
                        contentType = Files.probeContentType(localFile.toPath()) ?: "image/png"
                    } else if (cacheFile.isFile) {
                        bytes = cacheFile.readBytes()
                        contentType = "image/png"
                    } else {
                        val client = io.ktor.client.HttpClient()
                        try {
                            val response = client.get(url)
                            bytes = response.bodyAsChannel().readRemaining(MAX_IMAGE_BYTES + 1).readBytes()
                            require(bytes.size <= MAX_IMAGE_BYTES) { "Image is too large" }
                            contentType = response.contentType()?.toString() ?: "image/png"
                            cacheFile.parentFile.mkdirs()
                            cacheFile.writeBytes(bytes)
                        } finally {
                            client.close()
                        }
                    }
                    call.respond(mapOf(
                        "data" to java.util.Base64.getEncoder().encodeToString(bytes),
                        "contentType" to contentType,
                    ))
                } catch (error: Throwable) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (error.message ?: "image fetch failed")))
                }
            }

            post("/api/images/import") {
                try {
                    val sourcePath = json.parseToJsonElement(call.receiveText()).jsonObject["path"]
                        ?.jsonPrimitive?.contentOrNull.orEmpty()
                    val source = File(sourcePath)
                    require(source.isFile) { "Image file does not exist" }
                    require(source.length() <= MAX_IMAGE_BYTES) { "Image is too large" }
                    val extension = source.extension.lowercase().takeIf { it in setOf("png", "jpg", "jpeg", "svg") }
                        ?: "img"
                    val destination = File(imageDataDirectory(), "${java.util.UUID.randomUUID()}.$extension")
                    destination.parentFile.mkdirs()
                    Files.copy(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    call.respond(HttpStatusCode.Created, mapOf("url" to destination.toURI().toString()))
                } catch (error: Throwable) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (error.message ?: "image import failed")))
                }
            }

            // Screen / board-set library and editor
            get("/api/boardsets") {
                call.respond(boardSetUseCase.listBoardSets())
            }

            post("/api/boardsets") {
                val body = json.parseToJsonElement(call.receiveText()).jsonObject
                val name = body["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (name.isBlank()) return@post call.respond(HttpStatusCode.BadRequest)
                val template = body["template"]?.jsonPrimitive?.contentOrNull ?: "blank"
                val created = if (template.equals("calculator", ignoreCase = true)) {
                    boardSetUseCase.createCalculatorBoardSet(name)
                } else {
                    boardSetUseCase.createBoardSet(
                        name,
                        body["rows"]?.jsonPrimitive?.intOrNull ?: 4,
                        body["columns"]?.jsonPrimitive?.intOrNull ?: 4
                    )
                }
                call.respond(HttpStatusCode.Created, created)
            }

            post("/api/boardsets/import") {
                val path = json.parseToJsonElement(call.receiveText()).jsonObject["path"]?.jsonPrimitive?.contentOrNull
                    ?: return@post call.respond(HttpStatusCode.BadRequest)
                val imported = boardImportService.importBoardSetFromPath(path)
                    ?: return@post call.respond(HttpStatusCode.BadRequest)
                call.respond(HttpStatusCode.Created, imported)
            }

            get("/api/boardsets/{id}") {
                try {
                    val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val graph = boardSetUseCase.loadBoardSetGraph(id)
                        ?: return@get call.respond(HttpStatusCode.NotFound)
                    val appSettings = settingsManager.settings.value ?: Settings()
                    val resolvedSettings = graph.boards.associate { board ->
                        board.id to resolvedBoardSettingsResponse(
                            appSettings,
                            graph.boardSet,
                            board,
                        )
                    }
                    val fieldItems = graph.boards.associate { board ->
                        board.id to board.grid?.fieldItems().orEmpty().map { field ->
                            BoardFieldResponse(
                                row = field.row,
                                column = field.column,
                                rowSpan = field.rowSpan,
                                columnSpan = field.columnSpan,
                                buttonId = field.buttonId,
                            )
                        }
                    }
                    call.respondText(
                        json.encodeToString(
                            BoardSetGraphResponse(
                                graph.boardSet,
                                graph.boards,
                                resolvedSettings,
                                fieldItems,
                            )
                        ),
                        ContentType.Application.Json,
                        HttpStatusCode.OK
                    )
                } catch (error: Throwable) {
                    error.printStackTrace()
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (error.message ?: "unknown")))
                }
            }

            delete("/api/boardsets/{id}") {
                val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
                boardSetUseCase.deleteBoardSet(id)
                call.respond(HttpStatusCode.OK)
            }

            get("/api/boardsets/{id}/export") {
                val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                val bytes = boardSetUseCase.exportBoardSetAsObz(id)
                    ?: return@get call.respond(HttpStatusCode.NotFound)
                call.respondBytes(bytes, ContentType.Application.Zip, HttpStatusCode.OK)
            }

            post("/api/boardsets/{id}/duplicate") {
                val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                val duplicated = boardSetUseCase.duplicateBoardSet(id)
                    ?: return@post call.respond(HttpStatusCode.NotFound)
                call.respond(HttpStatusCode.Created, duplicated)
            }

            put("/api/boardsets/{id}/lock") {
                val id = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest)
                val updated = boardSetUseCase.toggleLocked(id)
                    ?: return@put call.respond(HttpStatusCode.NotFound)
                call.respond(updated)
            }

            put("/api/boardsets/{id}/name") {
                val id = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest)
                val name = json.parseToJsonElement(call.receiveText()).jsonObject["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val updated = boardSetUseCase.renameBoardSet(id, name)
                    ?: return@put call.respond(HttpStatusCode.BadRequest)
                call.respond(updated)
            }

            post("/api/boardsets/{id}/boards") {
                val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                val body = json.parseToJsonElement(call.receiveText()).jsonObject
                val board = boardSetUseCase.createBoard(
                    id,
                    body["name"]?.jsonPrimitive?.contentOrNull ?: "Page",
                    body["rows"]?.jsonPrimitive?.intOrNull ?: 4,
                    body["columns"]?.jsonPrimitive?.intOrNull ?: 4
                ) ?: return@post call.respond(HttpStatusCode.BadRequest)
                call.respond(HttpStatusCode.Created, board)
            }

            put("/api/boardsets/{setId}/boards/{boardId}/name") {
                val setId = call.parameters["setId"] ?: return@put call.respond(HttpStatusCode.BadRequest)
                val boardId = call.parameters["boardId"] ?: return@put call.respond(HttpStatusCode.BadRequest)
                val name = json.parseToJsonElement(call.receiveText()).jsonObject["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val board = boardSetUseCase.renameBoard(setId, boardId, name)
                    ?: return@put call.respond(HttpStatusCode.BadRequest)
                call.respond(board)
            }

            put("/api/boardsets/{setId}/boards/{boardId}/size") {
                val setId = call.parameters["setId"] ?: return@put call.respond(HttpStatusCode.BadRequest)
                val boardId = call.parameters["boardId"] ?: return@put call.respond(HttpStatusCode.BadRequest)
                val body = json.parseToJsonElement(call.receiveText()).jsonObject
                val board = boardSetUseCase.resizeBoard(
                    setId, boardId,
                    body["rows"]?.jsonPrimitive?.intOrNull ?: 4,
                    body["columns"]?.jsonPrimitive?.intOrNull ?: 4
                ) ?: return@put call.respond(HttpStatusCode.BadRequest)
                call.respond(board)
            }

            put("/api/boardsets/{setId}/boards/{boardId}/settings") {
                val setId = call.parameters["setId"] ?: return@put call.respond(HttpStatusCode.BadRequest)
                val boardId = call.parameters["boardId"] ?: return@put call.respond(HttpStatusCode.BadRequest)
                val boardSet = boardSetRepository.getBoardSet(setId) ?: return@put call.respond(HttpStatusCode.NotFound)
                if (boardSet.isLocked || boardId !in boardSet.boardIds) return@put call.respond(HttpStatusCode.Conflict)
                val board = boardRepository.getBoard(boardId) ?: return@put call.respond(HttpStatusCode.NotFound)
                val body = json.parseToJsonElement(call.receiveText()).jsonObject
                val current = board.pageSettingsOverrides()
                val settings = current.copy(
                    activationBehavior = body["activationBehavior"]?.jsonPrimitive?.contentOrNull
                        ?.let(::parseActivationBehavior) ?: current.activationBehavior,
                    returnBehavior = body["returnBehavior"]?.jsonPrimitive?.contentOrNull
                        ?.let(::parseReturnBehavior) ?: current.returnBehavior,
                )
                val updated = board.withPageSettingsOverrides(settings)
                boardRepository.saveBoard(updated)
                call.respond(updated)
            }

            delete("/api/boardsets/{setId}/boards/{boardId}") {
                val setId = call.parameters["setId"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
                val boardId = call.parameters["boardId"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
                boardSetUseCase.deleteBoard(setId, boardId)
                    ?: return@delete call.respond(HttpStatusCode.BadRequest)
                call.respond(HttpStatusCode.OK)
            }

            put("/api/boardsets/{setId}/boards/{boardId}/root") {
                val setId = call.parameters["setId"] ?: return@put call.respond(HttpStatusCode.BadRequest)
                val boardId = call.parameters["boardId"] ?: return@put call.respond(HttpStatusCode.BadRequest)
                boardSetUseCase.setRootBoard(setId, boardId)
                    ?: return@put call.respond(HttpStatusCode.BadRequest)
                call.respond(HttpStatusCode.OK)
            }

            put("/api/boardsets/{setId}/boards/{boardId}/cells/{row}/{column}") {
                val setId = call.parameters["setId"] ?: return@put call.respond(HttpStatusCode.BadRequest)
                val boardId = call.parameters["boardId"] ?: return@put call.respond(HttpStatusCode.BadRequest)
                val row = call.parameters["row"]?.toIntOrNull() ?: return@put call.respond(HttpStatusCode.BadRequest)
                val column = call.parameters["column"]?.toIntOrNull() ?: return@put call.respond(HttpStatusCode.BadRequest)
                val body = json.parseToJsonElement(call.receiveText()).jsonObject
                val existingBoard = boardRepository.getBoard(boardId)
                val existingId = existingBoard?.grid?.order?.getOrNull(row)?.getOrNull(column)
                val existing = existingBoard?.buttons?.firstOrNull { it.id == existingId }
                val board = boardSetUseCase.upsertBoardCellButton(
                    setId, boardId, row, column,
                    body["label"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    body["vocalization"]?.jsonPrimitive?.contentOrNull,
                    body["imageUrl"]?.jsonPrimitive?.contentOrNull,
                    body["backgroundColor"]?.jsonPrimitive?.contentOrNull ?: existing?.backgroundColor,
                    body["hidden"]?.jsonPrimitive?.booleanOrNull ?: existing?.hidden ?: false,
                    body["linkedBoardId"]?.jsonPrimitive?.contentOrNull ?: existing?.loadBoard?.id,
                    body["actions"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
                        ?: existing?.let { it.actions.ifEmpty { listOfNotNull(it.action) } }.orEmpty(),
                ) ?: return@put call.respond(HttpStatusCode.BadRequest)
                call.respond(board)
            }

            delete("/api/boardsets/{setId}/boards/{boardId}/cells/{row}/{column}") {
                val setId = call.parameters["setId"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
                val boardId = call.parameters["boardId"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
                val row = call.parameters["row"]?.toIntOrNull() ?: return@delete call.respond(HttpStatusCode.BadRequest)
                val column = call.parameters["column"]?.toIntOrNull() ?: return@delete call.respond(HttpStatusCode.BadRequest)
                val board = boardSetUseCase.clearBoardCellButton(setId, boardId, row, column)
                    ?: return@delete call.respond(HttpStatusCode.BadRequest)
                call.respond(board)
            }

            post("/api/board-session") {
                val body = json.parseToJsonElement(call.receiveText()).jsonObject
                val boardId = body["boardId"]?.jsonPrimitive?.contentOrNull
                    ?: return@post call.respond(HttpStatusCode.BadRequest)
                val operation = body["operation"]?.jsonPrimitive?.contentOrNull ?: "resolve"
                val board = boardRepository.getBoard(boardId)
                    ?: return@post call.respond(HttpStatusCode.NotFound)
                val boardSet = boardSetRepository.listBoardSets()
                    .firstOrNull { boardId in it.boardIds }
                val appSettings = settingsManager.settings.value ?: Settings()
                val resolved = resolveBoardSettings(
                    appShowLabels = appSettings.showLabels,
                    appShowSymbols = appSettings.showSymbols,
                    appLabelAtTop = appSettings.labelAtTop,
                    appShowMessageBar = appSettings.boardShowMessageBar,
                    appActivationBehavior = appSettings.boardActivationBehavior,
                    appReturnBehavior = appSettings.boardReturnBehavior,
                    screen = boardSet?.screenSettings
                        ?: io.github.jdreioe.wingmate.domain.obf.BoardSettingsOverrides(),
                    page = board.pageSettingsOverrides(),
                )
                var tokens = body["tokens"]?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    .orEmpty()
                var speakText: String? = null
                var navigateHome = false
                var navigateBoardId: String? = null
                val unsupportedActions = mutableListOf<String>()

                when (operation) {
                    "activate" -> {
                        val buttonId = body["buttonId"]?.jsonPrimitive?.contentOrNull
                        val button = board.buttons.firstOrNull { it.id == buttonId }
                            ?: return@post call.respond(HttpStatusCode.NotFound)
                        val actions = parseObfButtonActions(button)
                        if (actions.isNotEmpty()) {
                            for (effect in actions) {
                                when (effect) {
                                    is ObfButtonActionEffect.AppendText -> {
                                        if (effect.text.isNotEmpty()) tokens = tokens + effect.text
                                    }
                                    ObfButtonActionEffect.Backspace -> {
                                        tokens = backspaceSentenceSelection(tokens)
                                    }
                                    ObfButtonActionEffect.Clear -> tokens = emptyList()
                                    ObfButtonActionEffect.Speak -> {
                                        speakText = joinSentenceText(tokens, board.spellingMode)
                                            .takeIf { it.isNotBlank() }
                                    }
                                    ObfButtonActionEffect.Home -> navigateHome = true
                                    ObfButtonActionEffect.Predictions -> {
                                        val predictionIds = orderedPredictionButtonIds(board, false)
                                        val predictionIndex = predictionIds.indexOf(button.id)
                                        val sentence = joinSentenceText(tokens, board.spellingMode)
                                        val prediction = predictionIndex
                                            .takeIf { it >= 0 && predictionService.isTrained() }
                                            ?.let { index ->
                                                predictionService.predict(
                                                    sentence,
                                                    maxWords = predictionIds.size,
                                                    maxLetters = 0,
                                                ).words.getOrNull(index)
                                            }
                                        val insertion = prediction?.let {
                                            nGramPredictionInsertion(sentence, it)
                                        }
                                        if (!insertion.isNullOrEmpty()) tokens = tokens + insertion
                                    }
                                    is ObfButtonActionEffect.Unsupported -> {
                                        unsupportedActions += effect.action
                                    }
                                }
                            }
                        } else if (button.loadBoard != null && boardSet != null) {
                            navigateBoardId = boardSetUseCase
                                .loadBoardSetGraph(boardSet.id)
                                ?.resolveLinkedBoard(button.loadBoard)
                                ?.id
                        } else {
                            val text = resolveObfLocalizedString(
                                strings = board.strings,
                                locale = appSettings.primaryLanguage,
                                rawValue = button.vocalization ?: button.label,
                            )?.trim().orEmpty()
                            if (
                                text.isNotEmpty() &&
                                shouldAddBoardSelection(resolved.activationBehavior)
                            ) {
                                tokens = tokens + text
                            }
                            if (
                                text.isNotEmpty() &&
                                shouldSpeakBoardSelection(resolved.activationBehavior)
                            ) {
                                speakText = text
                            }
                        }
                    }
                    "backspace" -> tokens = backspaceSentenceSelection(tokens)
                    "clear" -> tokens = emptyList()
                }

                call.respond(
                    BoardSessionResponse(
                        tokens = tokens,
                        sentence = joinSentenceText(tokens, board.spellingMode),
                        speakText = speakText,
                        navigateHome = navigateHome,
                        navigateBoardId = navigateBoardId,
                        unsupportedActions = unsupportedActions,
                        settings = ResolvedBoardSettingsResponse(
                            showLabels = resolved.showLabels,
                            showSymbols = resolved.showSymbols,
                            labelAtTop = resolved.labelAtTop,
                            showMessageBar = resolved.showMessageBar,
                            activationBehavior = resolved.activationBehavior.name,
                            returnBehavior = resolved.returnBehavior.name,
                        ),
                    )
                )
            }
            
            post("/api/pronunciation") {
                val params = call.receive<Map<String, String>>()
                val word = params["word"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                val phoneme = params["phoneme"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                val alphabet = params["alphabet"] ?: "text"
                pronunciationRepository.add(PronunciationEntry(word = word, phoneme = phoneme, alphabet = alphabet))
                call.respond(HttpStatusCode.Created, mapOf("status" to "ok"))
            }
            
            delete("/api/pronunciation/{word}") {
                val word = call.parameters["word"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
                pronunciationRepository.delete(word)
                call.respond(HttpStatusCode.OK)
            }
            
            // Pitch setting
            put("/api/settings/pitch") {
                val params = call.receive<Map<String, Float>>()
                val pitch = params["pitch"] ?: 1.0f
                // Store pitch in settings (extend Settings if needed, for now just ack)
                call.respond(HttpStatusCode.OK)
            }

            
            // Partner Window
            put("/api/settings/partnerwindow") {
                val body = call.receiveText()
                val jsonObj = json.parseToJsonElement(body).jsonObject
                val enabled = jsonObj["enabled"]?.jsonPrimitive?.booleanOrNull ?: false
                
                val current = settingsManager.settings.value ?: io.github.jdreioe.wingmate.domain.Settings()
                settingsManager.updateSettings(current.copy(partnerWindowEnabled = enabled))
                
                call.respond(HttpStatusCode.OK)
            }

            put("/api/settings/partnerwindow-display") {
                val body = call.receiveText()
                val jsonObj = json.parseToJsonElement(body).jsonObject
                val fontSize = jsonObj["fontSize"]?.jsonPrimitive?.intOrNull
                val maxLines = jsonObj["maxLines"]?.jsonPrimitive?.intOrNull
                val idleEnabled = jsonObj["idleEnabled"]?.jsonPrimitive?.booleanOrNull

                val current = settingsManager.settings.value ?: io.github.jdreioe.wingmate.domain.Settings()
                settingsManager.updateSettings(current.copy(
                    partnerWindowFontSize = fontSize ?: current.partnerWindowFontSize,
                    partnerWindowMaxLines = maxLines ?: current.partnerWindowMaxLines,
                    partnerWindowIdleEnabled = idleEnabled ?: current.partnerWindowIdleEnabled
                ))

                call.respond(HttpStatusCode.OK)
            }
            
            put("/api/display-text") {
                val body = call.receiveText()
                val jsonObj = json.parseToJsonElement(body).jsonObject
                val text = jsonObj["text"]?.jsonPrimitive?.contentOrNull ?: ""
                
                partnerWindowManager.updateText(text)
                call.respond(HttpStatusCode.OK)
            }

            // OSK settings
            put("/api/settings/osk") {
                val body = call.receiveText()
                val jsonObj = json.parseToJsonElement(body).jsonObject
                val oskScale = jsonObj["oskKeyboardScale"]?.jsonPrimitive?.floatOrNull

                val current = settingsManager.settings.value ?: io.github.jdreioe.wingmate.domain.Settings()
                settingsManager.updateSettings(current.copy(
                    oskKeyboardScale = oskScale ?: current.oskKeyboardScale
                ))

                call.respond(HttpStatusCode.OK)
            }

            // Text Prediction
            post("/api/predict") {
                try {
                    val body = call.receiveText()
                    val jsonObj = json.parseToJsonElement(body).jsonObject
                    val context = jsonObj["context"]?.jsonPrimitive?.contentOrNull ?: ""
                    val maxWords = jsonObj["maxWords"]?.jsonPrimitive?.intOrNull ?: 5
                    val maxLetters = jsonObj["maxLetters"]?.jsonPrimitive?.intOrNull ?: 5

                    val result = predictionService.predict(context, maxWords, maxLetters)
                    call.respond(mapOf(
                        "words" to result.words,
                        "letters" to result.letters.map { it.toString() }
                    ))
                } catch (e: Exception) {
                    println("[PREDICT] Error: ${e.message}")
                    call.respond(HttpStatusCode.OK, mapOf("words" to emptyList<String>(), "letters" to emptyList<String>()))
                }
            }

            post("/api/predict/train") {
                scope.launch {
                    try {
                        trainPredictionModel()
                    } catch (e: Exception) {
                        println("[PREDICT] Training error: ${e.message}")
                    }
                }
                call.respond(HttpStatusCode.OK, mapOf("status" to "training"))
            }

            post("/api/predict/learn") {
                val body = call.receiveText()
                val jsonObj = json.parseToJsonElement(body).jsonObject
                val text = jsonObj["text"]?.jsonPrimitive?.contentOrNull ?: ""
                if (text.isNotBlank() && predictionService is SimpleNGramPredictionService) {
                    scope.launch {
                        (predictionService as SimpleNGramPredictionService).learnPhrase(text)
                    }
                }
                call.respond(HttpStatusCode.OK)
            }

            // Shared prediction-insertion logic (same as Android/iOS): completes the
            // current word when the suggestion extends it, otherwise adds a new word.
            post("/api/predict/insert") {
                try {
                    val body = call.receiveText()
                    val jsonObj = json.parseToJsonElement(body).jsonObject
                    val sentence = jsonObj["sentence"]?.jsonPrimitive?.contentOrNull ?: ""
                    val suggestion = jsonObj["suggestion"]?.jsonPrimitive?.contentOrNull ?: ""
                    call.respond(mapOf("insertion" to nGramPredictionInsertion(sentence, suggestion)))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.OK, mapOf("insertion" to ""))
                }
            }

            get("/api/predict/status") {
                call.respond(mapOf("trained" to predictionService.isTrained()))
            }
        }
    }
    
    fun start(skipPartnerWindow: Boolean = false) {
        server.start(wait = false)
        scope.launch {
            runCatching { trainPredictionModel() }
                .onFailure { println("[PREDICT] Bootstrap training failed: ${it.message}") }
        }
        if (!skipPartnerWindow) {
            partnerWindowManager.start()
        }
        println("Kotlin bridge server started on http://localhost:$port")
    }
    
    fun stop() {
        phraseViewModel.cleanup()
        settingsManager.cleanup()
        partnerWindowManager.stop()
        server.stop(1000, 2000)
    }

    private suspend fun performSpeech(
        generation: Long,
        text: String,
        voiceOverride: String? = null,
        recordHistory: Boolean = true,
    ) {
        try {
            speechService.stop()
            azureSpeechService.stop()

            val settings = settingsManager.settings.value ?: Settings()
            val voices = voiceRepository.getVoices()
            val selectedName = voiceOverride?.takeIf { it.isNotBlank() }
                ?: voiceRepository.getSelected()?.name?.takeIf { it.isNotBlank() }
                ?: settings.voice.takeIf { it.isNotBlank() }
            val language = settings.primaryLanguage.takeIf { it.isNotBlank() }
                ?: settings.language.takeIf { it.isNotBlank() }
                ?: "en-US"
            val selected = voices.firstOrNull { it.name == selectedName }
                ?: voices.firstOrNull()
                ?: Voice(name = "en-US-JennyNeural", selectedLanguage = language)
            val rate = settings.speechRate.toDouble()
            val voice = selected.copy(selectedLanguage = language, rate = rate)

            if (settings.ttsEngine == TtsEngine.SYSTEM) {
                speechService.speakSegments(SpeechTextProcessor.processText(text), voice, rate = rate)
            } else {
                azureSpeechService.speak(text, voice, rate = rate)
            }

            if (speechGeneration.get() != generation) return

            if (recordHistory) {
                val now = System.currentTimeMillis()
                saidTextRepository.add(
                    SaidText(
                        date = now,
                        createdAt = now,
                        saidText = text,
                        voiceName = voice.name,
                        speed = rate,
                        primaryLanguage = language,
                        visibleInHistory = true,
                    )
                )
                if (predictionService is SimpleNGramPredictionService) {
                    (predictionService as SimpleNGramPredictionService).learnPhrase(text)
                }
            }
            speechState = SpeechStateResponse(state = "completed")
            speechJob = null
        } catch (error: Throwable) {
            if (speechGeneration.get() == generation) {
                val message = error.message ?: "Speech failed"
                println("[SPEECH] $message")
                error.printStackTrace()
                speechState = SpeechStateResponse(state = "error", error = message)
                speechJob = null
            }
        }
    }

    private suspend fun trainPredictionModel() {
        val history = saidTextRepository.list()
        predictionService.train(history)
        val settings = settingsManager.settings.value
        val language = settings?.primaryLanguage?.takeIf { it.isNotBlank() }
            ?: settings?.language?.takeIf { it.isNotBlank() }
            ?: "en-US"
        try {
            val words = dictionaryLoader.loadDictionary(language)
            if (words.isNotEmpty() && predictionService is SimpleNGramPredictionService) {
                (predictionService as SimpleNGramPredictionService).setBaseLanguage(words)
                (predictionService as SimpleNGramPredictionService).train(history, false)
            }
        } catch (error: Exception) {
            println("[PREDICT] Dictionary load failed (non-fatal): ${error.message}")
        }
        println("[PREDICT] Trained on ${history.size} history entries")
    }
}

@Serializable
data class AddPhraseRequest(val text: String, val imageUrl: String? = null)

@Serializable
data class AddCategoryRequest(val name: String)

@Serializable
data class SelectCategoryRequest(val categoryId: String?)

@Serializable
data class UpdateLanguageRequest(val language: String)

@Serializable
data class UpdateVoiceRequest(val voice: String)

@Serializable
data class SpeakRequest(val text: String)

@Serializable
data class SpeechStateResponse(
    val state: String = "idle",
    val playing: Boolean = false,
    val paused: Boolean = false,
    val error: String? = null,
)

@Serializable
data class EditingAccessResponse(
    val enabled: Boolean = false,
    val unlocked: Boolean = true,
    val supported: Boolean = false,
    val failedAttempts: Int = 0,
)

private fun io.github.jdreioe.wingmate.application.EditingAccessState.toResponse() =
    EditingAccessResponse(enabled, unlocked, supported, failedAttempts)

private fun resolvedBoardSettingsResponse(
    appSettings: Settings,
    boardSet: ObfBoardSet,
    board: ObfBoard,
): ResolvedBoardSettingsResponse {
    val resolved = resolveBoardSettings(
        appShowLabels = appSettings.showLabels,
        appShowSymbols = appSettings.showSymbols,
        appLabelAtTop = appSettings.labelAtTop,
        appShowMessageBar = appSettings.boardShowMessageBar,
        appActivationBehavior = appSettings.boardActivationBehavior,
        appReturnBehavior = appSettings.boardReturnBehavior,
        screen = boardSet.screenSettings,
        page = board.pageSettingsOverrides(),
    )
    return ResolvedBoardSettingsResponse(
        showLabels = resolved.showLabels,
        showSymbols = resolved.showSymbols,
        labelAtTop = resolved.labelAtTop,
        showMessageBar = resolved.showMessageBar,
        activationBehavior = resolved.activationBehavior.name,
        returnBehavior = resolved.returnBehavior.name,
    )
}

@Serializable
data class BoardSetGraphResponse(
    val boardSet: ObfBoardSet,
    val boards: List<ObfBoard>,
    val resolvedSettings: Map<String, ResolvedBoardSettingsResponse> = emptyMap(),
    val fieldItems: Map<String, List<BoardFieldResponse>> = emptyMap(),
)

@Serializable
data class BoardFieldResponse(
    val row: Int,
    val column: Int,
    val rowSpan: Int,
    val columnSpan: Int,
    val buttonId: String? = null,
)

@Serializable
data class ResolvedBoardSettingsResponse(
    val showLabels: Boolean,
    val showSymbols: Boolean,
    val labelAtTop: Boolean,
    val showMessageBar: Boolean,
    val activationBehavior: String,
    val returnBehavior: String,
)

@Serializable
data class BoardSessionResponse(
    val tokens: List<String>,
    val sentence: String,
    val speakText: String? = null,
    val navigateHome: Boolean = false,
    val navigateBoardId: String? = null,
    val unsupportedActions: List<String> = emptyList(),
    val settings: ResolvedBoardSettingsResponse,
)

private const val MAX_IMAGE_BYTES = 20L * 1024L * 1024L

private fun imageCacheDirectory(): File =
    File(System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }
        ?: File(System.getProperty("user.home"), ".cache").path, "wingmate/images")

private fun imageDataDirectory(): File =
    File(System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
        ?: File(System.getProperty("user.home"), ".local/share").path, "wingmate/images")

private fun localImageFile(source: String): File? = when {
    source.startsWith("file:") -> runCatching { File(URI(source)) }.getOrNull()
    source.startsWith('/') -> File(source)
    else -> null
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.encodeToByteArray())
    .joinToString("") { "%02x".format(it) }

private fun parseActivationBehavior(value: String): BoardActivationBehavior? = when (value.lowercase()) {
    "speak_and_add", "speakandadd" -> BoardActivationBehavior.SpeakAndAdd
    "add_only", "addonly" -> BoardActivationBehavior.AddOnly
    "speak_only", "speakonly" -> BoardActivationBehavior.SpeakOnly
    else -> null
}

private fun parseReturnBehavior(value: String): BoardReturnBehavior? = when (value.lowercase()) {
    "stay" -> BoardReturnBehavior.Stay
    "previous" -> BoardReturnBehavior.Previous
    "start_page", "startpage" -> BoardReturnBehavior.StartPage
    else -> null
}

private fun csvField(value: String): String = "\"${value.replace("\"", "\"\"")}\""

private fun parseCsvLine(line: String): List<String> {
    val fields = mutableListOf<String>()
    val current = StringBuilder()
    var quoted = false
    var index = 0
    while (index < line.length) {
        val char = line[index]
        when {
            char == '"' && quoted && line.getOrNull(index + 1) == '"' -> {
                current.append('"')
                index += 1
            }
            char == '"' -> quoted = !quoted
            char == ',' && !quoted -> {
                fields += current.toString()
                current.clear()
            }
            else -> current.append(char)
        }
        index += 1
    }
    fields += current.toString()
    return fields
}



/**
 * Main entry point for the Kotlin bridge service.
 */
fun main(args: Array<String>) {
    val noPartnerWindow = "--no-partner-window" in args

    println("[PERSISTENCE] Starting Wingmate Linux Bridge...")
    if (noPartnerWindow) {
        println("[PartnerWindow] Disabled (managed by Rust driver)")
    }
    // Defines persistence module
    val persistenceModule = module {
        single<io.github.jdreioe.wingmate.domain.SettingsRepository> { JsonFileSettingsRepository() }
        single<io.github.jdreioe.wingmate.domain.ConfigRepository> { JsonFileConfigRepository() }
        single<io.github.jdreioe.wingmate.domain.VoiceRepository> { JsonFileVoiceRepository() }
        single<io.github.jdreioe.wingmate.domain.PhraseRepository> { JsonFilePhraseRepository() }
        single<io.github.jdreioe.wingmate.domain.CategoryRepository> { JsonFileCategoryRepository() }
        single<io.github.jdreioe.wingmate.domain.PronunciationDictionaryRepository> {
            JsonFilePronunciationDictionaryRepository()
        }
        single<io.github.jdreioe.wingmate.domain.BoardRepository> { JsonFileBoardRepository() }
        single<io.github.jdreioe.wingmate.domain.BoardSetRepository> { JsonFileBoardSetRepository() }
        single<io.github.jdreioe.wingmate.domain.TextPredictionService> { SimpleNGramPredictionService() }
        single<io.github.jdreioe.wingmate.platform.FilePicker> { LinuxFilePicker() }
        single<io.github.jdreioe.wingmate.application.BackupMediaAccess> { LinuxBackupMediaAccess() }
        single<SecureEditingCredentialStorage> { LinuxSecureEditingCredentialStorage() }
    }

    // Initialize Koin DI with overrides
    initKoin(persistenceModule)

    // Configure the public OpenSymbols proxy endpoint. No API secret enters the client.
    val openSymbolsProxyUrl = sequenceOf(
        System.getenv("WINGMATE_OPENSYMBOLS_PROXY_URL"),
        System.getenv("OPENSYMBOLS_PROXY_URL"),
        "https://wingmate-opensymbols-proxy.patient-mouse-467e.workers.dev",
    ).firstOrNull { !it.isNullOrBlank() }
    OpenSymbolsClient.setProxyBaseUrl(openSymbolsProxyUrl)
    if (openSymbolsProxyUrl.isNullOrBlank()) {
        println("[SYMBOLS] OpenSymbols proxy not configured; symbol search disabled")
    } else {
        println("[SYMBOLS] OpenSymbols proxy configured")
    }
    
    val bridge = KotlinBridge()
    bridge.start(skipPartnerWindow = noPartnerWindow)
    
    // Keep running
    Runtime.getRuntime().addShutdownHook(Thread {
        bridge.stop()
    })
    
    Thread.currentThread().join()
}
