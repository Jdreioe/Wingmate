package io.github.jdreioe.wingmate.platform

import android.content.Context
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

actual class AudioRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var outputPath: String? = null
    private var isRecording = false

    actual suspend fun start(outputPath: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val file = File(outputPath)
            file.parentFile?.mkdirs()

            val r = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recorder = r
            this@AudioRecorder.outputPath = outputPath
            isRecording = true
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    actual suspend fun stop(): ByteArray = withContext(Dispatchers.IO) {
        recorder?.apply {
            try {
                stop()
            } catch (_: Exception) {
            }
            release()
        }
        recorder = null
        isRecording = false
        outputPath?.let { File(it).readBytes() } ?: ByteArray(0)
    }

    actual suspend fun getDurationMs(): Long {
        recorder?.let {
            return outputPath?.let { path ->
                val file = File(path)
                if (file.exists()) file.length() / 8L else 0L
            } ?: 0L
        }
        return 0L
    }

    actual fun isRecording(): Boolean = isRecording
}