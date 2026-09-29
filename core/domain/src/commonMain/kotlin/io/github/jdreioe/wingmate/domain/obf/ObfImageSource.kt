package io.github.jdreioe.wingmate.domain.obf

/** Ordered media candidates shared by import, rendering, playback, and export. */
sealed interface ObfMediaSource {
    data class Data(val value: String) : ObfMediaSource
    data class Path(val value: String) : ObfMediaSource
    data class Url(val value: String) : ObfMediaSource
    data class Symbol(val value: ObfSymbol) : ObfMediaSource
}

/** Image candidates in OBF priority order: data, dataUrl, path, url, symbol. Blank fields are skipped. */
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
