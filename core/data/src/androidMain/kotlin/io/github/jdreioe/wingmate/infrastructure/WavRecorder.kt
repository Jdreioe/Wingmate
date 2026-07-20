package io.github.jdreioe.wingmate.infrastructure

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class WavRecorder(
    private val sampleRate: Int = 24000,
) {
    private var recorder: AudioRecord? = null
    private var isRecording = false
    private var outputPath: String? = null

    fun start(outputPath: String): Result<Unit> = runCatching {
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(4096)

        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            throw IllegalStateException("AudioRecord failed to initialize")
        }

        val file = File(outputPath)
        file.parentFile?.mkdirs()

        record.startRecording()

        this.recorder = record
        this.outputPath = outputPath
        this.isRecording = true

        val headerFile = File(outputPath + ".tmp")
        headerFile.parentFile?.mkdirs()

        val headerBytes = writeWavHeader(0, sampleRate, 16, 1)
        FileOutputStream(headerFile).use { it.write(headerBytes) }

        Thread {
            val buffer = ShortArray(bufferSize / 2)
            val pcmFile = File(outputPath + ".pcm")
            pcmFile.parentFile?.mkdirs()
            val pcmOut = FileOutputStream(pcmFile)

            try {
                while (isRecording) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val byteBuf = ByteArray(read * 2)
                        for (i in 0 until read) {
                            byteBuf[i * 2] = (buffer[i].toInt() and 0xFF).toByte()
                            byteBuf[i * 2 + 1] = (buffer[i].toInt() shr 8).toByte()
                        }
                        pcmOut.write(byteBuf)
                    }
                }
            } catch (_: Exception) {
            } finally {
                pcmOut.close()
            }
        }.start()
    }

    suspend fun stop(): File? = withContext(Dispatchers.IO) {
        isRecording = false
        recorder?.let {
            try {
                it.stop()
            } catch (_: Exception) {}
            it.release()
        }
        recorder = null

        val pcmFile = File(outputPath + ".pcm")
        val outFile = outputPath?.let { File(it) } ?: return@withContext null

        if (!pcmFile.exists() || pcmFile.length() == 0L) {
            pcmFile.delete()
            return@withContext null
        }

        val pcmData = pcmFile.readBytes()
        val totalDataLen = pcmData.size
        val header = writeWavHeader(totalDataLen, sampleRate, 16, 1)

        FileOutputStream(outFile).use { out ->
            out.write(header)
            out.write(pcmData)
        }

        pcmFile.delete()
        File(outputPath + ".tmp").delete()

        outFile
    }

    fun isRecording(): Boolean = isRecording

    private fun writeWavHeader(dataLen: Int, sampleRate: Int, bitsPerSample: Int, channels: Int): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val header = ByteArray(44)

        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()

        val totalLen = 36 + dataLen
        header[4] = (totalLen and 0xFF).toByte()
        header[5] = (totalLen shr 8 and 0xFF).toByte()
        header[6] = (totalLen shr 16 and 0xFF).toByte()
        header[7] = (totalLen shr 24 and 0xFF).toByte()

        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()

        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()

        header[16] = 16
        header[17] = 0
        header[18] = 0
        header[19] = 0

        header[20] = 1
        header[21] = 0

        header[22] = channels.toByte()
        header[23] = 0

        header[24] = (sampleRate and 0xFF).toByte()
        header[25] = (sampleRate shr 8 and 0xFF).toByte()
        header[26] = (sampleRate shr 16 and 0xFF).toByte()
        header[27] = (sampleRate shr 24 and 0xFF).toByte()

        header[28] = (byteRate and 0xFF).toByte()
        header[29] = (byteRate shr 8 and 0xFF).toByte()
        header[30] = (byteRate shr 16 and 0xFF).toByte()
        header[31] = (byteRate shr 24 and 0xFF).toByte()

        header[32] = (blockAlign and 0xFF).toByte()
        header[33] = 0

        header[34] = bitsPerSample.toByte()
        header[35] = 0

        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()

        header[40] = (dataLen and 0xFF).toByte()
        header[41] = (dataLen shr 8 and 0xFF).toByte()
        header[42] = (dataLen shr 16 and 0xFF).toByte()
        header[43] = (dataLen shr 24 and 0xFF).toByte()

        return header
    }
}
