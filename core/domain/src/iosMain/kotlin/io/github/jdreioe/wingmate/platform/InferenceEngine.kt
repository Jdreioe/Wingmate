package io.github.jdreioe.wingmate.platform

actual class InferenceEngine {
    actual suspend fun loadModel(path: String): Result<Unit> {
        throw UnsupportedOperationException("Chatterbox not yet supported on iOS")
    }
    actual suspend fun unloadModel() {}
    actual suspend fun extractEmbedding(audio: ByteArray): FloatArray {
        throw UnsupportedOperationException("Chatterbox not yet supported on iOS")
    }
    actual suspend fun synthesize(text: String, embedding: FloatArray): ByteArray {
        throw UnsupportedOperationException("Chatterbox not yet supported on iOS")
    }
    actual fun isLoaded(): Boolean = false
}
