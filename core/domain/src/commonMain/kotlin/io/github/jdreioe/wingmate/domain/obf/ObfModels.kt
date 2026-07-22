package io.github.jdreioe.wingmate.domain.obf

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ObfBoard(
    val format: String,
    val id: String,
    val locale: String? = null,
    val url: String? = null,
    val name: String? = null,
    @SerialName("description_html")
    val descriptionHtml: String? = null,
    @SerialName("background_color")
    val backgroundColor: String? = null,
    val buttons: List<ObfButton> = emptyList(),
    val images: List<ObfImage> = emptyList(),
    val grid: ObfGrid? = null,
    val sounds: List<ObfSound> = emptyList(),
    val strings: Map<String, Map<String, String>> = emptyMap(),
    val license: ObfLicense? = null,
    val extensions: Map<String, JsonElement> = emptyMap()
) {
    val isAbsoluteLayout: Boolean
        get() = buttons.isNotEmpty() && buttons.all {
            it.top != null && it.left != null && it.width != null && it.height != null
        }
}

@Serializable
data class ObfButton(
    val id: String,
    val label: String? = null,
    val vocalization: String? = null,
    val locale: String? = null,
    @SerialName("image_id")
    val imageId: String? = null,
    @SerialName("sound_id")
    val soundId: String? = null,
    @SerialName("background_color")
    val backgroundColor: String? = null,
    @SerialName("border_color")
    val borderColor: String? = null,
    val top: Double? = null,
    val left: Double? = null,
    val width: Double? = null,
    val height: Double? = null,
    @SerialName("load_board")
    val loadBoard: ObfLoadBoard? = null,
    val action: String? = null,
    val actions: List<String> = emptyList(),
    val hidden: Boolean = false,
    val extensions: Map<String, JsonElement> = emptyMap()
) {
    fun resolvedActions(): List<String> =
        if (actions.isNotEmpty()) actions else listOfNotNull(action?.takeIf { it.isNotBlank() })
}

@Serializable
data class ObfGrid(
    val rows: Int,
    val columns: Int,
    val order: List<List<String?>>
)

@Serializable
data class ObfImage(
    val id: String,
    val width: Int? = null,
    val height: Int? = null,
    @SerialName("content_type")
    val contentType: String? = null,
    val url: String? = null,
    val path: String? = null,
    val data: String? = null,
    @SerialName("data_url")
    val dataUrl: String? = null,
    val symbol: ObfSymbol? = null,
    val license: ObfLicense? = null,
    val extensions: Map<String, JsonElement> = emptyMap()
)

@Serializable
data class ObfSymbol(
    val set: String? = null,
    val filename: String? = null,
    @SerialName("library_key")
    val libraryKey: String? = null
)

@Serializable
data class ObfSound(
    val id: String,
    @SerialName("content_type")
    val contentType: String? = null,
    val url: String? = null,
    val path: String? = null,
    val data: String? = null,
    @SerialName("data_url")
    val dataUrl: String? = null,
    val license: ObfLicense? = null,
    val extensions: Map<String, JsonElement> = emptyMap()
)

@Serializable
data class ObfLoadBoard(
    val id: String? = null,
    val name: String? = null,
    val url: String? = null,
    val path: String? = null,
    @SerialName("data_url")
    val dataUrl: String? = null,
    val extensions: Map<String, JsonElement> = emptyMap()
)

@Serializable
data class ObfLicense(
    val type: String? = null,
    @SerialName("copyright_notice_url")
    val copyrightNoticeUrl: String? = null,
    @SerialName("source_url")
    val sourceUrl: String? = null,
    @SerialName("author_name")
    val authorName: String? = null,
    @SerialName("author_url")
    val authorUrl: String? = null,
    @SerialName("author_email")
    val authorEmail: String? = null,
    val extensions: Map<String, JsonElement> = emptyMap()
)

@Serializable
data class ObfManifest(
    val format: String,
    val root: String,
    val paths: ObfManifestPaths,
    val extensions: Map<String, JsonElement> = emptyMap()
)

@Serializable
data class ObfManifestPaths(
    val boards: Map<String, String> = emptyMap(),
    val images: Map<String, String> = emptyMap(),
    val sounds: Map<String, String> = emptyMap(),
    val extensions: Map<String, JsonElement> = emptyMap()
)
