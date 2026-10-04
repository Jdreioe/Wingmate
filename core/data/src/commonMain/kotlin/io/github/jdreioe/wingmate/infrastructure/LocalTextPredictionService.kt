package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.OperationalLogger
import io.github.jdreioe.wingmate.domain.PredictionResult
import io.github.jdreioe.wingmate.domain.SaidTextRepository
import io.github.jdreioe.wingmate.domain.TextPredictionService
import io.github.jdreioe.wingmate.domain.loggingClassName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Owns one lazily loaded model for all prediction surfaces. Rebuilds are private:
 * observers see empty suggestions during loading, then a fully trained model.
 * The application scope keeps loading independent of any one screen's lifetime.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalTextPredictionService(
    languages: Flow<String>,
    private val historyRepository: SaidTextRepository,
    private val loadDictionary: suspend (String) -> List<Pair<String, Int>>,
    scope: CoroutineScope,
) : TextPredictionService {
    private val refreshes = MutableStateFlow(0L)
    private val models = combine(languages.distinctUntilChanged(), refreshes) { language, _ -> language }
        .transformLatest<String, SimpleNGramPredictionService?> { language ->
            emit(null)
            val dictionary = try {
                // Unavailable downloads must not prevent local-history predictions.
                withTimeoutOrNull(15_000) { loadDictionary(language) }.orEmpty()
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                OperationalLogger.warn("prediction_model.dictionary", "failed", exceptionClass = failure.loggingClassName())
                emptyList()
            }
            val history = try {
                historyRepository.list().filter { it.visibleInHistory }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                OperationalLogger.warn("prediction_model.history", "failed", exceptionClass = failure.loggingClassName())
                emptyList()
            }
            val model = SimpleNGramPredictionService()
            model.setBaseLanguage(dictionary)
            model.train(history, clear = false)
            emit(model)
        }
        .stateIn(scope, SharingStarted.Lazily, null)

    override fun predictions(context: String, maxWords: Int, maxLetters: Int): Flow<PredictionResult> =
        models.mapLatest { model ->
            model?.predict(context, maxWords, maxLetters) ?: PredictionResult()
        }.distinctUntilChanged()

    override fun refresh() {
        refreshes.update { it + 1 }
    }
}
