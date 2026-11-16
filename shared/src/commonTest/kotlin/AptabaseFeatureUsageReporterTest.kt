package io.github.jdreioe.wingmate.infrastructure

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class AptabaseFeatureUsageReporterTest {

    private val endpoint = AptabaseEndpoint.fromAppKey("A-EU-1234567890")!!
    private val consent = MutableStateFlow(true)
    private val requests = mutableListOf<HttpRequestData>()

    private fun TestScope.reporter(
        respond: suspend MockRequestHandleScope.() -> HttpResponseData = { respond("", HttpStatusCode.OK) },
    ): AptabaseFeatureUsageReporter {
        // Engine and reporter share the virtual clock, so advanceTimeBy drives both.
        val dispatcher = StandardTestDispatcher(testScheduler)
        val engine = MockEngine(
            MockEngineConfig().apply {
                this.dispatcher = dispatcher
                addHandler { request ->
                    requests += request
                    respond()
                }
            }
        )
        return AptabaseFeatureUsageReporter(
            httpClient = HttpClient(engine),
            endpoint = endpoint,
            systemProps = JsonObject(emptyMap()),
            consent = consent,
            dispatcher = dispatcher,
        ).also { runCurrent() }
    }

    private fun HttpRequestData.events(): List<JsonObject> =
        Json.parseToJsonElement((body as TextContent).text).let { it as JsonArray }.map { it.jsonObject }

    @Test
    fun consentedEventsShareOneRedactedRequestAfterTheDelay() = runTest {
        val reporter = reporter()

        reporter.report("phrase_played")
        reporter.report("settings_updated", mapOf("engine" to "AZURE", "text" to "I need my medicine"))
        advanceTimeBy(AptabaseFeatureUsageReporter.FLUSH_DELAY - 1.seconds)
        assertEquals(0, requests.size)
        advanceTimeBy(2.seconds)

        val request = requests.single()
        assertEquals("https://eu.aptabase.com/api/v0/events", request.url.toString())
        assertEquals("A-EU-1234567890", request.headers["App-Key"])
        val events = request.events()
        assertEquals(listOf("phrase_played", "settings_updated"), events.map { it["eventName"]!!.jsonPrimitive.content })
        val props = events[1]["props"]!!.jsonObject
        assertEquals("AZURE", props["engine"]!!.jsonPrimitive.content)
        assertEquals("redacted", props["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun failedBatchIsRetriedUntilDelivered() = runTest {
        var attempts = 0
        val reporter = reporter {
            attempts++
            respond("", if (attempts == 1) HttpStatusCode.ServiceUnavailable else HttpStatusCode.OK)
        }

        reporter.report("phrase_played")
        advanceTimeBy(10.minutes)

        assertEquals(2, requests.size)
        assertEquals(requests[0].events(), requests[1].events())
    }

    @Test
    fun consentOffCancelsTheRequestInFlight() = runTest {
        val serverAnswer = CompletableDeferred<HttpStatusCode>()
        val reporter = reporter { respond("", serverAnswer.await()) }

        reporter.report("phrase_played")
        advanceTimeBy(AptabaseFeatureUsageReporter.FLUSH_DELAY + 1.seconds)
        assertEquals(1, requests.size) // in flight

        consent.value = false
        runCurrent()
        consent.value = true
        runCurrent()
        reporter.report("app_started")
        runCurrent()
        // A surviving old request would now treat app_started as its delivered batch.
        serverAnswer.complete(HttpStatusCode.OK)
        advanceTimeBy(10.minutes)

        assertEquals(2, requests.size)
        assertEquals("app_started", requests[1].events().single()["eventName"]!!.jsonPrimitive.content)
    }
}
