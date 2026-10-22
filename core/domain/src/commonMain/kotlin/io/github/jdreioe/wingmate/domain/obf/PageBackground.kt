package io.github.jdreioe.wingmate.domain.obf

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val OBF_BACKGROUND_IMAGE_EXTENSION = "ext_wingmate_background_image"

/** How a Page background image covers the Page (#103). */
@Serializable
enum class PageBackgroundFit {
    /** Covers the whole Page, cropping what does not fit. */
    @SerialName("fill") Fill,
    /** Shows the whole image, leaving the background color around it. */
    @SerialName("fit") Fit,
}

/**
 * A Page's background image, drawn behind its Buttons. It names one of the Page's own
 * [ObfBoard.images], so OBZ export, import and backups carry its media like any Button image.
 */
@Serializable
data class PageBackgroundImage(
    @SerialName("image_id") val imageId: String,
    val fit: PageBackgroundFit = PageBackgroundFit.Fill,
)

private val backgroundJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** The Page's background image, or null when there is none or its image entry is missing. */
val ObfBoard.backgroundImage: PageBackgroundImage?
    get() {
        val element = extensions[OBF_BACKGROUND_IMAGE_EXTENSION] ?: return null
        val background = runCatching { backgroundJson.decodeFromJsonElement(PageBackgroundImage.serializer(), element) }.getOrNull()
        return background?.takeIf { bg -> images.any { it.id == bg.imageId } }
    }

/**
 * Sets the Page's background to [image] with [fit], or removes it when [image] is null.
 * The previous background image entry goes too, unless a Button still uses it.
 */
fun ObfBoard.withBackgroundImage(image: ObfImage?, fit: PageBackgroundFit = PageBackgroundFit.Fill): ObfBoard {
    val previousId = backgroundImage?.imageId
    val keptImages = images.filterNot { it.id == previousId && buttons.none { button -> button.imageId == previousId } }
    return if (image == null) {
        copy(images = keptImages, extensions = extensions - OBF_BACKGROUND_IMAGE_EXTENSION)
    } else {
        copy(
            images = keptImages.filterNot { it.id == image.id } + image,
            extensions = extensions + (OBF_BACKGROUND_IMAGE_EXTENSION to
                backgroundJson.encodeToJsonElement(PageBackgroundImage.serializer(), PageBackgroundImage(image.id, fit))),
        )
    }
}

/** Ids of the images this Page uses, through its Buttons or its background. Others can be pruned. */
fun ObfBoard.usedImageIds(buttons: List<ObfButton> = this.buttons): Set<String> =
    buttons.mapNotNullTo(mutableSetOf()) { it.imageId } + listOfNotNull(backgroundImage?.imageId)
