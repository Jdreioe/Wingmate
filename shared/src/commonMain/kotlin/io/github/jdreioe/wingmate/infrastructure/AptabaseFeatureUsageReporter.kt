package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.application.FeatureUsageEventSanitizer
import io.github.jdreioe.wingmate.application.FeatureUsageReporter
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Sends user-consented, sanitized feature events to Aptabase's event API, the
 * endpoint the official Aptabase SDKs post to. iOS uses this; Android uses the
 * Aptabase Android SDK.
 *
 * One flush job does all sending. It waits [FLUSH_DELAY] so frequent actions such
 * as speaking share a request, then drains the queue in batches. A batch stays
 * queued until it is delivered; a network or server failure retries after another
 * delay. Consent off cancels that job, including a request in flight, and drops
 * the queue.
 */
class AptabaseFeatureUsageReporter(
    private val httpClient: HttpClient,
    private val endpoint: AptabaseEndpoint,
    private val systemProps: JsonObject,
    consent: Flow<Boolean>,
    dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) : FeatureUsageReporter {

    // The state below is only touched from [scope], which runs one coroutine at a time.
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var enabled = false
    private val queue = ArrayList<JsonObject>()
    private var flushJob: Job? = null
    private var sessionId = newSessionId()
    private var lastEventAt = Clock.System.now()

    init {
        scope.launch { consent.collect(::applyEnabled) }
    }

    override fun setEnabled(enabled: Boolean) {
        scope.launch { applyEnabled(enabled) }
    }

    override fun report(event: String, metadata: Map<String, String>) {
        val name = FeatureUsageEventSanitizer.eventName(event)
        val props = FeatureUsageEventSanitizer.props(metadata)
        // Millisecond precision keeps the ISO-8601 timestamp short and widely parseable.
        val now = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())
        scope.launch {
            if (!enabled || queue.size >= MAX_QUEUED) return@launch
            queue += eventJson(name, props, now)
            if (flushJob?.isActive != true) flushJob = scope.launch { flushQueue() }
        }
    }

    private fun applyEnabled(value: Boolean) {
        enabled = value
        if (!value) {
            flushJob?.cancel()
            queue.clear()
        }
    }

    private suspend fun flushQueue() {
        delay(FLUSH_DELAY)
        while (queue.isNotEmpty()) {
            val batch = queue.take(MAX_BATCH)
            // Events are only appended, and consent off cancels this job before it
            // clears the queue, so the batch is still the head of the queue here.
            if (send(batch)) queue.subList(0, batch.size).clear() else delay(FLUSH_DELAY)
        }
    }

    /** True when the batch is finished with: delivered, or rejected as invalid. */
    private suspend fun send(batch: List<JsonObject>): Boolean = try {
        val response = httpClient.post(endpoint.eventsUrl) {
            header("App-Key", endpoint.appKey)
            setBody(TextContent(JsonArray(batch).toString(), ContentType.Application.Json))
        }
        response.status.value < 500
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    private fun eventJson(name: String, props: Map<String, String>, now: Instant): JsonObject {
        // Like the official SDKs: a new session after an hour without events.
        if (now - lastEventAt > SESSION_TIMEOUT) sessionId = newSessionId()
        lastEventAt = now
        return buildJsonObject {
            put("timestamp", now.toString())
            put("sessionId", sessionId)
            put("eventName", name)
            put("systemProps", systemProps)
            put("props", JsonObject(props.mapValues { JsonPrimitive(it.value) }))
        }
    }

    private fun newSessionId(): String =
        (Clock.System.now().epochSeconds * 100_000_000 + Random.nextLong(100_000_000)).toString()

    companion object {
        val FLUSH_DELAY = 30.seconds
        private val SESSION_TIMEOUT = 1.hours
        private const val MAX_BATCH = 25
        private const val MAX_QUEUED = 500
    }
}

/** Where an Aptabase app key such as `A-EU-1234567890` sends events; its region picks the host. */
class AptabaseEndpoint private constructor(val appKey: String, val eventsUrl: String) {
    companion object {
        /** Null when the key is missing or not a hosted EU/US key, which disables reporting. */
        fun fromAppKey(raw: String?): AptabaseEndpoint? {
            val key = raw?.trim().orEmpty()
            val parts = key.split("-")
            if (parts.size != 3 || parts[0] != "A" || parts[2].isEmpty()) return null
            val host = when (parts[1]) {
                "EU" -> "https://eu.aptabase.com"
                "US" -> "https://us.aptabase.com"
                else -> return null
            }
            return AptabaseEndpoint(key, "$host/api/v0/events")
        }
    }
}
