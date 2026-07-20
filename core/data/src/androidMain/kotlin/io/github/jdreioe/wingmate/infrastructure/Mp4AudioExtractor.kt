package io.github.jdreioe.wingmate.infrastructure

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

class Mp4AudioExtractor : io.github.jdreioe.wingmate.domain.chatterbox.AudioExtractor {

    companion object {
        private const val TAG = "Mp4AudioExtractor"
        private const val TIMEOUT_US = 10_000L
    }

    override suspend fun extractToWav(inputPath: String, outputPath: String): Result<String> =
        extractAudioToWav(inputPath, outputPath).map { it.absolutePath }

    fun extractAudioToWav(mp4Path: String, outputPath: String, targetSampleRate: Int = 24000): Result<File> = runCatching {
        val extractor = MediaExtractor()
        extractor.setDataSource(mp4Path)

        var audioTrackIndex = -1
        var inputFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) {
                audioTrackIndex = i
                inputFormat = fmt
                break
            }
        }

        if (audioTrackIndex < 0 || inputFormat == null) {
            throw IllegalStateException("No audio track found in MP4 file")
        }

        extractor.selectTrack(audioTrackIndex)
        val inputMime = inputFormat.getString(MediaFormat.KEY_MIME)!!
        val inputSampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val inputChannels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        Log.i(TAG, "Extracting audio: mime=$inputMime, rate=$inputSampleRate, ch=$inputChannels")

        val decoder = MediaCodec.createDecoderByType(inputMime)
        decoder.configure(inputFormat, null, null, 0)
        decoder.start()

        val pcmSamples = mutableListOf<Byte>()
        val bufferInfo = MediaCodec.BufferInfo()

        while (true) {
            val inputBufIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
            if (inputBufIndex >= 0) {
                val inputBuf = decoder.getInputBuffer(inputBufIndex)!!
                val sampleSize = extractor.readSampleData(inputBuf, 0)
                if (sampleSize < 0) {
                    decoder.queueInputBuffer(inputBufIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                } else {
                    decoder.queueInputBuffer(inputBufIndex, 0, sampleSize, extractor.sampleTime, 0)
                    extractor.advance()
                }
            }

            val outputBufIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            when {
                outputBufIndex >= 0 -> {
                    val outputBuf = decoder.getOutputBuffer(outputBufIndex)!!
                    if (bufferInfo.size > 0) {
                        val chunk = ByteArray(bufferInfo.size)
                        outputBuf.get(chunk)
                        chunk.forEach { pcmSamples.add(it) }
                    }
                    decoder.releaseOutputBuffer(outputBufIndex, false)

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        break
                    }
                }
                outputBufIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val newFormat = decoder.outputFormat
                    Log.i(TAG, "Output format changed: $newFormat")
                }
            }
        }

        decoder.stop()
        decoder.release()
        extractor.release()

        val pcmBytes = pcmSamples.toByteArray()
        val numChannels = if (inputChannels > 0) inputChannels else 1
        val bitsPerSample = 16

        val resampled = if (inputSampleRate != targetSampleRate) {
            resamplePcm(pcmBytes, inputSampleRate, targetSampleRate, numChannels)
        } else {
            pcmBytes
        }

        val monoBytes = if (numChannels > 1) {
            convertToMono(resampled, numChannels)
        } else {
            resampled
        }

        val wavHeader = createWavHeader(monoBytes.size, targetSampleRate, bitsPerSample, 1)
        val outFile = File(outputPath)
        outFile.parentFile?.mkdirs()
        FileOutputStream(outFile).use { out ->
            out.write(wavHeader)
            out.write(monoBytes)
        }

        Log.i(TAG, "WAV extracted: ${monoBytes.size} bytes, ${monoBytes.size * 8 / (targetSampleRate * 16)} seconds")
        outFile
    }

    private fun resamplePcm(input: ByteArray, inRate: Int, outRate: Int, channels: Int): ByteArray {
        if (inRate == outRate) return input
        val ratio = outRate.toDouble() / inRate
        val inSamples = input.size / 2
        val outSamples = (inSamples * ratio).toInt()
        val output = ByteArray(outSamples * 2)

        for (i in 0 until outSamples) {
            val srcPos = i.toDouble() / ratio
            val srcIdx = srcPos.toInt()
            val frac = srcPos - srcIdx
            val a = readShort(input, srcIdx * 2)
            val b = if ((srcIdx + 1) * 2 + 1 < input.size) readShort(input, (srcIdx + 1) * 2) else a
            val interpolated = (a * (1f - frac.toFloat()) + b * frac.toFloat()).toInt()
            writeShort(output, i * 2, interpolated)
        }
        return output
    }

    private fun convertToMono(input: ByteArray, channels: Int): ByteArray {
        val frames = input.size / (2 * channels)
        val output = ByteArray(frames * 2)
        for (i in 0 until frames) {
            var sum = 0
            for (ch in 0 until channels) {
                sum += readShort(input, (i * channels + ch) * 2)
            }
            writeShort(output, i * 2, sum / channels)
        }
        return output
    }

    private fun readShort(buf: ByteArray, offset: Int): Int {
        if (offset + 1 >= buf.size) return 0
        val low = buf[offset].toInt() and 0xFF
        val high = buf[offset + 1].toInt() shl 8
        return (high or low).toShort().toInt()
    }

    private fun writeShort(buf: ByteArray, offset: Int, value: Int) {
        if (offset + 1 >= buf.size) return
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = (value shr 8 and 0xFF).toByte()
    }

    private fun createWavHeader(dataLen: Int, sampleRate: Int, bitsPerSample: Int, channels: Int): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val header = ByteArray(44)

        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        val totalLen = 36 + dataLen
        header[4] = (totalLen and 0xFF).toByte()
        header[5] = (totalLen shr 8 and 0xFF).toByte()
        header[6] = (totalLen shr 16 and 0xFF).toByte()
        header[7] = (totalLen shr 24 and 0xFF).toByte()
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0
        header[20] = 1; header[21] = 0
        header[22] = channels.toByte(); header[23] = 0
        header[24] = (sampleRate and 0xFF).toByte()
        header[25] = (sampleRate shr 8 and 0xFF).toByte()
        header[26] = (sampleRate shr 16 and 0xFF).toByte()
        header[27] = (sampleRate shr 24 and 0xFF).toByte()
        header[28] = (byteRate and 0xFF).toByte()
        header[29] = (byteRate shr 8 and 0xFF).toByte()
        header[30] = (byteRate shr 16 and 0xFF).toByte()
        header[31] = (byteRate shr 24 and 0xFF).toByte()
        header[32] = (blockAlign and 0xFF).toByte(); header[33] = 0
        header[34] = bitsPerSample.toByte(); header[35] = 0
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        header[40] = (dataLen and 0xFF).toByte()
        header[41] = (dataLen shr 8 and 0xFF).toByte()
        header[42] = (dataLen shr 16 and 0xFF).toByte()
        header[43] = (dataLen shr 24 and 0xFF).toByte()
        return header
    }
}
