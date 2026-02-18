package io.github.jdreioe.wingmate.kde

import io.github.jdreioe.wingmate.initKoin
import org.koin.dsl.module
import io.ktor.http.*
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
import io.github.jdreioe.wingmate.domain.Voice
import io.github.jdreioe.wingmate.domain.VoiceRepository
import org.koin.core.context.GlobalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/**
 * HTTP server that bridges QML UI with Kotlin business logic.
 * QML makes REST calls to this local server.
 */
class KotlinBridge(private val port: Int = 8765) {
    private val scope = CoroutineScope(Dispatchers.Default + kotlinx.coroutines.SupervisorJob())
    private val phraseViewModel = PhraseViewModel()
    private val settingsManager = SettingsManager()
    private val configRepository: ConfigRepository by lazy { GlobalContext.get().get() }
    private val azureConfigManager = AzureConfigManager()
    private val speechService = LinuxSpeechService()
    private val azureSpeechService = AzureSpeechService(configRepository)
    private val voiceRepository: VoiceRepository by lazy { GlobalContext.get().get() }
    private val pronunciationRepository: PronunciationDictionaryRepository by lazy { GlobalContext.get().get() }
    
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
            
            // Settings
            get("/api/settings") {
                val settings = settingsManager.settings.firstOrNull()
                call.respond(settings ?: mapOf("status" to "loading"))
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
                
                println("[API] Updating settings to: $newSettings")
                settingsManager.updateSettings(newSettings)
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
                val params = call.receive<Map<String, Boolean>>()
                val useSystem = params["useSystemTts"] ?: true
                val current = settingsManager.settings.value ?: io.github.jdreioe.wingmate.domain.Settings()
                settingsManager.updateSettings(current.copy(useSystemTts = useSystem))
                call.respond(HttpStatusCode.OK)
            }
            
            // Speech
            post("/api/speak") {
                try {
                    val body = call.receiveText()
                    println("[SPEECH] API /api/speak RAW body: '$body'")
                    
                    val jsonObj = json.parseToJsonElement(body).jsonObject
                    val text = jsonObj["text"]?.jsonPrimitive?.contentOrNull ?: ""
                    println("[SPEECH] API /api/speak parsed text: '$text'")
                    
                    scope.launch {
                        try {
                            val settings = settingsManager.settings.value
                            
                            // Resolve voice
                            val currentVoiceName = settings?.voice ?: "default"
                            val settingsLanguage = settings?.language ?: "en-US"
                            println("[SPEECH] Resolving voice. Content: '$currentVoiceName', Settings language: '$settingsLanguage'")
                            
                            val voices = voiceRepository.getVoices()
                            val foundVoice = voices.find { it.name == currentVoiceName } 
                                ?: voices.firstOrNull() 
                                ?: Voice(name="en-US-JennyNeural", selectedLanguage="en-US") // Default fallback
                            
                            // Set selectedLanguage to the user's settings language so SSML uses correct lang
                            val voice = foundVoice.copy(selectedLanguage = settingsLanguage)
                                
                            println("[SPEECH] Selected voice: ${voice.name}, Language: ${voice.selectedLanguage}")

                            // Logic: useSystemTts=true -> Piper/Local. useSystemTts=false -> Azure.
                            // Default is false, which means Azure.
                            if (settings?.useSystemTts == true) { // Use explicit true check
                                println("[SPEECH] Using System TTS (LinuxSpeechService)")
                                speechService.speak(text, voice)
                            } else {
                                println("[SPEECH] Using Azure TTS (AzureSpeechService)")
                                azureSpeechService.speak(text, voice)
                            }
                        } catch (e: Exception) {
                            println("Speech error inside launch: ${e.message}")
                            e.printStackTrace()
                        }
                    }
                    call.respond(HttpStatusCode.OK, mapOf("status" to "speaking"))
                } catch (e: Exception) {
                    println("[SPEECH] /api/speak error: ${e.message}")
                    e.printStackTrace()
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
                }
            }
            
            // Azure Config
            get("/api/azure-config") {
                val config = azureConfigManager.getConfig()
                call.respond(mapOf("endpoint" to config.endpoint, "key" to config.subscriptionKey))
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
            
            // Voices
            get("/api/voices") {
                val voices = voiceRepository.getVoices()
                call.respond(voices)
            }
            post("/api/speak/stop") {
                scope.launch {
                    speechService.stop()
                    azureSpeechService.stop()
                }
                call.respond(HttpStatusCode.OK)
            }
            
            get("/api/speak/status") {
                call.respond(mapOf(
                    "playing" to (speechService.isPlaying() || azureSpeechService.isPlaying()),
                    "paused" to (speechService.isPaused() || azureSpeechService.isPaused())
                ))
            }
            
            // Pronunciation Dictionary
            get("/api/pronunciation") {
                val entries = pronunciationRepository.getAll()
                call.respond(entries)
            }
            
            post("/api/pronunciation") {
                val params = call.receive<Map<String, String>>()
                val word = params["word"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                val phoneme = params["phoneme"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                pronunciationRepository.add(PronunciationEntry(word = word, phoneme = phoneme))
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
        }
    }
    
    fun start() {
        server.start(wait = false)
        println("Kotlin bridge server started on http://localhost:$port")
    }
    
    fun stop() {
        phraseViewModel.cleanup()
        settingsManager.cleanup()
        server.stop(1000, 2000)
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



/**
 * Main entry point for the Kotlin bridge service.
 */
fun main() {
    println("[PERSISTENCE] Starting Wingmate Linux Bridge...")
    // Defines persistence module
    val persistenceModule = module {
        single<io.github.jdreioe.wingmate.domain.SettingsRepository> { JsonFileSettingsRepository() }
        single<io.github.jdreioe.wingmate.domain.ConfigRepository> { JsonFileConfigRepository() }
        single<io.github.jdreioe.wingmate.domain.VoiceRepository> { JsonFileVoiceRepository() }
    }

    // Initialize Koin DI with overrides
    initKoin(persistenceModule)
    
    val bridge = KotlinBridge()
    bridge.start()
    
    // Keep running
    Runtime.getRuntime().addShutdownHook(Thread {
        bridge.stop()
    })
    
    Thread.currentThread().join()
}
