package io.github.jdreioe.wingmate.domain.obf

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

const val OBF_PAGE_ELEMENTS_EXTENSION = "ext_wingmate_page_elements"

object PageElementTypes {
    const val PhraseCollection = "phrase_collection"
    const val PageNavigation = "page_navigation"
    const val ActionStrip = "action_strip"

    val supported = setOf(PhraseCollection, PageNavigation, ActionStrip)
}

/**
 * A responsive rectangle in a Page's outer Wingmate layout.
 *
 * [type] and [configuration] remain open so a client can preserve elements
 * created by a newer Wingmate version without understanding or discarding them.
 */
@Serializable
data class PageElement(
    val id: String,
    val type: String,
    val row: Int,
    val column: Int,
    val rowSpan: Int = 1,
    val columnSpan: Int = 1,
    val configuration: JsonObject = JsonObject(emptyMap()),
    /** Top-level fields written by a newer client, retained verbatim on save. */
    @Transient
    val unknownProperties: JsonObject = JsonObject(emptyMap()),
) {
    val isSupported: Boolean
        get() = content !is PageElementContent.Unsupported

    val content: PageElementContent
        get() = when (type) {
            PageElementTypes.PhraseCollection -> decodeConfiguration<PhraseCollectionElementConfig>()
                ?.let(PageElementContent::PhraseCollection)
                ?: PageElementContent.Unsupported(type, configuration)
            PageElementTypes.PageNavigation -> decodeConfiguration<PageNavigationElementConfig>()
                ?.let(PageElementContent::PageNavigation)
                ?: PageElementContent.Unsupported(type, configuration)
            PageElementTypes.ActionStrip -> decodeConfiguration<ActionStripElementConfig>()
                ?.let(PageElementContent::ActionStrip)
                ?: PageElementContent.Unsupported(type, configuration)
            else -> PageElementContent.Unsupported(type, configuration)
        }
}

sealed interface PageElementContent {
    data class PhraseCollection(val configuration: PhraseCollectionElementConfig) : PageElementContent
    data class PageNavigation(val configuration: PageNavigationElementConfig) : PageElementContent
    data class ActionStrip(val configuration: ActionStripElementConfig) : PageElementContent
    data class Unsupported(val type: String, val rawConfiguration: JsonObject) : PageElementContent
}

@Serializable
data class PhraseCollectionElementConfig(
    val columns: Int? = null,
)

@Serializable
data class PageNavigationElementConfig(
    val showAddCategory: Boolean = true,
)

@Serializable
data class ActionStripElementConfig(
    val buttonIds: List<String> = emptyList(),
)

private val pageElementJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    coerceInputValues = true
}

fun ObfBoard.pageElements(): List<PageElement> {
    val raw = extensions[OBF_PAGE_ELEMENTS_EXTENSION] as? JsonArray ?: return emptyList()
    return raw.mapNotNull { (it as? JsonObject)?.decodePageElement() }
}

fun ObfBoard.withPageElements(elements: List<PageElement>): ObfBoard {
    val remaining = elements.associateByTo(linkedMapOf(), PageElement::id)
    val existing = extensions[OBF_PAGE_ELEMENTS_EXTENSION] as? JsonArray ?: JsonArray(emptyList())
    val merged = buildList {
        existing.forEach { rawElement ->
            val decoded = (rawElement as? JsonObject)?.decodePageElement()
            if (decoded == null) {
                // A newer or malformed element remains recoverable even when this
                // client cannot offer editing controls for it.
                add(rawElement)
            } else {
                remaining.remove(decoded.id)?.let { add(it.encodePageElement()) }
            }
        }
        remaining.values.forEach { add(it.encodePageElement()) }
    }
    return copy(
        extensions = if (merged.isEmpty()) {
            extensions - OBF_PAGE_ELEMENTS_EXTENSION
        } else {
            extensions + (OBF_PAGE_ELEMENTS_EXTENSION to JsonArray(merged))
        },
    )
}

private fun JsonObject.decodePageElement(): PageElement? =
    runCatching { pageElementJson.decodeFromJsonElement<PageElement>(this) }
        .getOrNull()
        ?.copy(
            unknownProperties = JsonObject(filterKeys { it !in PAGE_ELEMENT_PROPERTIES })
        )

private fun PageElement.encodePageElement(): JsonObject {
    val knownProperties = pageElementJson.encodeToJsonElement(this) as JsonObject
    return JsonObject(unknownProperties + knownProperties)
}

inline fun <reified T> PageElement.decodeConfiguration(): T? = runCatching {
    pageElementJsonForConfiguration.decodeFromJsonElement<T>(configuration)
}.getOrNull()

fun PageElement.withConfiguration(value: PhraseCollectionElementConfig): PageElement =
    copy(configuration = pageElementJson.encodeToJsonElement(value) as JsonObject)

fun PageElement.withConfiguration(value: PageNavigationElementConfig): PageElement =
    copy(configuration = pageElementJson.encodeToJsonElement(value) as JsonObject)

fun PageElement.withConfiguration(value: ActionStripElementConfig): PageElement =
    copy(configuration = pageElementJson.encodeToJsonElement(value) as JsonObject)

@PublishedApi
internal val pageElementJsonForConfiguration = pageElementJson

private val PAGE_ELEMENT_PROPERTIES = setOf(
    "id",
    "type",
    "row",
    "column",
    "rowSpan",
    "columnSpan",
    "configuration",
)
