package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.AacLogger
import io.github.jdreioe.wingmate.domain.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer

@Serializable
private data class OblEvent(
    val timestamp: String,
    val event: String,
    val label: String? = null,
    val board_id: String? = null,
    val phrase_id: String? = null,
    val sentence: String? = null
)

class RealAacLogger(
    private val fileSystem: FileSystem,
    private val logDir: String?,
    private val settingsRepository: SettingsRepository
) : AacLogger {
    private val scope = CoroutineScope(Dispatchers.Default)
    private var enabled = false

    init {
        scope.launch {
            enabled = settingsRepository.get().usageLoggingEnabled
        }
    }

    override fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
    }

    override fun logButtonClick(label: String, boardId: String?, phraseId: String?) {
        if (!enabled) return
        logEvent(OblEvent(
            timestamp = currentTimestamp(),
            event = "button_click",
            label = label,
            board_id = boardId,
            phrase_id = phraseId
        ))
    }

    override fun logSentenceSpeak(sentence: String) {
        if (!enabled) return
        logEvent(OblEvent(
            timestamp = currentTimestamp(),
            event = "sentence_speak",
            sentence = sentence
        ))
    }

    private fun logEvent(event: OblEvent) {
        val dir = logDir ?: return
        scope.launch {
            try {
                val path = dir.toPath().resolve("usage_log.obl")
                val json = Json.encodeToString(event)
                fileSystem.appendingSink(path).buffer().use { sink ->
                    sink.writeUtf8(json + "\n")
                }
            } catch (e: Exception) {
                // Fail silently but maybe print to console in debug
                println("Failed to log OBL event: ${e.message}")
            }
        }
    }

    private fun currentTimestamp(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }
}
