import io.github.jdreioe.wingmate.application.PredictionFacade
import io.github.jdreioe.wingmate.application.PredictionStatus
import io.github.jdreioe.wingmate.application.PredictionUpdate
import io.github.jdreioe.wingmate.domain.PredictionResult
import io.github.jdreioe.wingmate.domain.TextPredictionService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PredictionFacadeTest {
    @Test
    fun observeDeliversPredictionsUntilCancelled() = withFakeMain {
        val predictions = MutableStateFlow(PredictionResult(words = listOf("hello")))
        val received = mutableListOf<PredictionUpdate>()

        val subscription = PredictionFacade(service { predictions }).observe("he", 5, 5) { received += it }
        predictions.value = PredictionResult(words = listOf("help"))
        subscription.cancel()
        predictions.value = PredictionResult(words = listOf("helmet"))

        assertTrue(received.all { it.status == PredictionStatus.Success })
        assertEquals(listOf(listOf("hello"), listOf("help")), received.map { it.predictions.words })
    }

    @Test
    fun aFailingPredictionModelReportsUnavailableInsteadOfCrashing() = withFakeMain {
        val received = mutableListOf<PredictionUpdate>()
        val failing = service {
            flow {
                emit(PredictionResult(words = listOf("hello")))
                throw IllegalStateException("dictionary unreadable")
            }
        }

        PredictionFacade(failing).observe("he", 5, 5) { received += it }

        assertEquals(listOf(PredictionStatus.Success, PredictionStatus.Unavailable), received.map { it.status })
        assertTrue(received.last().predictions.words.isEmpty())
        assertTrue(received.last().isRetryable)
    }

    @Test
    fun cancellationInsideThePredictionModelEndsObservationWithoutAFailure() = withFakeMain {
        val received = mutableListOf<PredictionUpdate>()
        val cancelled = service { flow { throw CancellationException("model reload cancelled") } }

        PredictionFacade(cancelled).observe("he", 5, 5) { received += it }

        assertTrue(received.isEmpty())
    }

    private fun service(predictions: () -> Flow<PredictionResult>) = object : TextPredictionService {
        override fun predictions(context: String, maxWords: Int, maxLetters: Int) = predictions()
        override fun refresh() = Unit
    }

    // The facade collects on Dispatchers.Main; an unconfined test Main runs it eagerly.
    private fun withFakeMain(block: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            block()
        } finally {
            Dispatchers.resetMain()
        }
    }
}
