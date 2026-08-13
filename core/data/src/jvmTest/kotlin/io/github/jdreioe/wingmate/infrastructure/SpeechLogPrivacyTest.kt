package io.github.jdreioe.wingmate.infrastructure

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Regression guard for the Android/shared production logging policy (issue #162). */
class SpeechLogPrivacyTest {

    @Test
    fun productionSourcesUseOnlyThePrivacySafeLogger() {
        val offenders = productionFiles().flatMap { (relative, file) ->
            file.readLines().mapIndexedNotNull { index, line ->
                line.takeIf { it.isAdHocDiagnostic() || it.isDirectLoggerUse(relative) }
                    ?.let { "$relative:${index + 1}  ${it.trim()}" }
            }
        }

        assertTrue(
            offenders.isEmpty(),
            "Android/shared production diagnostics must use OperationalLogger:\n" +
                offenders.joinToString("\n"),
        )
    }

    @Test
    fun productionDiagnosticsDoNotReceiveSensitiveValuesOrRawThrowables() {
        val offenders = productionFiles().flatMap { (relative, file) ->
            val source = file.readText()
            source.operationalLogCalls().mapNotNull { call ->
                call.takeIf(String::isUnsafeOperationalLogCall)
                    ?.let { "$relative  ${it.replace(Regex("""\s+"""), " ").trim()}" }
            }
        }

        assertTrue(
            offenders.isEmpty(),
            "Operational diagnostics may only contain bounded, content-free metadata:\n" +
                offenders.joinToString("\n"),
        )
    }

    @Test
    fun representativeUnsafeStatementsAreDetected() {
        val unsafe = listOf(
            "println(\"phrase=\$text\")",
            "error.printStackTrace()",
            "Log.w(\"Speech\", \"failed\", throwable)",
            "logger.warn(error) { \"speech failed\" }",
            "logger.info { \"endpoint=\$url\" }",
            "OperationalLogger.warn(\"speech.play\", \"failed\", exceptionClass = error.message)",
            "OperationalLogger.info(operation = text, outcome = \"spoken\")",
            "OperationalLogger.info(\"speech.play\", \"started\", count = ssml.length)",
        )

        unsafe.forEach { statement ->
            assertTrue(
                statement.isAdHocDiagnostic() ||
                    statement.isDirectLoggerUse("example.kt") ||
                    statement.isUnsafeOperationalLogCall(),
                "Regression guard should flag: $statement",
            )
        }
    }

    @Test
    fun representativeContentFreeStatementsAreAllowed() {
        val safe = listOf(
            "OperationalLogger.info(\"speech.play\", \"started\")",
            "OperationalLogger.info(\"voice_catalog.load\", \"succeeded\", count = voices.size)",
            "OperationalLogger.warn(\"speech.play\", \"failed\", exceptionClass = error.loggingClassName())",
            "OperationalLogger.info(\"azure_tts.synthesize\", \"response_received\", statusCode = response.status.value)",
        )

        safe.forEach { statement ->
            assertFalse(statement.isAdHocDiagnostic(), "Should allow: $statement")
            assertFalse(statement.isDirectLoggerUse("example.kt"), "Should allow: $statement")
            assertFalse(statement.isUnsafeOperationalLogCall(), "Should allow: $statement")
        }
    }

    private fun productionFiles(): List<Pair<String, File>> {
        val root = findRepoRoot()
        val sourceRoots = listOf(
            "androidApp/src/main",
            "shared/src",
            "core",
            "feature",
        )
        val productionSourceSet = Regex("/src/(?:common|android|ios|jvm)Main/")

        return sourceRoots.flatMap { sourceRoot ->
            File(root, sourceRoot).walkTopDown()
                .filter(File::isFile)
                .filter { it.extension == "kt" }
                .filter { file ->
                    val normalized = file.invariantSeparatorsPath
                    normalized.contains("/androidApp/src/main/") || productionSourceSet.containsMatchIn(normalized)
                }
                .map { file -> file.relativeTo(root).invariantSeparatorsPath to file }
                .toList()
        }
    }

    private fun findRepoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (true) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            dir = dir.parentFile ?: error("Cannot locate repository root from ${System.getProperty("user.dir")}")
        }
    }
}

private val directLoggerCall = Regex("""\b(?:logger|saidLogger|log)\.(?:trace|debug|info|warn|error)\s*[({]""")
private val rawThrowableArgument = Regex(
    """\.(?:trace|debug|info|warn|error)\s*\(\s*(?:e|t|error|exception|throwable)\b""",
)
private val unsafeOperationalValue = Regex(
    """\b(?:operation|outcome|exceptionClass|count|statusCode|enabled)\s*=\s*(?:text|ssml|phrase|history|recording|token|credential|endpoint|url|settings|config|message|e\.message|t\.message|error\.message|exception\.message|throwable\.message)\b""",
    RegexOption.IGNORE_CASE,
)
private val sensitiveInterpolation = Regex(
    """\$\{?(?:text|ssml|phrase|history|recording|token|credential|endpoint|url|settings|config|message|e|t|error|exception|throwable)(?:[.}]|\b)""",
    RegexOption.IGNORE_CASE,
)
private val sensitiveMetadataExpression = Regex(
    """\b(?:count|statusCode|enabled)\s*=\s*(?:ssml|text|phrase|history|recording|token|credential|endpoint|url|settings|config)\.""",
    RegexOption.IGNORE_CASE,
)
private val positionalOperationalTags = Regex(
    """OperationalLogger\.(?:debug|info|warn|error)\s*\(\s*"[a-z0-9_.-]+"\s*,\s*"[a-z0-9_.-]+"""",
)
private val namedOperationalTags = Regex(
    """\boperation\s*=\s*"[a-z0-9_.-]+"[\s\S]*\boutcome\s*=\s*"[a-z0-9_.-]+"""",
)

private fun String.isAdHocDiagnostic(): Boolean =
    contains("println(") ||
        contains("printStackTrace(") ||
        contains("System.err.print") ||
        Regex("""\bLog\.[vdiewtf]\s*\(""").containsMatchIn(this)

private fun String.isDirectLoggerUse(relativePath: String): Boolean =
    relativePath != OPERATIONAL_LOGGER_PATH &&
        (contains("KotlinLogging") || directLoggerCall.containsMatchIn(this) || rawThrowableArgument.containsMatchIn(this))

private fun String.isUnsafeOperationalLogCall(): Boolean {
    if (!contains("OperationalLogger.")) return false
    return (!positionalOperationalTags.containsMatchIn(this) && !namedOperationalTags.containsMatchIn(this)) ||
        sensitiveInterpolation.containsMatchIn(this) ||
        unsafeOperationalValue.containsMatchIn(this) ||
        sensitiveMetadataExpression.containsMatchIn(this) ||
        contains(".message") ||
        contains(".toString()")
}

private fun String.operationalLogCalls(): List<String> = buildList {
    var searchFrom = 0
    while (true) {
        val start = indexOf("OperationalLogger.", searchFrom)
        if (start < 0) break
        val open = indexOf('(', start)
        if (open < 0) break
        var depth = 0
        var end = open
        while (end < length) {
            when (this@operationalLogCalls[end]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) {
                        end++
                        break
                    }
                }
            }
            end++
        }
        add(substring(start, end.coerceAtMost(length)))
        searchFrom = end
    }
}

private const val OPERATIONAL_LOGGER_PATH =
    "core/domain/src/commonMain/kotlin/io/github/jdreioe/wingmate/domain/OperationalLogger.kt"
