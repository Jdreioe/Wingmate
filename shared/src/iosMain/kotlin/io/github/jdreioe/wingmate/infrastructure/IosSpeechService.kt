package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.ConfigRepository
import io.github.jdreioe.wingmate.domain.SaidText
import io.github.jdreioe.wingmate.domain.SaidTextRepository
import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.domain.Voice
import io.github.jdreioe.wingmate.application.VoiceUseCase
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import kotlinx.cinterop.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioSession
import platform.Foundation.*

import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.json.*

private val logger = KotlinLogging.logger {}

@OptIn(ExperimentalForeignApi::class)
class IosSpeechService(
    private val httpClient: HttpClient,
    private val configRepository: ConfigRepository,
    private val voiceUseCase: VoiceUseCase? = null,
    private val saidRepo: SaidTextRepository? = null,
) : SpeechService {

    private var audioPlayer: AVAudioPlayer? = null

    // Cache/History TTL: 30 days
    private val CACHE_TTL_MS: Long = 30L * 24 * 60 * 60 * 1000

    init {
        configureAudioSession()
        // Opportunistic cleanup on initialization
        runCatching {
            // Best-effort cleanup without blocking init; actual pruning happens during speak as well
            kotlinx.coroutines.GlobalScope.launch(Dispatchers.Default) { cleanupExpiredFiles() }
        }
    }

    private fun configureAudioSession() {
        try {
            val session = AVAudioSession.sharedInstance()
            // Newer AVFAudio bindings prefer NSErrorPointer parameters
            memScoped {
                val err = alloc<ObjCObjectVar<NSError?>>()
                // Pass category by name to avoid constant import differences across SDKs
                session.setCategory("AVAudioSessionCategoryPlayback", error = err.ptr)
                // Some AVFAudio bindings don't expose setActive; skip if unavailable
                // iOS often auto-activates on playback; if needed, this can be handled in Swift side
                if (err.value != null) {
                    logger.error { "AVAudioSession error: ${err.value?.localizedDescription}" }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to configure AVAudioSession" }
        }
    }

    override suspend fun speak(text: String, voice: Voice?, pitch: Double?, rate: Double?) {
    // Periodic cleanup prior to speaking
    cleanupExpiredFiles()
        if (text.isBlank()) return

        // Resolve an effective voice for language matching in history
        val selected = runCatching { voice ?: voiceUseCase?.selected() }.getOrNull()
        val effectiveVoice = selected ?: Voice(
            id = null,
            name = "en-US-JennyNeural",
            displayName = "Default Voice",
            primaryLanguage = "en-US",
            selectedLanguage = "en-US"
        )

        // 1) Try history-first playback with TTL
        val historyPlayed = tryPlayFromHistoryIfFresh(text = text, voice = effectiveVoice)
        if (historyPlayed) return

        // 2) Try cache by key (voice+lang+pitch+rate+text hash) with TTL
        val cacheKey = buildCacheKey(text, effectiveVoice, pitch, rate)
        val cachedPath = findCachedPath(cacheKey)
        if (cachedPath != null) {
            logger.info { "Playing from cache: $cachedPath" }
            return playFile(cachedPath)
        }

        val audioBytes = withContext(Dispatchers.Default) {
            try {
                val config = configRepository.getSpeechConfig()
                if (config == null) {
                    logger.warn { "No speech config found, cannot speak" }
                    return@withContext null
                }

                val voiceToUse = effectiveVoice
                val ssml = AzureTtsClient.generateSsml(text, voiceToUse)
        val audioData = AzureTtsClient.synthesize(httpClient, ssml, config)
                logger.info { "Generated ${audioData.size} bytes of audio for text: '${text.take(40)}'" }
                audioData
            } catch (e: Exception) {
                logger.error(e) { "Failed to synthesize speech for text: '${text.take(40)}'" }
                null
            }
        }

        audioBytes ?: return

    // Save to cache and history
    val filePath = saveToCache(cacheKey, audioBytes)
    trySaveHistory(text, effectiveVoice, pitch, rate, filePath)

    playAudio(audioBytes)
    }

    private var lastCleanupAtMs: Long = 0L
    private suspend fun cleanupExpiredFiles() {
        val now = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
        if (now - lastCleanupAtMs < 24L * 60 * 60 * 1000) return
        withContext(Dispatchers.Default) {
            try {
                // 1) Prune cache directory files by mtime
                val dir = iosCacheDir()
                val fm = NSFileManager.defaultManager
                val contents = fm.contentsOfDirectoryAtPath(dir, error = null) as? List<*>
                contents?.forEach { nameAny ->
                    val name = nameAny as? String ?: return@forEach
                    val path = "$dir/$name"
                    val attrs = fm.attributesOfItemAtPath(path, error = null)
                    val mtime = attrs?.objectForKey(NSFileModificationDate) as? NSDate
                    val baseTime = mtime?.timeIntervalSince1970?.times(1000.0)?.toLong() ?: 0L
                    val age = now - baseTime
                    if (age > CACHE_TTL_MS) runCatching { fm.removeItemAtPath(path, error = null) }
                }

                // 2) Prune expired history audio file paths
                runCatching {
                    val items = saidRepo?.list().orEmpty()
                    items.forEach { item ->
                        val path = item.audioFilePath ?: return@forEach
                        val attrs = fm.attributesOfItemAtPath(path, error = null)
                        val mtime = attrs?.objectForKey(NSFileModificationDate) as? NSDate
                        val base = (item.createdAt ?: item.date) ?: mtime?.timeIntervalSince1970?.times(1000.0)?.toLong()
                        val age = if (base != null) now - base else Long.MAX_VALUE
                        if (age > CACHE_TTL_MS) runCatching { fm.removeItemAtPath(path, error = null) }
                    }
                }
            } finally { lastCleanupAtMs = now }
        }
    }

    private suspend fun tryPlayFromHistoryIfFresh(text: String, voice: Voice): Boolean {
        val repo = saidRepo ?: return false
        return try {
            val items = withContext(Dispatchers.Default) { repo.list() }
            val now = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
            // Prefer most recent match by text and (if present) language
            val match = items
                .asSequence()
                .filter { it.saidText?.trim().equals(text.trim(), ignoreCase = false) }
                .sortedByDescending { it.createdAt ?: it.date ?: 0L }
                .firstOrNull { item ->
                    val langOk = when {
                        item.primaryLanguage.isNullOrBlank() -> true
                        !voice.selectedLanguage.isNullOrBlank() -> item.primaryLanguage == voice.selectedLanguage
                        !voice.primaryLanguage.isNullOrBlank() -> item.primaryLanguage == voice.primaryLanguage
                        else -> true
                    }
                    if (!langOk) return@firstOrNull false
                    val path = item.audioFilePath ?: return@firstOrNull false
                    val fresh = isFileFresh(path, now, item.createdAt ?: item.date)
                    fresh
                }
            if (match != null) {
                playFile(match.audioFilePath!!)
                true
            } else false
        } catch (t: Throwable) {
            logger.warn(t) { "History lookup failed" }
            false
        }
    }

    private suspend fun playFile(path: String) = withContext(Dispatchers.Main) {
        stop()
        val url = NSURL.fileURLWithPath(path)
        memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            audioPlayer = AVAudioPlayer(contentsOfURL = url, error = error.ptr)
            if (error.value != null) {
                logger.error { "Failed to create audio player from file: ${error.value?.localizedDescription}" }
                return@memScoped
            }
            audioPlayer?.play()
        }
    }

    private suspend fun playAudio(audioBytes: ByteArray) = withContext(Dispatchers.Main) {
        stop()

        val nsData = audioBytes.usePinned {
            NSData.dataWithBytes(it.addressOf(0), it.get().size.toULong())
        }

        memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            audioPlayer = AVAudioPlayer(data = nsData, error = error.ptr)

            if (error.value != null) {
                logger.error { "Failed to create audio player: ${error.value?.localizedDescription}" }
                return@memScoped
            }

            audioPlayer?.play()
        }
    }

    override suspend fun pause() = withContext(Dispatchers.Main) {
        logger.info { "Pause speech on iOS" }
        if (audioPlayer?.isPlaying() == true) {
            audioPlayer?.pause()
        }
    }

    override suspend fun stop() = withContext(Dispatchers.Main) {
        logger.info { "Stop speech on iOS" }
        if (audioPlayer?.isPlaying() == true) {
            audioPlayer?.stop()
        }
        audioPlayer = null
    }

    override suspend fun resume() {
        // No-op for now
    }

    override fun isPlaying(): Boolean = audioPlayer?.isPlaying() == true

    override fun isPaused(): Boolean = false

    override suspend fun guessPronunciation(text: String, language: String): String? {
        val langCode = language.take(2).lowercase()
        return try {
            // Use Wiktionary API as a robust source for IPA
            val url = "https://en.wiktionary.org/w/api.php?action=query&titles=${text.trim()}&prop=revisions&rvprop=content&format=json"
            val response = httpClient.get(url)
            
            if (response.status.value == 200) {
                val body = response.bodyAsText()
                val json = Json { ignoreUnknownKeys = true }
                val root = json.parseToJsonElement(body).jsonObject
                
                val pages = root["query"]?.jsonObject?.get("pages")?.jsonObject
                if (pages == null || pages.isEmpty()) return null
                
                val pageKey = pages.keys.first()
                if (pageKey == "-1") return null
                
                val page = pages[pageKey]?.jsonObject
                val revisions = page?.get("revisions")?.jsonArray
                val content = revisions?.get(0)?.jsonObject?.get("*")?.jsonPrimitive?.content
                
                if (content != null) {
                    val regex = Regex("\\{\\{IPA\\|$langCode\\|/([^/]+)/")
                    val match = regex.find(content)
                    if (match != null) return match.groupValues[1]
                    
                    val regexBrackets = Regex("\\{\\{IPA\\|$langCode\\|\\[([^\\]]+)\\]")
                    val matchBrackets = regexBrackets.find(content)
                    if (matchBrackets != null) return matchBrackets.groupValues[1]
                }
            }
            null
        } catch (e: Exception) {
            logger.warn(e) { "Failed to guess pronunciation for '$text'" }
            null
        }
    }

    // --- Caching helpers ---
    private fun buildCacheKey(text: String, voice: Voice?, pitch: Double?, rate: Double?): String {
        val v = voice?.name ?: voice?.displayName ?: "default"
        val lang = voice?.selectedLanguage ?: voice?.primaryLanguage ?: ""
        val p = pitch?.toString() ?: voice?.pitch?.toString() ?: ""
        val r = rate?.toString() ?: voice?.rate?.toString() ?: ""
        val raw = listOf(v, lang, p, r, text).joinToString("|")
        return raw.sha1()
    }

    private fun findCachedPath(key: String): String? = try {
        val dir = iosCacheDir()
        val path = "$dir/$key.m4a"
        if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return null
        val now = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
        return if (isFileFresh(path, now, null)) path else null
    } catch (_: Throwable) { null }

    private fun saveToCache(key: String, bytes: ByteArray): String? = try {
    val dir = iosCacheDir()
    NSFileManager.defaultManager.createDirectoryAtPath(dir, true, null, null)
        val path = "$dir/$key.m4a"
    val data = bytes.usePinned { NSData.dataWithBytes(it.addressOf(0), it.get().size.toULong()) }
    NSFileManager.defaultManager.createFileAtPath(path, contents = data, attributes = null)
        path
    } catch (t: Throwable) {
        logger.warn(t) { "Failed to write cache file" }
        null
    }

    private fun isFileFresh(path: String, nowMs: Long, createdAtMsOrNull: Long?): Boolean {
        // Prefer provided createdAt; otherwise check file modification date
        val baseTime = createdAtMsOrNull ?: runCatching {
            val attrs = NSFileManager.defaultManager.attributesOfItemAtPath(path, error = null)
            val mtime = attrs?.objectForKey(NSFileModificationDate) as? NSDate
            if (mtime != null) (mtime.timeIntervalSince1970 * 1000.0).toLong() else null
        }.getOrNull()
        val age = if (baseTime != null) nowMs - baseTime else Long.MAX_VALUE
        val fresh = age <= CACHE_TTL_MS
        if (!fresh) runCatching { NSFileManager.defaultManager.removeItemAtPath(path, error = null) }
        return fresh
    }

    private suspend fun trySaveHistory(text: String, voice: Voice?, pitch: Double?, rate: Double?, filePath: String?) {
        val repo = saidRepo ?: return
        try {
            val now = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
            val item = SaidText(
                date = now,
                saidText = text,
                voiceName = voice?.name ?: voice?.displayName,
                pitch = pitch ?: voice?.pitch,
                speed = rate ?: voice?.rate,
                audioFilePath = filePath,
                createdAt = now,
                primaryLanguage = voice?.selectedLanguage ?: voice?.primaryLanguage
            )
            withContext(Dispatchers.Default) { repo.add(item) }
        } catch (t: Throwable) {
            logger.warn(t) { "Failed to save said history" }
        }
    }

    private fun iosCacheDir(): String {
        val home = NSHomeDirectory()
        val base = "$home/Library/Caches"
        return "$base/tts-cache"
    }

    private fun String.sha1(): String {
        // Simple Kotlin implementation to avoid extra deps; acceptable for cache keys
        val bytes = this.encodeToByteArray()
        var h0 = 0x67452301
        var h1 = 0xEFCDAB89.toInt()
        var h2 = 0x98BADCFE.toInt()
        var h3 = 0x10325476
        var h4 = 0xC3D2E1F0.toInt()
        val ml = bytes.size * 8L
        val withOne = bytes + byteArrayOf(0x80.toByte())
        val padLen = ((56 - (withOne.size % 64) + 64) % 64)
        val padded = withOne + ByteArray(padLen) + ByteArray(8).apply {
            for (i in 0 until 8) this[7 - i] = ((ml ushr (8 * i)) and 0xFF).toByte()
        }
        fun rotl(x: Int, n: Int) = (x shl n) or (x ushr (32 - n))
        for (chunk in 0 until padded.size step 64) {
            val w = IntArray(80)
            for (i in 0 until 16) {
                val j = chunk + i * 4
                w[i] = ((padded[j].toInt() and 0xFF) shl 24) or
                        ((padded[j + 1].toInt() and 0xFF) shl 16) or
                        ((padded[j + 2].toInt() and 0xFF) shl 8) or
                        (padded[j + 3].toInt() and 0xFF)
            }
            for (i in 16 until 80) w[i] = rotl(w[i - 3] xor w[i - 8] xor w[i - 14] xor w[i - 16], 1)
            var a = h0; var b = h1; var c = h2; var d = h3; var e = h4
            for (i in 0 until 80) {
                val f: Int; val k: Int = when (i) {
                    in 0..19 -> { f = (b and c) or ((b.inv()) and d); 0x5A827999 }
                    in 20..39 -> { f = b xor c xor d; 0x6ED9EBA1 }
                    in 40..59 -> { f = (b and c) or (b and d) or (c and d); 0x8F1BBCDC.toInt() }
                    else -> { f = b xor c xor d; 0xCA62C1D6.toInt() }
                }
                val temp = (rotl(a, 5) + f + e + k + w[i])
                e = d
                d = c
                c = rotl(b, 30)
                b = a
                a = temp
            }
            h0 += a; h1 += b; h2 += c; h3 += d; h4 += e
        }
        return buildString(40) {
            for (h in intArrayOf(h0, h1, h2, h3, h4)) append(h.toUInt().toString(16).padStart(8, '0'))
        }
    }
}
