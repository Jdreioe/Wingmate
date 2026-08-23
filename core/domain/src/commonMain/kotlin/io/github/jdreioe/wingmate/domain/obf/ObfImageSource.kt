package io.github.jdreioe.wingmate.domain.obf

/**
 * Resolved image source following the OBF priority order:
 * **data → dataUrl → path → url → symbol**.
 */
sealed class ObfImageSource {
    data class DataUri(val data: String) : ObfImageSource()
    data class Path(val path: String) : ObfImageSource()
    data class Url(val url: String) : ObfImageSource()
    data class Symbol(val symbol: ObfSymbol) : ObfImageSource()
    data object None : ObfImageSource()
}

/** Ordered media candidates shared by import, rendering, playback, and export. */
sealed interface ObfMediaSource {
    data class Data(val value: String) : ObfMediaSource
    data class Path(val value: String) : ObfMediaSource
    data class Url(val value: String) : ObfMediaSource
    data class Symbol(val value: ObfSymbol) : ObfMediaSource
}

fun obfImageSources(image: ObfImage?): List<ObfMediaSource> {
    if (image == null) return emptyList()
    return buildList {
        image.data?.takeIf(String::isNotBlank)?.let { add(ObfMediaSource.Data(it)) }
        image.dataUrl?.takeIf(String::isNotBlank)?.let { add(ObfMediaSource.Url(it)) }
        image.path?.takeIf(String::isNotBlank)?.let { add(ObfMediaSource.Path(it)) }
        image.url?.takeIf(String::isNotBlank)?.let { add(ObfMediaSource.Url(it)) }
        image.symbol?.takeIf {
            !it.set.isNullOrBlank() || !it.filename.isNullOrBlank() || !it.libraryKey.isNullOrBlank()
        }?.let { add(ObfMediaSource.Symbol(it)) }
    }
}

fun obfSoundSources(sound: ObfSound?): List<ObfMediaSource> {
    if (sound == null) return emptyList()
    return buildList {
        sound.data?.takeIf(String::isNotBlank)?.let { add(ObfMediaSource.Data(it)) }
        sound.dataUrl?.takeIf(String::isNotBlank)?.let { add(ObfMediaSource.Url(it)) }
        sound.path?.takeIf(String::isNotBlank)?.let { add(ObfMediaSource.Path(it)) }
        sound.url?.takeIf(String::isNotBlank)?.let { add(ObfMediaSource.Url(it)) }
    }
}

fun interface ObfMediaUrlLoader {
    suspend fun load(url: String): ByteArray?
}

/**
 * Pick the highest-priority non-blank image reference on [image].
 */
fun resolveObfImageSource(image: ObfImage?): ObfImageSource {
    return when (val source = obfImageSources(image).firstOrNull()) {
        is ObfMediaSource.Data -> ObfImageSource.DataUri(source.value)
        is ObfMediaSource.Path -> ObfImageSource.Path(source.value)
        is ObfMediaSource.Url -> ObfImageSource.Url(source.value)
        is ObfMediaSource.Symbol -> ObfImageSource.Symbol(source.value)
        null -> ObfImageSource.None
    }
}
