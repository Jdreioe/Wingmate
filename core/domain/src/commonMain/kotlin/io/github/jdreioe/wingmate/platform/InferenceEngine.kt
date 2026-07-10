package io.github.jdreioe.wingmate.platform

expect class InferenceEngine {
    suspend fun loadModel(path: String): Result<Unit>
    suspend fun unloadModel()
    suspend fun extractEmbedding(audio: ByteArray): FloatArray
    suspend fun synthesize(text: String, embedding: FloatArray): ByteArray
    fun isLoaded(): Boolean
}