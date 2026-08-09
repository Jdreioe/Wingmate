package io.github.jdreioe.wingmate.infrastructure

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression guard for AAC speech privacy: no logging statement in the speech
 * infrastructure may emit speech text, SSML, phrase/history contents,
 * pronunciation words, or recording paths (issue #146).
 */
class SpeechLogPrivacyTest {

    @Test
    fun speechServicesDoNotLogSensitiveContent() {
        val offenders = buildList {
            monitoredFiles().forEach { (relative, file) ->
                file.forEachLogLine { lineNo, line ->
                    if (line.isSensitiveLogLine()) {
                        add("$relative:$lineNo  ${line.trim()}")
                    }
                }
            }
        }
        assertTrue(
            offenders.isEmpty(),
            "Speech code may leak AAC text, SSML, history, pronunciation, or recording paths:\n" +
                offenders.joinToString("\n"),
        )
    }

    @Test
    fun representativeSensitivePhrasesNeverAppearInLogLines() {
        val phrases = listOf(
            "I am scared",
            "grandma\u2019s house",
            "the pain is in my chest",
            "123 Main Street",
            "call 911",
        )
        monitoredFiles().forEach { (relative, file) ->
            file.forEachLogLine { _, line ->
                phrases.forEach { phrase ->
                    assertFalse(
                        line.contains(phrase),
                        "$relative would log sensitive phrase '$phrase': ${line.trim()}",
                    )
                }
            }
        }
    }

    @Test
    fun linuxBridgeSpeechLogLinesAreContentFree() {
        val file = File(findRepoRoot(), "linuxApp/src/main/kotlin/io/github/jdreioe/wingmate/kde/KotlinBridge.kt")
        assertTrue(file.exists(), "Missing KotlinBridge.kt")
        file.forEachLogLine { lineNo, line ->
            if (line.contains("[SPEECH]")) {
                assertFalse(
                    line.isSensitiveLogLine(),
                    "KotlinBridge speech log leaks content at line $lineNo: ${line.trim()}",
                )
            }
        }
    }

    @Test
    fun knownDangerousLoggingPatternsAreDetected() {
        val dangerous = listOf(
            "println(\"Android Azure TTS SSML:\\n\$ssml\")",
            "logger.debug { \"SSML length=\${ssml.length} chars, preview=\${ssml.take(200)}\" }",
            "println(\"DEBUG: Recorded history for: \$text\")",
            "println(\"DEBUG: Failed to record history: \${t.message}\")",
            "logger.warn(e) { \"Failed to guess pronunciation for '\$text'\" }",
            "logger.warn(t) { \"Failed to play recorded audio: \$path\" }",
            "saidLogger.debug { \"Saved SaidText item path=\${enriched.audioFilePath}\" }",
            "println(\"[SPEECH] Synthesizing with Azure TTS... Text: '\$text', Voice: \${voice?.name}\")",
            "println(\"[SPEECH] Executing TTS command: \$args\")",
            "println(\"[SPEECH] TTS Output: \$line\")",
            "println(\"[SPEECH] /api/speak error: \${e.message}\")",
            "println(\"[SPEECH] \$message\")",
            "println(\"Failed to speak: \$e\")",
            "logger.error { \"Azure TTS client error: \${response.status} - \${body.take(500)}\" }",
        )
        dangerous.forEach { line ->
            assertTrue(line.isSensitiveLogLine(), "Regression guard should flag: $line")
        }
    }

    @Test
    fun contentFreeDiagnosticsAreNotFlagged() {
        val safe = listOf(
            "logger.info { \"Azure TTS response status=\${response.status}\" }",
            "logger.info { \"Azure TTS returned \${bytes.size} bytes (\${bytes.size / 1024}KB)\" }",
            "logger.debug { \"SSML length=\${ssml.length} chars\" }",
            "logger.info { \"Azure TTS request -> url=\$url (format=\${audioFormat.value})\" }",
            "logger.warn(e) { \"Azure TTS network error\" }",
            "logger.warn { \"Failed to guess pronunciation\" }",
            "logger.warn { \"Failed to play recorded audio\" }",
            "logger.warn(t) { \"Failed to play synthesized Azure audio\" }",
            "saidLogger.debug { \"Saved SaidText item id=\${enriched.id} voice=\${enriched.voiceName}\" }",
            "println(\"[SPEECH] Audio playback finished.\")",
            "println(\"[SPEECH] Executing TTS engine: \${File(ttsCommand).name}\")",
            "println(\"[SPEECH] Speech failed (\${error::class.simpleName})\")",
            "println(\"Failed to speak (\${e::class.simpleName})\")",
            "println(\"[SPEECH] Received \${audioData.size} bytes audio. Playing...\")",
        )
        safe.forEach { line ->
            assertFalse(line.isSensitiveLogLine(), "Should not flag: $line")
        }
    }

    private fun monitoredFiles(): List<Pair<String, File>> {
        val root = findRepoRoot()
        return listOf(
            "core/data/src/commonMain/kotlin/io/github/jdreioe/wingmate/infrastructure/AzureTtsClient.kt",
            "core/data/src/commonMain/kotlin/io/github/jdreioe/wingmate/infrastructure/SecureAzureSpeechService.kt",
            "core/data/src/commonMain/kotlin/io/github/jdreioe/wingmate/infrastructure/TokenExchangeClient.kt",
            "core/data/src/androidMain/kotlin/io/github/jdreioe/wingmate/infrastructure/AndroidSpeechService.kt",
            "core/data/src/iosMain/kotlin/io/github/jdreioe/wingmate/infrastructure/IosSpeechService.kt",
            "core/data/src/iosMain/kotlin/io/github/jdreioe/wingmate/infrastructure/IosSaidTextRepository.kt",
            "linuxApp/src/main/kotlin/io/github/jdreioe/wingmate/kde/LinuxSpeechService.kt",
            "linuxApp/src/main/kotlin/io/github/jdreioe/wingmate/kde/AzureSpeechService.kt",
            "androidApp/src/main/java/io/github/jdreioe/wingmate/ui/TestVoiceScreen.kt",
        ).map { it to File(root, it) }
    }

    private fun findRepoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (true) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            dir = dir.parentFile ?: error("Cannot locate repository root from ${System.getProperty("user.dir")}")
        }
    }
}

private fun File.forEachLogLine(block: (Int, String) -> Unit) {
    readLines().forEachIndexed { index, line ->
        if (line.contains("println(") ||
            line.contains("logger.") ||
            line.contains("saidLogger.") ||
            line.contains("System.err.print")
        ) {
            block(index + 1, line)
        }
    }
}

private val sensitiveVariableNames = setOf(
    "ssml",
    "text",
    "phrase",
    "history",
    "args",
    "body",
    "errorbody",
    "path",
    "line",
    "output",
    "message",
    "audiofilepath",
    "recordingpath",
    "audiopath",
    "e",
    "t",
    "error",
    "exception",
    "throwable",
)

private val bareInterpolation = Regex("""\$([A-Za-z_][A-Za-z0-9_]*)""")
private val bracketedInterpolation = Regex("""\$\{([A-Za-z0-9_.?]+)\}""")

private fun String.isSensitiveLogLine(): Boolean {
    if (!contains("println(") &&
        !contains("logger.") &&
        !contains("saidLogger.") &&
        !contains("System.err.print")
    ) {
        return false
    }
    val literalMarkers = listOf(
        "preview=",
        "body.take(",
        "take(500)",
        "history for:",
        "for: \$",
        "for '\$",
        "TTS Output:",
        "Text: '",
    )
    if (literalMarkers.any { contains(it) }) return true

    if (bareInterpolation.findAll(this).any {
            it.groupValues[1].lowercase() in sensitiveVariableNames
        }
    ) {
        return true
    }

    return bracketedInterpolation.findAll(this).any { match ->
        match.groupValues[1].substringAfterLast('.').trimEnd('?').lowercase() in sensitiveVariableNames
    }
}
