package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.SpeechServiceConfig
import io.ktor.http.URLProtocol
import io.ktor.http.Url

/**
 * A validated Azure Speech authority that is safe to receive a BYOK subscription key.
 *
 * Microsoft documents regional TTS endpoints under `*.tts.speech.*`, Azure Portal
 * regional endpoints under `*.api.cognitive.*`, and resource endpoints under
 * `*.cognitiveservices.*`. Only those exact authority shapes are accepted; callers
 * cannot supply paths, query strings, fragments, user-info, or non-HTTPS schemes.
 */
class AzureSpeechEndpoint private constructor(
    /** Canonical value stored in the user's secure configuration. */
    val persistedValue: String,
    /** HTTPS origin used for credential-bearing requests. */
    val baseUrl: String,
    private val kind: Kind,
) {
    val synthesisUrl: String
        get() = "$baseUrl/cognitiveservices/v1"

    val voicesUrl: String
        get() = when (kind) {
            Kind.REGIONAL -> "$baseUrl/cognitiveservices/voices/list"
            Kind.RESOURCE -> "$baseUrl/tts/cognitiveservices/voices/list"
        }

    private enum class Kind { REGIONAL, RESOURCE }

    companion object {
        fun parse(rawValue: String): AzureSpeechEndpointResult {
            val value = rawValue.trim()
            if (value.isEmpty()) {
                return AzureSpeechEndpointResult.Invalid(AzureSpeechEndpointError.EMPTY)
            }
            if (value.any { it.code !in PRINTABLE_ASCII_RANGE } || '\\' in value || '%' in value) {
                return AzureSpeechEndpointResult.Invalid(AzureSpeechEndpointError.INVALID_FORMAT)
            }

            if (SCHEME_SEPARATOR !in value && '.' !in value) {
                return parseRegion(value)
            }

            if (SCHEME_SEPARATOR in value && !value.startsWith(HTTPS_PREFIX, ignoreCase = true)) {
                return AzureSpeechEndpointResult.Invalid(AzureSpeechEndpointError.HTTPS_REQUIRED)
            }

            val candidate = if (SCHEME_SEPARATOR in value) value else "$HTTPS_PREFIX$value"
            val authority = candidate.substringAfter(SCHEME_SEPARATOR).substringBefore('/')
            if (':' in authority) {
                return AzureSpeechEndpointResult.Invalid(AzureSpeechEndpointError.UNEXPECTED_URL_COMPONENT)
            }
            val url = runCatching { Url(candidate) }.getOrNull()
                ?: return AzureSpeechEndpointResult.Invalid(AzureSpeechEndpointError.INVALID_FORMAT)

            if (url.protocol != URLProtocol.HTTPS) {
                return AzureSpeechEndpointResult.Invalid(AzureSpeechEndpointError.HTTPS_REQUIRED)
            }
            if (
                url.user != null ||
                url.password != null ||
                url.port != HTTPS_PORT ||
                url.encodedPath !in setOf("", "/") ||
                !url.parameters.isEmpty() ||
                url.trailingQuery ||
                url.fragment.isNotEmpty() ||
                '@' in value ||
                '?' in value ||
                '#' in value
            ) {
                return AzureSpeechEndpointResult.Invalid(AzureSpeechEndpointError.UNEXPECTED_URL_COMPONENT)
            }

            val host = url.host.lowercase()
            if (!host.isValidHostName()) {
                return AzureSpeechEndpointResult.Invalid(AzureSpeechEndpointError.INVALID_FORMAT)
            }

            GENERIC_REGIONAL_SUFFIXES.entries.firstOrNull { (suffix) ->
                host.hasSingleLabelBefore(suffix)
            }?.let { (genericSuffix, ttsSuffix) ->
                val region = host.removeSuffix(genericSuffix)
                return AzureSpeechEndpointResult.Valid(
                    AzureSpeechEndpoint(
                        persistedValue = region,
                        baseUrl = "$HTTPS_PREFIX$region$ttsSuffix",
                        kind = Kind.REGIONAL,
                    )
                )
            }

            val kind = when {
                REGIONAL_SUFFIXES.any { host.hasSingleLabelBefore(it) } -> Kind.REGIONAL
                RESOURCE_SUFFIXES.any { host.hasSingleLabelBefore(it) } -> Kind.RESOURCE
                else -> return AzureSpeechEndpointResult.Invalid(AzureSpeechEndpointError.UNSUPPORTED_HOST)
            }
            return AzureSpeechEndpointResult.Valid(
                AzureSpeechEndpoint(
                    persistedValue = "$HTTPS_PREFIX$host",
                    baseUrl = "$HTTPS_PREFIX$host",
                    kind = kind,
                )
            )
        }

        private fun parseRegion(value: String): AzureSpeechEndpointResult {
            val region = value.lowercase()
            if (!region.isValidDnsLabel()) {
                return AzureSpeechEndpointResult.Invalid(AzureSpeechEndpointError.INVALID_FORMAT)
            }
            val suffix = when (region) {
                in AZURE_GOVERNMENT_REGIONS -> ".tts.speech.azure.us"
                in AZURE_CHINA_REGIONS -> ".tts.speech.azure.cn"
                else -> ".tts.speech.microsoft.com"
            }
            return AzureSpeechEndpointResult.Valid(
                AzureSpeechEndpoint(
                    persistedValue = region,
                    baseUrl = "$HTTPS_PREFIX$region$suffix",
                    kind = Kind.REGIONAL,
                )
            )
        }

        private val REGIONAL_SUFFIXES = setOf(
            ".tts.speech.microsoft.com",
            ".tts.speech.azure.us",
            ".tts.speech.azure.cn",
        )
        private val RESOURCE_SUFFIXES = setOf(
            ".cognitiveservices.azure.com",
            ".cognitiveservices.azure.us",
            ".cognitiveservices.azure.cn",
        )
        private val GENERIC_REGIONAL_SUFFIXES = mapOf(
            ".api.cognitive.microsoft.com" to ".tts.speech.microsoft.com",
            ".api.cognitive.microsoft.us" to ".tts.speech.azure.us",
            ".api.cognitive.azure.cn" to ".tts.speech.azure.cn",
        )
        private val AZURE_GOVERNMENT_REGIONS = setOf("usgovarizona", "usgovvirginia")
        private val AZURE_CHINA_REGIONS = setOf("chinaeast2", "chinanorth2", "chinanorth3")
        private val PRINTABLE_ASCII_RANGE = 0x21..0x7e
        private const val HTTPS_PREFIX = "https://"
        private const val SCHEME_SEPARATOR = "://"
        private const val HTTPS_PORT = 443
    }
}

enum class AzureSpeechEndpointError {
    EMPTY,
    HTTPS_REQUIRED,
    INVALID_FORMAT,
    UNEXPECTED_URL_COMPONENT,
    UNSUPPORTED_HOST,
}

sealed interface AzureSpeechEndpointResult {
    data class Valid(val endpoint: AzureSpeechEndpoint) : AzureSpeechEndpointResult
    data class Invalid(val error: AzureSpeechEndpointError) : AzureSpeechEndpointResult
}

fun requireAzureSpeechEndpoint(rawValue: String): AzureSpeechEndpoint =
    when (val result = AzureSpeechEndpoint.parse(rawValue)) {
        is AzureSpeechEndpointResult.Valid -> result.endpoint
        is AzureSpeechEndpointResult.Invalid -> throw IllegalArgumentException(
            "Enter a valid Azure Speech region or official HTTPS endpoint."
        )
    }

/** Validates and canonicalizes new configuration before it reaches secure storage. */
fun SpeechServiceConfig.validatedForStorage(): SpeechServiceConfig {
    require(subscriptionKey.isNotBlank()) { "Azure subscription key must not be blank" }
    val endpoint = requireAzureSpeechEndpoint(endpoint)
    return copy(
        endpoint = endpoint.persistedValue,
        subscriptionKey = subscriptionKey.trim(),
    )
}

/**
 * Canonicalizes a readable legacy value without deleting an invalid saved credential.
 * Invalid legacy endpoints remain visible in settings but are rejected before networking.
 */
fun SpeechServiceConfig.normalizedIfValid(): SpeechServiceConfig =
    when (val result = AzureSpeechEndpoint.parse(endpoint)) {
        is AzureSpeechEndpointResult.Valid -> copy(endpoint = result.endpoint.persistedValue)
        is AzureSpeechEndpointResult.Invalid -> this
    }

private fun String.hasSingleLabelBefore(suffix: String): Boolean {
    if (!endsWith(suffix)) return false
    val prefix = removeSuffix(suffix)
    return prefix.isValidDnsLabel()
}

private fun String.isValidHostName(): Boolean =
    length <= 253 && split('.').all(String::isValidDnsLabel)

private fun String.isValidDnsLabel(): Boolean =
    length in 1..63 &&
        first().isAsciiLetterOrDigit() &&
        last().isAsciiLetterOrDigit() &&
        all { it.isAsciiLetterOrDigit() || it == '-' }

private fun Char.isAsciiLetterOrDigit(): Boolean =
    this in 'a'..'z' || this in '0'..'9'
