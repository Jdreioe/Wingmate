package io.github.jdreioe.wingmate.domain.chatterbox

sealed class ChatterboxError(override val message: String) : Exception(message) {
    class RecordingTooShort(durationMs: Long) :
        ChatterboxError("Recording too short (${durationMs / 1000}s). Minimum 3s required.")

    class LowQualityRecording(snr: Float) :
        ChatterboxError("Recording quality too low. Try a quieter environment.")

    class CorruptedVoiceProfile(reason: String) :
        ChatterboxError("Voice profile corrupted: $reason")

    class UnsupportedModelVersion(profileVersion: String, modelVersion: String) :
        ChatterboxError("Profile from model v$profileVersion incompatible with v$modelVersion.")

    class CloneFailed(cause: String) :
        ChatterboxError("Voice cloning failed: $cause")

    class StorageLow(required: Long, available: Long) :
        ChatterboxError("Need ${formatBytes(required)} but only ${formatBytes(available)} free.")

    class CloneInterrupted(step: String) :
        ChatterboxError("Cloning interrupted at: $step. Please try again.")

    class UnsupportedVoiceFormat(detected: String) :
        ChatterboxError("Unsupported format: $detected. Expected .voiceprofile or .rost.")

    class ModelNotFound(modelId: String) :
        ChatterboxError("Model '$modelId' not found.")

    class InferenceError(cause: String) :
        ChatterboxError("Speech engine error: $cause")

    class DownloadIntegrity(path: String) :
        ChatterboxError("Downloaded model file failed verification: $path")

    class IncompatibleGraph(model: String, detail: String) :
        ChatterboxError("Model '$model' has an incompatible ONNX interface: $detail")

    class UnsupportedLanguage(language: String) :
        ChatterboxError("Chatterbox currently supports English and Danish; '$language' is unavailable.")

    class TextTooLong(maxTokens: Int) :
        ChatterboxError("Text is too long for Chatterbox (maximum $maxTokens tokens).")

    class InvalidWaveform(reason: String) :
        ChatterboxError("Chatterbox produced invalid audio: $reason")

    class Cancelled : ChatterboxError("Chatterbox speech was cancelled.")

    companion object {
        private fun formatBytes(bytes: Long): String {
            val kb = bytes / 1024
            val mb = kb / 1024
            val gb = mb / 1024
            return when {
                gb > 0 -> "${gb}GB"
                mb > 0 -> "${mb}MB"
                kb > 0 -> "${kb}KB"
                else -> "${bytes}B"
            }
        }
    }
}
