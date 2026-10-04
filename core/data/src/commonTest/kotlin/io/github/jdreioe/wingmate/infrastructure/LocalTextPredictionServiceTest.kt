package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.PredictionResult
import io.github.jdreioe.wingmate.domain.SaidText
import io.github.jdreioe.wingmate.domain.SaidTextRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalTextPredictionServiceTest {
    @Test
    fun observersShareLoadingAndRefreshWithoutAnotherKeystroke() = runBlocking {
        withServiceScope { scope ->
            val started = CompletableDeferred<Unit>()
            val dictionary = CompletableDeferred<List<Pair<String, Int>>>()
            var loads = 0
            val service = LocalTextPredictionService(MutableStateFlow("en"), History(), {
                loads++
                started.complete(Unit)
                dictionary.await()
            }, scope)
            val emissions = mutableListOf<PredictionResult>()
            val first = async {
                service.predictions("he").onEach { emissions.add(it) }.first { it.words.isNotEmpty() }
            }
            started.await()
            val second = async { service.predictions("h").first { it.words.isNotEmpty() } }
            dictionary.complete(listOf("hello" to 100))

            assertEquals(listOf("hello"), first.await().words)
            assertEquals(listOf("hello"), second.await().words)
            assertEquals(PredictionResult(), emissions.first())
            assertEquals(1, loads)
        }
    }

    @Test
    fun closingOneScreenDoesNotCancelSharedLoading() = runBlocking {
        withServiceScope { scope ->
            val started = CompletableDeferred<Unit>()
            val dictionary = CompletableDeferred<List<Pair<String, Int>>>()
            var loads = 0
            val service = LocalTextPredictionService(MutableStateFlow("en"), History(), {
                loads++
                started.complete(Unit)
                dictionary.await()
            }, scope)
            val leavingScreen = async { service.predictions("he").first { it.words.isNotEmpty() } }
            started.await()
            leavingScreen.cancel()
            dictionary.complete(listOf("hello" to 100))

            assertEquals(listOf("hello"), service.predictions("he").first { it.words.isNotEmpty() }.words)
            assertEquals(1, loads)
        }
    }

    @Test
    fun languageChangeCancelsAnOutdatedLoad() = runBlocking {
        withServiceScope { scope ->
            val language = MutableStateFlow("en")
            val englishStarted = CompletableDeferred<Unit>()
            val englishCancelled = CompletableDeferred<Unit>()
            val service = LocalTextPredictionService(language, History(), { selected ->
                if (selected == "en") {
                    englishStarted.complete(Unit)
                    try {
                        CompletableDeferred<Unit>().await()
                    } finally {
                        englishCancelled.complete(Unit)
                    }
                }
                listOf("vand" to 100)
            }, scope)
            val result = async { service.predictions("").first { it.words.isNotEmpty() } }
            englishStarted.await()
            language.value = "da"

            assertEquals(listOf("vand"), result.await().words)
            englishCancelled.await()
        }
    }

    @Test
    fun historyRefreshRetainsDictionaryAndReplacesRemovedHistory() = runBlocking {
        withServiceScope { scope ->
            val history = History(mutableListOf(SaidText(saidText = "tea")))
            val service = LocalTextPredictionService(MutableStateFlow("en"), history, {
                listOf("hello" to 100)
            }, scope)
            assertTrue("tea" in service.predictions("").first { it.words.isNotEmpty() }.words)
            history.items.add(SaidText(saidText = "water"))
            service.refresh()
            val updated = service.predictions("").first { "water" in it.words }
            assertTrue(updated.words.containsAll(listOf("tea", "hello", "water")))

            history.items.clear()
            service.refresh()
            val cleared = service.predictions("").first { it.words == listOf("hello") }
            assertFalse("tea" in cleared.words)
        }
    }

    @Test
    fun dictionaryFailureFallsBackToVisibleLocalHistoryAndCanRetry() = runBlocking {
        withServiceScope { scope ->
            var offline = true
            val history = History(mutableListOf(
                SaidText(saidText = "water"),
                SaidText(saidText = "hidden", visibleInHistory = false),
            ))
            val service = LocalTextPredictionService(MutableStateFlow("en"), history, {
                if (offline) error("Fixture dictionary unavailable")
                listOf("hello" to 100)
            }, scope)
            assertEquals(listOf("water"), service.predictions("").first { it.words.isNotEmpty() }.words)
            offline = false
            service.refresh()
            assertTrue("hello" in service.predictions("").first { "hello" in it.words }.words)
        }
    }

    @Test
    fun historyReadFailureStillAllowsDictionaryPredictions() = runBlocking {
        withServiceScope { scope ->
            val history = History().also { it.failReads = true }
            val service = LocalTextPredictionService(MutableStateFlow("en"), history, {
                listOf("hello" to 100)
            }, scope)
            assertEquals(listOf("hello"), service.predictions("he").first { it.words.isNotEmpty() }.words)
        }
    }

    private suspend fun withServiceScope(block: suspend CoroutineScope.(CoroutineScope) -> Unit) =
        withTimeout(5_000) {
            coroutineScope {
                val modelScope = CoroutineScope(coroutineContext + SupervisorJob())
                try {
                    block(modelScope)
                } finally {
                    modelScope.cancel()
                }
            }
        }

    private class History(val items: MutableList<SaidText> = mutableListOf()) : SaidTextRepository {
        var failReads = false
        override suspend fun list(): List<SaidText> {
            check(!failReads) { "Fixture history unavailable" }
            return items.toList()
        }
        override suspend fun add(item: SaidText): SaidText = item.also { items.add(it) }
        override suspend fun addAll(items: List<SaidText>) { this.items.addAll(items) }
        override suspend fun deleteAll() { items.clear() }
    }
}
