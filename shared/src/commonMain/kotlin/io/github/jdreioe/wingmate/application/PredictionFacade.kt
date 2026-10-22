package io.github.jdreioe.wingmate.application

import io.github.jdreioe.wingmate.domain.OperationalLogger
import io.github.jdreioe.wingmate.domain.PredictionResult
import io.github.jdreioe.wingmate.domain.TextPredictionService
import io.github.jdreioe.wingmate.domain.loggingClassName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

enum class PredictionStatus {
    Success,

    /** Predictions could not be produced; observing again (for example on the next edit) retries. */
    Unavailable,
}

/** Predictions are optional: an unavailable update carries no suggestions, so stale ones clear. */
data class PredictionUpdate(
    val status: PredictionStatus,
    val predictions: PredictionResult = PredictionResult(),
) {
    val isSuccess: Boolean get() = status == PredictionStatus.Success
    val isRetryable: Boolean get() = status == PredictionStatus.Unavailable
}

/**
 * Native boundary to shared text prediction. Applying a chosen prediction to
 * the text is [io.github.jdreioe.wingmate.domain.TextEditingPolicy]'s job.
 */
class PredictionFacade(
    private val predictionService: TextPredictionService,
) {
    /**
     * Calls [onChange] on the main thread with predictions for [context], and again
     * when the language or learned vocabulary changes, until the subscription is
     * cancelled. A prediction failure ends the observation with one
     * [PredictionStatus.Unavailable] update instead of crashing the app;
     * cancellation ends it without an update.
     */
    fun observe(
        context: String,
        maxWords: Int,
        maxLetters: Int,
        onChange: (PredictionUpdate) -> Unit,
    ): NativeSubscription {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope.launch {
            predictionService.predictions(context, maxWords, maxLetters)
                .map { PredictionUpdate(PredictionStatus.Success, it) }
                .catch { failure ->
                    if (failure is CancellationException || failure !is Exception) throw failure
                    OperationalLogger.warn("prediction.observe", "failed", exceptionClass = failure.loggingClassName())
                    emit(PredictionUpdate(PredictionStatus.Unavailable))
                }
                .collect { onChange(it) }
        }
        return NativeSubscription(scope)
    }
}
