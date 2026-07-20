package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.FileStorage

class InMemoryFileStorage : FileStorage {
    private val text = mutableMapOf<String, String>()
    private val bytes = mutableMapOf<String, ByteArray>()

    override suspend fun save(fileName: String, content: String) {
        text[fileName] = content
    }

    override suspend fun load(fileName: String): String? = text[fileName]

    override suspend fun saveBytes(fileName: String, content: ByteArray) {
        bytes[fileName] = content.copyOf()
    }

    override suspend fun loadBytes(fileName: String): ByteArray? = bytes[fileName]?.copyOf()

    override suspend fun exists(fileName: String): Boolean =
        text.containsKey(fileName) || bytes.containsKey(fileName)
}
