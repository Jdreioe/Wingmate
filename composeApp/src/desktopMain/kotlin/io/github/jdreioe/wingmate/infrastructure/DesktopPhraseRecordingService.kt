package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.PhraseRecordingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine

class DesktopPhraseRecordingService : PhraseRecordingService {

    private val format = AudioFormat(44_100f, 16, 1, true, false)

    @Volatile
    private var line: TargetDataLine? = null

    @Volatile
    private var outputPath: Path? = null

    @Volatile
    private var recordingThread: Thread? = null

    @Volatile
    private var recordingError: Throwable? = null

    override val isSupported: Boolean = true

    override fun isRecording(): Boolean = line != null

    override suspend fun startRecording(phraseIdHint: String?): Result<String> = withContext(Dispatchers.IO) {
        if (isRecording()) {
            return@withContext Result.failure(IllegalStateException("Recording already in progress."))
        }

        runCatching {
            val directory = Paths.get(System.getProperty("user.home"), ".wingmate", "recordings")
            Files.createDirectories(directory)

            val fileName = "${sanitizeFilePart(phraseIdHint)}-${System.currentTimeMillis()}.wav"
            val target = directory.resolve(fileName)

            val info = DataLine.Info(TargetDataLine::class.java, format)
            val targetLine = AudioSystem.getLine(info) as TargetDataLine
            targetLine.open(format)

            recordingError = null
            outputPath = target
            line = targetLine

            val writer = Thread(
                {
                    runCatching {
                        AudioSystem.write(
                            AudioInputStream(targetLine),
                            AudioFileFormat.Type.WAVE,
                            target.toFile()
                        )
                    }.onFailure {
                        recordingError = it
                    }
                },
                "wingmate-phrase-recorder"
            )
            writer.isDaemon = true
            recordingThread = writer

            targetLine.start()
            writer.start()

            target.toString()
        }
    }

    override suspend fun stopRecording(): Result<String> = withContext(Dispatchers.IO) {
        val currentLine = line ?: return@withContext Result.failure(
            IllegalStateException("No active recording to stop.")
        )

        val currentOutput = outputPath
        val currentThread = recordingThread

        val result = runCatching {
            runCatching { currentLine.stop() }
            runCatching { currentLine.close() }
            runCatching { currentThread?.join(3000) }

            val error = recordingError
            if (error != null) {
                throw IllegalStateException("Recording failed.", error)
            }

            val finishedPath = currentOutput ?: throw IllegalStateException("Recording path is unavailable.")
            if (!Files.exists(finishedPath) || Files.size(finishedPath) <= 0L) {
                throw IllegalStateException("Recorded audio file is empty.")
            }

            finishedPath.toString()
        }

        line = null
        outputPath = null
        recordingThread = null
        recordingError = null

        result
    }

    private fun sanitizeFilePart(value: String?): String {
        val source = value?.trim().takeUnless { it.isNullOrBlank() } ?: "phrase"
        return source.replace(Regex("[^A-Za-z0-9_-]"), "_").take(40).ifBlank { "phrase" }
    }
}
