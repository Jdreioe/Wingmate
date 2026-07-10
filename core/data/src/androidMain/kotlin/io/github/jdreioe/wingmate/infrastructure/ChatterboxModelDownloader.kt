package io.github.jdreioe.wingmate.infrastructure

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class ChatterboxModelDownloader(private val context: Context) {

    data class ModelFile(
        val name: String,
        val url: String,
        val sizeBytes: Long,
        val isEssential: Boolean = true,
    )

    companion object {
        private const val HF_BASE = "https://huggingface.co/acul3/chatterbox-executorch/resolve/main"

        val MODELS = listOf(
            ModelFile("voice_encoder.pte", "$HF_BASE/voice_encoder.pte", 7_600_000),
            ModelFile("xvector_encoder.pte", "$HF_BASE/xvector_encoder.pte", 28_100_000),
            ModelFile("t3_cond_speech_emb.pte", "$HF_BASE/t3_cond_speech_emb.pte", 50_400_000),
            ModelFile("t3_cond_enc.pte", "$HF_BASE/t3_cond_enc.pte", 18_000_000),
            ModelFile("t3_prefill.pte", "$HF_BASE/t3_prefill.pte", 2_100_000_000, isEssential = true),
            ModelFile("t3_decode.pte", "$HF_BASE/t3_decode.pte", 2_100_000_000, isEssential = true),
            ModelFile("s3gen_encoder.pte", "$HF_BASE/s3gen_encoder.pte", 185_700_000),
            ModelFile("cfm_step.pte", "$HF_BASE/cfm_step.pte", 286_400_000),
            ModelFile("hifigan.pte", "$HF_BASE/hifigan.pte", 83_600_000),
        )

        val TOTAL_SIZE_BYTES = MODELS.sumOf { it.sizeBytes }

        fun getDefaultModelDir(context: Context): File {
            return File(context.filesDir, "chatterbox_models")
        }
    }

    val modelDir: File get() = getDefaultModelDir(context)

    fun isDownloaded(): Boolean {
        return modelDir.exists() && MODELS.all { model ->
            File(modelDir, model.name).exists()
        }
    }

    fun getMissingModels(): List<ModelFile> {
        return MODELS.filter { !File(modelDir, it.name).exists() }
    }

    fun getDownloadProgress(): Float {
        val downloaded = MODELS.sumOf {
            val f = File(modelDir, it.name)
            if (f.exists()) f.length() else 0L
        }
        return if (TOTAL_SIZE_BYTES > 0) downloaded.toFloat() / TOTAL_SIZE_BYTES else 0f
    }

    suspend fun downloadAll(onProgress: (Float) -> Unit = {}): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            modelDir.mkdirs()
            val missing = getMissingModels()
            val totalBytes = missing.sumOf { it.sizeBytes }
            var downloadedBytes = 0L

            for (model in missing) {
                val file = File(modelDir, model.name)
                val url = URL(model.url)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 30_000
                conn.readTimeout = 120_000
                conn.instanceFollowRedirects = true

                conn.inputStream.use { input ->
                    FileOutputStream(file).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            onProgress(downloadedBytes.toFloat() / totalBytes)
                        }
                    }
                }
            }
        }
    }

    fun deleteModels() {
        modelDir.deleteRecursively()
    }
}
