package io.github.jdreioe.wingmate.infrastructure

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class ArasaacDownloadProgress(
    val completed: Int,
    val total: Int,
    val failed: Int = 0
)

/** Downloads the public ARASAAC catalogue into the platform's persistent image store. */
class ArasaacSymbolDownloadService(
    private val imageCacher: ImageCacher
) {
    private val client = HttpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 60_000
        }
    }
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun cachedCount(): Int = imageCacher.cachedArasaacSymbolCount()

    suspend fun downloadAll(
        locale: String,
        onProgress: (ArasaacDownloadProgress) -> Unit
    ): ArasaacDownloadProgress {
        val language = OpenSymbolsClient.normalizeLocale(locale)
        val response = client.get("https://api.arasaac.org/api/pictograms/all/$language")
        check(response.status.isSuccess()) { "ARASAAC returned ${response.status.value}" }
        val ids = json.decodeFromString<List<ArasaacPictogram>>(response.bodyAsText())
            .map { it.id }
            .distinct()
        var completed = 0
        var failed = 0
        onProgress(ArasaacDownloadProgress(completed, ids.size, failed))

        // A small fixed batch protects the service and keeps memory/network usage predictable.
        ids.chunked(4).forEach { batch ->
            val results = coroutineScope {
                batch.map { id ->
                    async {
                        try {
                            imageCacher.cacheArasaacSymbol(id)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Throwable) {
                            false
                        }
                    }
                }.awaitAll()
            }
            failed += results.count { !it }
            completed += results.size
            onProgress(ArasaacDownloadProgress(completed, ids.size, failed))
        }
        return ArasaacDownloadProgress(completed, ids.size, failed)
    }

    @Serializable
    private data class ArasaacPictogram(@SerialName("_id") val id: Long)
}
