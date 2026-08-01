package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.obf.ObfMediaUrlLoader
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get

class KtorObfMediaUrlLoader(private val client: HttpClient) : ObfMediaUrlLoader {
    override suspend fun load(url: String): ByteArray? = runCatching {
        client.get(url).body<ByteArray>()
    }.getOrNull()
}
