package io.github.jdreioe.wingmate.infrastructure

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import io.github.jdreioe.wingmate.domain.PhraseRecordingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class AndroidPhraseRecordingService(
    private val context: Context
) : PhraseRecordingService {

    private var mediaRecorder: MediaRecorder? = null
    private var outputPath: String? = null

    override val isSupported: Boolean = true

    override fun isRecording(): Boolean = mediaRecorder != null

    override suspend fun startRecording(phraseIdHint: String?): Result<String> = withContext(Dispatchers.IO) {
        if (isRecording()) {
            return@withContext Result.failure(IllegalStateException("Recording already in progress."))
        }

        val permissionGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!permissionGranted) {
            return@withContext Result.failure(IllegalStateException("Microphone permission is not granted."))
        }

        runCatching {
            val root = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
            val outputDir = File(root, "wingmate/recordings").apply { mkdirs() }
            val fileName = "${sanitizeFilePart(phraseIdHint)}-${System.currentTimeMillis()}.m4a"
            val file = File(outputDir, fileName)

            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioEncodingBitRate(128_000)
            recorder.setAudioSamplingRate(44_100)
            recorder.setOutputFile(file.absolutePath)
            recorder.prepare()
            recorder.start()

            mediaRecorder = recorder
            outputPath = file.absolutePath

            file.absolutePath
        }
    }

    override suspend fun stopRecording(): Result<String> = withContext(Dispatchers.IO) {
        val recorder = mediaRecorder ?: return@withContext Result.failure(
            IllegalStateException("No active recording to stop.")
        )

        val filePath = outputPath
        mediaRecorder = null
        outputPath = null

        val stopResult = runCatching {
            recorder.stop()
            recorder.reset()
            recorder.release()
        }

        stopResult.exceptionOrNull()?.let {
            runCatching { if (!filePath.isNullOrBlank()) File(filePath).delete() }
            return@withContext Result.failure(IllegalStateException("Could not finalize recording."))
        }

        val finalPath = filePath ?: return@withContext Result.failure(
            IllegalStateException("Recording file path is unavailable.")
        )
        val file = File(finalPath)
        if (!file.exists() || file.length() <= 0L) {
            return@withContext Result.failure(IllegalStateException("Recorded audio file is empty."))
        }

        Result.success(finalPath)
    }

    private fun sanitizeFilePart(value: String?): String {
        val source = value?.trim().takeUnless { it.isNullOrBlank() } ?: "phrase"
        return source.replace(Regex("[^A-Za-z0-9_-]"), "_").take(40).ifBlank { "phrase" }
    }
}
