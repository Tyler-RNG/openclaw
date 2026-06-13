package ai.openclaw.glasses

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavWriter {

    fun write(
        file: File,
        pcm: ByteArray,
        sampleRate: Int,
        bitDepth: Int,
        channels: Int = 1,
    ) {
        require(bitDepth == 8 || bitDepth == 16) { "unsupported bitDepth=$bitDepth" }
        require(channels in 1..2) { "unsupported channels=$channels" }

        // WAV's 8-bit PCM is unsigned (0..255); Frame ships signed 8-bit, so
        // rebias each sample by +128. 16-bit Frame audio is already signed
        // little-endian, which matches WAV — passes through unchanged.
        val data = if (bitDepth == 8) {
            ByteArray(pcm.size) { i -> ((pcm[i].toInt() + 128) and 0xFF).toByte() }
        } else {
            pcm
        }

        val byteRate = sampleRate * channels * (bitDepth / 8)
        val blockAlign = channels * (bitDepth / 8)
        val dataSize = data.size
        val chunkSize = 36 + dataSize

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(chunkSize)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)                          // PCM subchunk size
            putShort(1)                         // PCM format
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort(blockAlign.toShort())
            putShort(bitDepth.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataSize)
        }.array()

        FileOutputStream(file).use {
            it.write(header)
            it.write(data)
        }
    }
}
