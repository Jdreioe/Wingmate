package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.FileStorage
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class DictionaryLoaderTest {
    @Test
    fun cacheWriteFailure_doesNotFailNetworkLoad() = runBlocking {
        val storage = object : FileStorage by InMemoryFileStorage() {
            override suspend fun save(fileName: String, content: String) {
                throw RuntimeException("cache is full")
            }
        }
        val client = HttpClient(MockEngine {
            respond(
                content = "word=hello,f=215,flags=,originalFreq=215",
                status = HttpStatusCode.OK,
            )
        })
        val loader = DictionaryLoader(storage, client)

        val result = loader.loadDictionary("en-US")

        assertEquals(listOf("hello" to 215), result)
    }
}
