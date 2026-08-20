package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.Voice
import io.github.jdreioe.wingmate.domain.VoiceProvider
import io.github.jdreioe.wingmate.domain.SpeechServiceConfig
import io.github.jdreioe.wingmate.domain.SpeechSegment
import io.github.jdreioe.wingmate.domain.SpeechTextProcessor
import io.github.jdreioe.wingmate.domain.OperationalLogger
import io.github.jdreioe.wingmate.domain.loggingClassName
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.HttpRedirect
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import nl.adaptivity.xmlutil.EventType
import nl.adaptivity.xmlutil.xmlStreaming

/**
 * Enhanced shared Azure TTS client with improved audio quality and error handling.
 * Accepts SSML and returns audio bytes (mp3) from Azure.
 * Uses Ktor client from the calling platform (ensure ktor client engine configured on each platform).
 */
object AzureTtsClient {
    
    /**
     * Audio format options for Azure TTS
     */
    enum class AudioFormat(val value: String) {
        MP3_16KHZ_128KBPS("audio-16khz-128kbitrate-mono-mp3"),
        MP3_24KHZ_160KBPS("audio-24khz-160kbitrate-mono-mp3"),
        MP3_48KHZ_192KBPS("audio-48khz-192kbitrate-mono-mp3"),
        WAV_16KHZ_16BIT("riff-16khz-16bit-mono-pcm"),
        WAV_24KHZ_16BIT("riff-24khz-16bit-mono-pcm")
    }
    
    suspend fun synthesize(
        client: HttpClient, 
        ssml: String, 
        config: SpeechServiceConfig,
        audioFormat: AudioFormat = AudioFormat.MP3_24KHZ_160KBPS
    ): ByteArray {
        val endpoint = requireAzureSpeechEndpoint(config.endpoint)
        requireCredentialSafeClient(client)
        val url = endpoint.synthesisUrl

        OperationalLogger.info("azure_tts.synthesize", "started")

        try {
            val response: HttpResponse = client.post(url) {
                headers {
                    // Important: do not log the subscription key. We must send it but not print it.
                    append("Ocp-Apim-Subscription-Key", config.subscriptionKey)
                    append(HttpHeaders.ContentType, "application/ssml+xml")
                    append("X-Microsoft-OutputFormat", audioFormat.value)
                    append(HttpHeaders.UserAgent, "WingmateKMP/2.0")
                    append(HttpHeaders.Accept, "audio/*")
                }
                setBody(ssml)
            }

            OperationalLogger.info("azure_tts.synthesize", "response_received", statusCode = response.status.value)
            
            when {
                response.status.isSuccess() -> {
                    val bytes = response.body<ByteArray>()
                    OperationalLogger.info("azure_tts.synthesize", "succeeded", count = bytes.size)
                    
                    if (bytes.isEmpty()) {
                        throw RuntimeException("Azure TTS returned empty audio data")
                    }
                    
                    return bytes
                }
                response.status.value == 401 -> {
                    OperationalLogger.warn("azure_tts.synthesize", "authentication_failed", statusCode = 401)
                    throw RuntimeException("Azure TTS authentication failed. Please check your subscription key.")
                }
                response.status.value == 429 -> {
                    OperationalLogger.warn("azure_tts.synthesize", "rate_limited", statusCode = 429)
                    throw RuntimeException("Azure TTS rate limit exceeded. Please try again later.")
                }
                response.status.value in 400..499 -> {
                    OperationalLogger.error("azure_tts.synthesize", "client_error", statusCode = response.status.value)
                    throw RuntimeException("Azure TTS request error: ${response.status.description}")
                }
                response.status.value in 500..599 -> {
                    OperationalLogger.error("azure_tts.synthesize", "server_error", statusCode = response.status.value)
                    throw RuntimeException("Azure TTS server error: ${response.status.description}")
                }
                else -> {
                    OperationalLogger.error("azure_tts.synthesize", "failed", statusCode = response.status.value)
                    throw RuntimeException("Azure TTS failed: ${response.status.description}")
                }
            }
        } catch (e: Exception) {
            when (e) {
                is RuntimeException -> throw e
                else -> {
                    OperationalLogger.error(
                        operation = "azure_tts.synthesize",
                        outcome = "network_failed",
                        exceptionClass = e.loggingClassName(),
                    )
                    throw RuntimeException("Azure TTS network error: ${e.message}", e)
                }
            }
        }
    }
    
    /**
     * Backward compatibility method with default audio format
     */
    suspend fun synthesize(client: HttpClient, ssml: String, config: SpeechServiceConfig): ByteArray {
        return synthesize(client, ssml, config, AudioFormat.MP3_24KHZ_160KBPS)
    }

    /**
     * Enhanced SSML generation with better voice parameter support
     */
    fun generateSsml(text: String, voice: Voice, dictionary: List<io.github.jdreioe.wingmate.domain.PronunciationEntry> = emptyList()): String {
        val params = resolveVoiceParams(voice)
        if (voice.mathMode) {
            return buildMathSsmlDocument(params, text)
        }
        val normalized = SpeechTextProcessor.normalizeShorthandSsml(text)
        val processed = applyPronunciationDictionary(normalized, dictionary)
        val escaped = escapeForSsml(processed)
        val content = if (params.baseLang.isNotBlank() && params.baseLang != "en-US") {
            "<lang xml:lang=\"${params.baseLang}\">$escaped</lang>"
        } else {
            escaped
        }
        return buildSsmlDocument(params, content)
    }

    /**
     * Generate SSML by weaving <lang> tags through provided segments.
     */
    fun generateSsml(segments: List<SpeechSegment>, voice: Voice, dictionary: List<io.github.jdreioe.wingmate.domain.PronunciationEntry> = emptyList()): String {
        val params = resolveVoiceParams(voice)
        val content = buildString {
            segments.forEach { segment ->
                if (segment.text.isNotEmpty()) {
                    val normalized = SpeechTextProcessor.normalizeShorthandSsml(segment.text)
                    val processed = applyPronunciationDictionary(normalized, dictionary)
                    val escaped = escapeForSsml(processed)
                    val overrideLang = segment.languageTag?.takeIf { it.isNotBlank() }
                    if (!overrideLang.isNullOrBlank() && overrideLang != params.baseLang) {
                        append("<lang xml:lang=\"$overrideLang\">$escaped</lang>")
                    } else {
                        append(escaped)
                    }
                }
                if (segment.pauseDurationMs > 0) {
                    append("<break time=\"${segment.pauseDurationMs}ms\"/>")
                }
            }
        }
        return buildSsmlDocument(params, content)
    }

    /**
     * Replaces words with <phoneme> tags based on the provided dictionary.
     */
    private fun applyPronunciationDictionary(text: String, dictionary: List<io.github.jdreioe.wingmate.domain.PronunciationEntry>): String {
        if (dictionary.isEmpty()) return text
        var result = text
        // Sort by length descending to avoid partial matches (e.g. "car" matching "carpet")
        val sortedDict = dictionary.sortedByDescending { it.word.length }
        
        for (entry in sortedDict) {
            if (entry.word.isBlank() || entry.phoneme.isBlank()) continue
            
            // Regex to match whole word, case insensitive
            val regex = Regex("\\b${Regex.escape(entry.word)}\\b", RegexOption.IGNORE_CASE)
            result = result.replace(regex) { match ->
                if (entry.alphabet == "text") {
                    "<sub alias=\"${entry.phoneme}\">${match.value}</sub>"
                } else {
                    "<phoneme alphabet=\"${entry.alphabet}\" ph=\"${entry.phoneme}\">${match.value}</phoneme>"
                }
            }
        }
        return result
    }
    
    /**
     * Convert numeric pitch (0.0-2.0) to SSML pitch string
     */
    private fun convertPitchToSSML(pitch: Double): String {
        return when {
            pitch < 0.7 -> "x-low"
            pitch < 0.8 -> "low" 
            pitch < 1.2 -> "medium"
            pitch < 1.5 -> "high"
            else -> "x-high"
        }
    }
    
    /**
     * Convert numeric rate (0.0-2.0) to SSML rate string
     */
    private fun convertRateToSSML(rate: Double): String {
        return when {
            rate < 0.7 -> "x-slow"
            rate < 0.8 -> "slow"
            rate < 1.2 -> "medium" 
            rate < 1.5 -> "fast"
            else -> "x-fast"
        }
    }

    /**
     * Enhanced SSML text escaping with comprehensive character support
     */
    private fun escapeForSsml(text: String): String {
        // Find segments that are tags vs plain text
        val tagRegex = Regex("<[^>]+>")
        val matches = tagRegex.findAll(text)
        
        if (matches.none()) {
            return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
        }
        
        val result = StringBuilder()
        var lastIndex = 0
        
        for (match in matches) {
            // 1. Escape the text BEFORE the tag
            val preText = text.substring(lastIndex, match.range.first)
            result.append(preText.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;"))
            
            // 2. Add the tag AS-IS (do not escape the code parts of it)
            result.append(match.value)
            
            lastIndex = match.range.last + 1
        }
        
        // 3. Escape the remaining text AFTER the last tag
        if (lastIndex < text.length) {
            val postText = text.substring(lastIndex)
            result.append(postText.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;"))
        }
        
        return result.toString()
    }

    private fun escapeXmlText(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private data class VoiceParams(
        val voiceName: String,
        val baseLang: String,
        val pitch: String,
        val rate: String
    )

    private fun resolveVoiceParams(voice: Voice): VoiceParams {
        val voiceName = voice.name ?: "en-US-JennyNeural"
        val baseLang =
            voice.selectedLanguage.takeIf(String::isNotBlank)
                ?: voice.primaryLanguage?.takeIf(String::isNotBlank)
                ?: "en-US"
        val pitch = voice.pitchForSSML ?: voice.pitch?.let(::convertPitchToSSML) ?: "medium"
        val rate = voice.rateForSSML ?: voice.rate?.let(::convertRateToSSML) ?: "medium"
        return VoiceParams(voiceName, baseLang, pitch, rate)
    }

    private fun buildSsmlDocument(params: VoiceParams, content: String): String {
        val primaryWrapped = "<lang xml:lang=\"${params.baseLang}\">$content</lang>"
        val inner = buildString {
            append("<prosody pitch=\"${params.pitch}\">")
            append("<prosody rate=\"${params.rate}\">")
            append(primaryWrapped)
            append("</prosody>")
            append("</prosody>")
        }
                return """
                        <speak version="1.0" xml:lang="${params.baseLang}">
                            <voice xml:lang="${params.baseLang}" name="${params.voiceName}">
                                $inner
                            </voice>
                        </speak>
                """.trimIndent()
    }

    private fun buildMathSsmlDocument(params: VoiceParams, expression: String): String = """
        <speak version="1.0" xmlns="http://www.w3.org/2001/10/synthesis" xmlns:mstts="http://www.w3.org/2001/mstts" xml:lang="${params.baseLang}">
            <voice name="${params.voiceName}">
                <lang xml:lang="${params.baseLang}">
                    ${mathMlElement(expression)}
                </lang>
            </voice>
        </speak>
    """.trimIndent()

    /**
     * Preserves native MathML 2/3 documents and fragments verbatim after checking that they are
     * well-formed and contained by one MathML root. Plain calculator text uses the shorthand parser.
     */
    private fun mathMlElement(input: String): String {
        val trimmed = input.trim()
        if (trimmed.startsWith("<")) {
            val candidate = if (trimmed.startsWith("<math", ignoreCase = true)) {
                trimmed
            } else {
                "<math xmlns=\"$MATHML_NAMESPACE\">$trimmed</math>"
            }
            if (isContainedMathMl(candidate)) return candidate
        }

        return "<math xmlns=\"$MATHML_NAMESPACE\">${PlainTextMathMlParser(input).parse()}</math>"
    }

    /** Rejects declarations and multiple roots so MathML cannot escape into the surrounding SSML. */
    private fun isContainedMathMl(candidate: String): Boolean = runCatching {
        val reader = xmlStreaming.newGenericReader(candidate)
        var rootSeen = false
        var rootCompleted = false
        var openElements = 0
        try {
            while (reader.hasNext()) {
                when (reader.next()) {
                    EventType.START_ELEMENT -> {
                        if (openElements == 0) {
                            if (rootSeen || rootCompleted || reader.localName != "math") return@runCatching false
                            if (reader.namespaceURI.isNotEmpty() && reader.namespaceURI != MATHML_NAMESPACE) {
                                return@runCatching false
                            }
                            rootSeen = true
                        }
                        openElements++
                    }
                    EventType.END_ELEMENT -> {
                        openElements--
                        if (openElements < 0) return@runCatching false
                        if (openElements == 0) rootCompleted = true
                    }
                    EventType.TEXT,
                    EventType.CDSECT,
                    EventType.IGNORABLE_WHITESPACE -> {
                        if (openElements == 0 && reader.text.isNotBlank()) return@runCatching false
                    }
                    EventType.DOCDECL,
                    EventType.PROCESSING_INSTRUCTION -> return@runCatching false
                    else -> Unit
                }
            }
        } finally {
            reader.close()
        }
        rootSeen && rootCompleted && openElements == 0
    }.getOrDefault(false)

    /** Converts calculator/keyboard input into safe presentation MathML for Azure Speech. */
    private class PlainTextMathMlParser(private val expression: String) {
        private var index = 0

        fun parse(): String = parseSequence(stopAtClosingParenthesis = false).joinToString("")

        private fun parseSequence(stopAtClosingParenthesis: Boolean): MutableList<String> {
            val nodes = mutableListOf<String>()
            while (index < expression.length) {
                val character = expression[index]
                when {
                    character.isWhitespace() -> index++
                    character == ')' && stopAtClosingParenthesis -> break
                    character == '/' -> {
                        index++
                        val denominator = parseScriptedAtom()
                        if (nodes.isNotEmpty() && denominator != null) {
                            nodes[nodes.lastIndex] = "<mfrac>${nodes.last()}$denominator</mfrac>"
                        } else {
                            nodes += operatorNode("/")
                            if (denominator != null) nodes += denominator
                        }
                    }
                    character == '^' -> {
                        index++
                        val exponent = parseExponent()
                        if (nodes.isNotEmpty() && exponent != null) {
                            nodes[nodes.lastIndex] = "<msup>${nodes.last()}$exponent</msup>"
                        } else {
                            nodes += operatorNode("^")
                            if (exponent != null) nodes += exponent
                        }
                    }
                    character in SUPERSCRIPT_DIGITS -> {
                        val exponent = readSuperscriptNumber()
                        val exponentNode = numberNode(exponent)
                        if (nodes.isNotEmpty()) {
                            nodes[nodes.lastIndex] = "<msup>${nodes.last()}$exponentNode</msup>"
                        } else {
                            nodes += exponentNode
                        }
                    }
                    else -> {
                        val atom = parseAtom()
                        if (nodes.lastOrNull()?.let(::isOperandNode) == true && isOperandNode(atom)) {
                            nodes += operatorNode(INVISIBLE_TIMES)
                        }
                        nodes += atom
                    }
                }
            }
            return nodes
        }

        private fun parseScriptedAtom(): String? {
            skipWhitespace()
            if (index >= expression.length || expression[index] == ')') return null

            var node = parseAtom()
            var readingScripts = true
            while (readingScripts && index < expression.length) {
                when (expression[index]) {
                    '^' -> {
                        index++
                        val exponent = parseExponent()
                        if (exponent != null) node = "<msup>$node$exponent</msup>"
                    }
                    in SUPERSCRIPT_DIGITS -> {
                        node = "<msup>$node${numberNode(readSuperscriptNumber())}</msup>"
                    }
                    else -> readingScripts = false
                }
            }
            return node
        }

        private fun parseExponent(): String? {
            skipWhitespace()
            if (index >= expression.length) return null
            if (expression[index] == '(') {
                index++
                val content = parseSequence(stopAtClosingParenthesis = true).joinToString("")
                if (index < expression.length && expression[index] == ')') index++
                return "<mrow>$content</mrow>"
            }
            return parseAtom()
        }

        private fun parseAtom(): String {
            val character = expression[index]
            return when {
                character.isDigit() || (character == '.' && expression.getOrNull(index + 1)?.isDigit() == true) -> {
                    val start = index
                    var decimalPointSeen = false
                    while (index < expression.length) {
                        val next = expression[index]
                        if (next.isDigit()) {
                            index++
                        } else if (next == '.' && !decimalPointSeen) {
                            decimalPointSeen = true
                            index++
                        } else {
                            break
                        }
                    }
                    numberNode(expression.substring(start, index))
                }
                character.isLetter() || character == 'π' -> {
                    val start = index++
                    while (index < expression.length && expression[index].isLetter()) index++
                    val identifier = identifierNode(expression.substring(start, index))
                    skipWhitespace()
                    if (index < expression.length && expression[index] == '(') {
                        index++
                        val arguments = parseSequence(stopAtClosingParenthesis = true).joinToString("")
                        if (index < expression.length && expression[index] == ')') index++
                        "<mrow>$identifier${operatorNode(FUNCTION_APPLICATION)}<mfenced>$arguments</mfenced></mrow>"
                    } else {
                        identifier
                    }
                }
                character == '√' -> {
                    index++
                    skipWhitespace()
                    if (index < expression.length && expression[index] == '(') {
                        index++
                        val content = parseSequence(stopAtClosingParenthesis = true).joinToString("")
                        if (index < expression.length && expression[index] == ')') index++
                        "<msqrt>$content</msqrt>"
                    } else if (index < expression.length) {
                        "<msqrt>${parseAtom()}</msqrt>"
                    } else {
                        operatorNode("√")
                    }
                }
                character == '(' -> {
                    index++
                    val content = parseSequence(stopAtClosingParenthesis = true).joinToString("")
                    if (index < expression.length && expression[index] == ')') index++
                    "<mrow>${operatorNode("(")}$content${operatorNode(")")}</mrow>"
                }
                else -> {
                    index++
                    operatorNode(character.toString())
                }
            }
        }

        private fun readSuperscriptNumber(): String = buildString {
            while (index < expression.length) {
                val digit = SUPERSCRIPT_DIGIT_VALUES[expression[index]] ?: break
                append(digit)
                index++
            }
        }

        private fun skipWhitespace() {
            while (index < expression.length && expression[index].isWhitespace()) index++
        }

        private fun numberNode(value: String): String = "<mn>${escapeXmlText(value)}</mn>"
        private fun identifierNode(value: String): String = "<mi>${escapeXmlText(value)}</mi>"
        private fun operatorNode(value: String): String {
            val normalized = if (value == "*") "×" else value
            return "<mo>${escapeXmlText(normalized)}</mo>"
        }

        private fun isOperandNode(node: String): Boolean = !node.startsWith("<mo>")

        companion object {
            private const val FUNCTION_APPLICATION = "⁡"
            private const val INVISIBLE_TIMES = "⁢"
            private const val SUPERSCRIPT_DIGITS = "⁰¹²³⁴⁵⁶⁷⁸⁹"
            private val SUPERSCRIPT_DIGIT_VALUES = SUPERSCRIPT_DIGITS
                .mapIndexed { digit, character -> character to digit }
                .toMap()
        }
    }

    private const val MATHML_NAMESPACE = "http://www.w3.org/1998/Math/MathML"
    
    // ========================================================================
    // TOKEN-BASED AUTHENTICATION (Secure Backend)
    // ========================================================================
    
    /**
     * Synthesize speech using a bearer token instead of subscription key.
     * 
     * This is the secure method that should be used in production:
     * 1. Client calls TokenExchangeClient.getToken() to get a short-lived token
     * 2. This method uses that token to call Azure TTS directly
     * 3. No subscription key is ever stored on the client device
     * 
     * @param client Ktor HTTP client
     * @param ssml The SSML document to synthesize
     * @param token Bearer token from TokenExchangeClient
     * @param region Azure region (e.g., "eastus")
     * @param audioFormat Desired audio format
     */
    suspend fun synthesizeWithToken(
        client: HttpClient,
        ssml: String,
        token: String,
        region: String,
        audioFormat: AudioFormat = AudioFormat.MP3_24KHZ_160KBPS
    ): ByteArray {
        val endpoint = requireAzureSpeechEndpoint(region)
        requireCredentialSafeClient(client)
        val url = endpoint.synthesisUrl
        
        OperationalLogger.info("azure_tts.token_synthesize", "started")
        
        try {
            val response: HttpResponse = client.post(url) {
                headers {
                    // Use Bearer token instead of subscription key
                    append(HttpHeaders.Authorization, "Bearer $token")
                    append(HttpHeaders.ContentType, "application/ssml+xml")
                    append("X-Microsoft-OutputFormat", audioFormat.value)
                    append(HttpHeaders.UserAgent, "WingmateKMP/2.0")
                    append(HttpHeaders.Accept, "audio/*")
                }
                setBody(ssml)
            }
            
            OperationalLogger.info("azure_tts.token_synthesize", "response_received", statusCode = response.status.value)
            
            when {
                response.status.isSuccess() -> {
                    val bytes = response.body<ByteArray>()
                    OperationalLogger.info("azure_tts.token_synthesize", "succeeded", count = bytes.size)
                    
                    if (bytes.isEmpty()) {
                        throw RuntimeException("Azure TTS returned empty audio data")
                    }
                    return bytes
                }
                response.status.value == 401 -> {
                    OperationalLogger.warn("azure_tts.token_synthesize", "authentication_failed", statusCode = 401)
                    throw TokenExpiredException("Azure TTS token expired or invalid")
                }
                response.status.value == 429 -> {
                    OperationalLogger.warn("azure_tts.token_synthesize", "rate_limited", statusCode = 429)
                    throw RuntimeException("Azure TTS rate limit exceeded. Please try again later.")
                }
                else -> {
                    OperationalLogger.error(
                        operation = "azure_tts.token_synthesize",
                        outcome = "failed",
                        statusCode = response.status.value,
                    )
                    throw RuntimeException("Azure TTS failed: ${response.status}")
                }
            }
        } catch (e: TokenExpiredException) {
            throw e
        } catch (e: RuntimeException) {
            throw e
        } catch (e: Exception) {
            OperationalLogger.error(
                operation = "azure_tts.token_synthesize",
                outcome = "network_failed",
                exceptionClass = e.loggingClassName(),
            )
            throw RuntimeException("Azure TTS network error", e)
        }
    }

    
    suspend fun getVoices(
        client: HttpClient, 
        config: SpeechServiceConfig
    ): List<Voice> {
        val endpoint = requireAzureSpeechEndpoint(config.endpoint)
        requireCredentialSafeClient(client)
        val url = endpoint.voicesUrl
        
        OperationalLogger.info("azure_voice_catalog.fetch", "started")
        
        try {
            val response: HttpResponse = client.get(url) {
                headers {
                    append("Ocp-Apim-Subscription-Key", config.subscriptionKey)
                    append(HttpHeaders.UserAgent, "WingmateKMP/2.0")
                    append(HttpHeaders.Accept, "application/json")
                }
            }
            
            if (response.status.isSuccess()) {
                val azureVoices = response.body<List<AzureVoiceDto>>()
                OperationalLogger.info("azure_voice_catalog.fetch", "succeeded", count = azureVoices.size)
                return azureVoices.map { it.toDomain() }
            } else {
                OperationalLogger.error(
                    operation = "azure_voice_catalog.fetch",
                    outcome = "failed",
                    statusCode = response.status.value,
                )
                throw RuntimeException("Failed to fetch voices: ${response.status}")
            }
        } catch (e: Exception) {
            OperationalLogger.error(
                operation = "azure_voice_catalog.fetch",
                outcome = "failed",
                exceptionClass = e.loggingClassName(),
            )
            throw e
        }
    }
    
    @kotlinx.serialization.Serializable
    private data class AzureVoiceDto(
        val Name: String,
        val ShortName: String,
        val Gender: String,
        val Locale: String,
        val LocalName: String? = null,
        val DisplayName: String? = null
    ) {
        fun toDomain(): Voice {
            val display = if (LocalName != null && DisplayName != null) {
                "$LocalName ($DisplayName)" 
            } else {
                DisplayName ?: LocalName ?: ShortName
            }
            
            return Voice(
                name = ShortName,
                displayName = display,
                primaryLanguage = Locale,
                gender = Gender,
                provider = VoiceProvider.AZURE,
                // Assume 1.0 default pitch/rate
                pitch = 1.0, 
                rate = 1.0
            )
        }
    }

    /**
     * Ktor follows GET redirects by default and only strips its standard
     * Authorization header when the authority changes. Azure's subscription-key
     * header would otherwise be forwarded to the redirect target.
     */
    private fun requireCredentialSafeClient(client: HttpClient) {
        check(client.pluginOrNull(HttpRedirect) == null) {
            "Azure credential requests require an HTTP client with redirects disabled"
        }
    }
}

/**
 * Exception thrown when the Azure TTS token has expired.
 * The caller should invalidate the cached token and request a new one.
 */
class TokenExpiredException(message: String) : Exception(message)
