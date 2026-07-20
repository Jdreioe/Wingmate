package io.github.jdreioe.wingmate.platform

actual class InferenceEngine {
    private var loadedPath: String? = null

    actual suspend fun loadModel(path: String): Result<Unit> {
        return try {
            loadedPath = path
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    actual suspend fun unloadModel() {
        loadedPath = null
    }

    actual suspend fun extractEmbedding(audio: ByteArray): FloatArray {
        require(loadedPath != null) { "Model not loaded" }
        return FloatArray(128) { 0f }
    }

    actual suspend fun synthesize(text: String, embedding: FloatArray): ByteArray {
        require(loadedPath != null) { "Model not loaded" }
        return ByteArray(0)
    }

    actual fun isLoaded(): Boolean = loadedPath != null
}
