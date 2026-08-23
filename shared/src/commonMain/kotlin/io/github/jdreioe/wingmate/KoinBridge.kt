package io.github.jdreioe.wingmate

import io.github.jdreioe.wingmate.application.SelectionHighlight
import io.github.jdreioe.wingmate.application.AccessInputController
import io.github.jdreioe.wingmate.application.AccessInputEffect
import io.github.jdreioe.wingmate.application.SettingsUseCase
import io.github.jdreioe.wingmate.di.appModule
import io.github.jdreioe.wingmate.initKoin
import io.github.jdreioe.wingmate.domain.OperationalLogger
import io.github.jdreioe.wingmate.domain.TextEditResult
import io.github.jdreioe.wingmate.domain.TextEditingPolicy
import io.github.jdreioe.wingmate.domain.TextSpan
import io.github.jdreioe.wingmate.domain.loggingClassName
import io.github.jdreioe.wingmate.infrastructure.OpenSymbolsClient
import io.github.jdreioe.wingmate.infrastructure.SymbolSearchClient
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

data class IosAccessInputResult(
    val activationTargetId: String?,
    val isPaused: Boolean,
    val currentTargetId: String?,
    val dwellProgress: Float,
)

class KoinBridge : KoinComponent {
    private val accessInput = AccessInputController()

    // --- Shared native text-editing policy ---
    fun mergeTextSpans(spans: List<TextSpan>, textLength: Int): List<TextSpan> =
        TextEditingPolicy.merge(spans, textLength)

    fun addTextSpan(spans: List<TextSpan>, span: TextSpan, textLength: Int): List<TextSpan> =
        TextEditingPolicy.merge(spans + span, textLength)

    fun adjustTextSpansForReplacement(
        textLength: Int,
        edit: TextSpan,
        replacementLength: Int,
        spans: List<TextSpan>,
    ): List<TextSpan> = TextEditingPolicy.adjustForReplacement(textLength, edit, replacementLength, spans)

    fun completePredictedWord(text: String, cursor: Int, suggestion: String): TextEditResult =
        TextEditingPolicy.completeWord(text, cursor, suggestion)

    fun insertPredictedText(text: String, cursor: Int, value: String): TextEditResult =
        TextEditingPolicy.insert(text, cursor, value)

    // --- Sharing helpers ---
    fun shareAudio(path: String) {
        try {
            get<io.github.jdreioe.wingmate.platform.ShareService>().shareAudio(path)
        } catch (_: Throwable) {}
    }

    fun copyAudio(path: String) {
        try {
            get<io.github.jdreioe.wingmate.platform.AudioClipboard>().copyAudioFile(path)
        } catch (_: Throwable) {}
    }

    fun accessInputEnter(targetId: String): IosAccessInputResult {
        accessInput.targetEntered(targetId, nowMillis())
        return accessResult(null)
    }

    fun accessInputExit(targetId: String): IosAccessInputResult {
        accessInput.targetExited(targetId, nowMillis())
        return accessResult(null)
    }

    fun accessInputFocus(targetId: String): IosAccessInputResult {
        accessInput.targetFocused(targetId, nowMillis())
        return accessResult(null)
    }

    fun accessInputBlur(targetId: String): IosAccessInputResult {
        accessInput.targetBlurred(targetId, nowMillis())
        return accessResult(null)
    }

    fun accessInputKeyDown(key: String, selectBinding: String, restBinding: String): IosAccessInputResult =
        accessResult(accessInput.keyDown(key, selectBinding, restBinding, nowMillis()))

    fun accessInputKeyUp(key: String): IosAccessInputResult {
        accessInput.keyUp(key)
        return accessResult(null)
    }

    fun accessInputTick(dwellMillis: Long): IosAccessInputResult =
        accessResult(accessInput.tick(nowMillis(), dwellMillis))

    fun accessInputTogglePause(): IosAccessInputResult = accessResult(accessInput.togglePaused(nowMillis()))

    private fun accessResult(effect: AccessInputEffect?): IosAccessInputResult = IosAccessInputResult(
        activationTargetId = (effect as? AccessInputEffect.Activate)?.targetId,
        isPaused = accessInput.state.isPaused,
        currentTargetId = accessInput.state.currentTargetId,
        dwellProgress = accessInput.state.dwellProgress,
    )

    private val selectionHighlight = SelectionHighlight()

    /** Record a selection for visual highlight; immediately ends the previous highlight. */
    fun selectionHighlightActivate(buttonId: String) {
        selectionHighlight.activate(buttonId, nowMillis())
    }

    /** Clear any active selection highlight. */
    fun selectionHighlightClear() {
        selectionHighlight.clear()
    }

    /**
     * The currently highlighted button id for the given duration, or null when the
     * highlight has expired or is disabled by a non-positive [durationMillis].
     */
    fun selectionHighlightButtonId(durationMillis: Long): String? =
        selectionHighlight.highlightedTarget(nowMillis(), durationMillis)

    private fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

    // Debug helper: return the runtime class name of the bound VoiceRepository
    fun debugVoiceRepositoryName(): String = try { get<io.github.jdreioe.wingmate.domain.VoiceRepository>()::class.simpleName ?: "unknown" } catch (_: Throwable) { "error" }

    companion object {
        private var started: Boolean = false
    fun start() {
            if (started) return
            try {
                initKoin(appModule)
                started = true
            } catch (_: Throwable) {
                // Already started elsewhere, or init failed — retry on next call
            }
        }
    }

    // --- Prediction Helpers ---
    // Bridge to TextPredictionService
    suspend fun predict(context: String, maxWords: Int, maxLetters: Int): io.github.jdreioe.wingmate.domain.PredictionResult {
        return try {
            get<io.github.jdreioe.wingmate.domain.TextPredictionService>().predict(context, maxWords, maxLetters)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            io.github.jdreioe.wingmate.domain.PredictionResult()
        }
    }

    suspend fun trainPredictionModel() {
        try {
            val service = get<io.github.jdreioe.wingmate.domain.TextPredictionService>()
            val repo = get<io.github.jdreioe.wingmate.domain.SaidTextRepository>()
            val list = repo.list()
            
            // If it's the n-gram service, we can try to load base dict first
            if (service is io.github.jdreioe.wingmate.infrastructure.SimpleNGramPredictionService) {
                // Determine primary language
                val settings = get<SettingsUseCase>().get()
                val lang = settings.primaryLanguage
                
                // Try to load dict
                 try {
                    val loader = get<io.github.jdreioe.wingmate.infrastructure.DictionaryLoader>()
                    val dict = loader.loadDictionary(lang)
                    if (dict.isNotEmpty()) {
                        service.setBaseLanguage(dict)
                        // Train history on top without clearing
                        service.train(list, false)
                        return
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Throwable) {}
                 // Fallback: train just history (clearing old)
                service.train(list, true)
            } else {
                service.train(list)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            OperationalLogger.warn("prediction_model.train", "failed", exceptionClass = t.loggingClassName())
        }
    }

    suspend fun learnPhrase(text: String) {
        try {
            val service = get<io.github.jdreioe.wingmate.domain.TextPredictionService>()
            if (service is io.github.jdreioe.wingmate.infrastructure.SimpleNGramPredictionService) {
                service.learnPhrase(text)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {}
    }

    // --- Pronunciation Dictionary Helpers ---
    suspend fun listPronunciations(): List<io.github.jdreioe.wingmate.domain.PronunciationEntry> {
        return try {
            get<io.github.jdreioe.wingmate.domain.PronunciationDictionaryRepository>().getAll()
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            emptyList()
        }
    }

    suspend fun addPronunciation(word: String, phoneme: String, alphabet: String) {
        try {
            get<io.github.jdreioe.wingmate.domain.PronunciationDictionaryRepository>().add(
                io.github.jdreioe.wingmate.domain.PronunciationEntry(word, phoneme, alphabet)
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {}
    }

    suspend fun deletePronunciation(word: String) {
        try {
            get<io.github.jdreioe.wingmate.domain.PronunciationDictionaryRepository>().delete(word)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {}
    }

    // --- OpenSymbols helpers (route through shared client, not Swift) ---
    fun setOpenSymbolsProxyUrl(url: String?) {
        OpenSymbolsClient.setProxyBaseUrl(url)
    }

    suspend fun openSymbolsSearch(
        query: String,
        locale: String,
        symbolPackage: String,
        prioritizeArasaac: Boolean,
    ): IosOpenSymbolsResult {
        return when (
            val result = SymbolSearchClient.search(
                query = query,
                locale = locale,
                packageFilter = SymbolSearchClient.Package.fromWireValue(symbolPackage),
                prioritizeArasaac = prioritizeArasaac,
            )
        ) {
            is SymbolSearchClient.SearchResponse.Success -> IosOpenSymbolsResult(
                symbols = result.symbols.map {
                    IosOpenSymbol(
                        id = it.id,
                        name = it.name,
                        imageUrl = it.imageUrl,
                        source = it.source.name.lowercase(),
                    )
                },
                errorCode = ""
            )
            is SymbolSearchClient.SearchResponse.Failure -> IosOpenSymbolsResult(
                symbols = emptyList(),
                errorCode = result.error.toIosErrorCode()
            )
        }
    }
}

private fun OpenSymbolsClient.SearchError.toIosErrorCode(): String = when (this) {
    OpenSymbolsClient.SearchError.NotConfigured -> "missing_proxy"
    OpenSymbolsClient.SearchError.Throttled,
    OpenSymbolsClient.SearchError.Network,
    OpenSymbolsClient.SearchError.Server,
    -> "search_failed"
}

data class IosOpenSymbol(
    val id: String,
    val name: String? = null,
    val imageUrl: String? = null,
    val source: String = "opensymbols",
)

data class IosOpenSymbolsResult(
    val symbols: List<IosOpenSymbol> = emptyList(),
    val errorCode: String = "",
)

