package ai.openclaw.app.voice

import ai.openclaw.app.diag.PhoneDiagLog
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Records 16 kHz mono 16-bit PCM to a WAV file for upload to the gateway's
 * `/stream/stt` route. Wraps [AudioRecord] on a dedicated read thread so the
 * main / coroutine thread isn't blocked; finalises a valid 44-byte WAV header
 * on stop so the file is playable / parseable by the upstream provider.
 *
 * Used by [MicCaptureManager] press-and-hold mode when the gateway has
 * `streamStt.enabled=true`. The alternative path (Android `SpeechRecognizer`)
 * is still there when the gateway has no STT plugin wired.
 */
internal class MicPcmRecorder(
    private val context: Context,
    /**
     * Callback receiving a 0..1-normalised RMS every audio-buffer read,
     * so the Voice-tab UI ring can pulse the same way it does for the
     * SpeechRecognizer path. Safe to ignore.
     */
    private val onInputLevel: (Float) -> Unit = {},
) {
    private var audioRecord: AudioRecord? = null
    private var writerThread: Thread? = null
    private var outFile: File? = null
    private val running = AtomicBoolean(false)
    private var pcmBytesWritten = 0L
    private var startedAtMs = 0L

    /**
     * Begin recording to a new temp file under [Context.getCacheDir]. Returns
     * false when the RECORD_AUDIO permission is missing or [AudioRecord]
     * failed to initialise; callers should abort the hold UX in that case.
     */
    @SuppressLint("MissingPermission") // checked below
    fun start(): Boolean {
        if (running.get()) {
            PhoneDiagLog.warn("stt", "recorder start called while already running")
            return true
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            PhoneDiagLog.warn("stt", "recorder missing RECORD_AUDIO permission")
            return false
        }

        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(4096) * 2

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
        } catch (e: Throwable) {
            PhoneDiagLog.error("stt", "AudioRecord ctor failed: ${e.javaClass.simpleName}")
            return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            PhoneDiagLog.error("stt", "AudioRecord state=${record.state} after ctor")
            record.release()
            return false
        }

        val file = File.createTempFile("stt-hold-", ".wav", context.cacheDir)
        // Reserve 44 bytes for the header; we patch it in on stop() once we
        // know the total PCM size.
        FileOutputStream(file).use { it.write(ByteArray(WAV_HEADER_BYTES)) }

        audioRecord = record
        outFile = file
        pcmBytesWritten = 0L
        startedAtMs = System.currentTimeMillis()
        running.set(true)

        record.startRecording()
        writerThread = thread(name = "stt-pcm-writer", isDaemon = true) {
            writeLoop(record, file, bufferSize)
        }
        PhoneDiagLog.info("stt", "recorder started file=${file.name} buf=${bufferSize}B")
        return true
    }

    /**
     * Stop recording, patch the WAV header with final sizes, and return the
     * finished file. Returns null when the recorder wasn't running or no
     * audio was captured.
     */
    fun stop(): File? {
        if (!running.compareAndSet(true, false)) {
            PhoneDiagLog.warn("stt", "recorder stop called while idle")
            return null
        }
        val record = audioRecord
        val file = outFile
        audioRecord = null
        outFile = null

        try {
            record?.stop()
        } catch (e: Throwable) {
            PhoneDiagLog.warn("stt", "AudioRecord.stop threw: ${e.javaClass.simpleName}")
        }
        try {
            writerThread?.join(500)
        } catch (_: InterruptedException) {
            // Fall through; writer loop sees running=false and exits.
        }
        writerThread = null
        try {
            record?.release()
        } catch (_: Throwable) {
            // Best-effort cleanup.
        }

        if (file == null) {
            PhoneDiagLog.warn("stt", "recorder stop without file")
            return null
        }
        if (pcmBytesWritten <= 0L) {
            PhoneDiagLog.warn("stt", "recorder stop with 0 bytes — discarding")
            file.delete()
            return null
        }
        try {
            patchWavHeader(file, pcmBytesWritten)
        } catch (e: Throwable) {
            PhoneDiagLog.error("stt", "patchWavHeader threw: ${e.javaClass.simpleName}")
            file.delete()
            return null
        }
        val durMs = System.currentTimeMillis() - startedAtMs
        PhoneDiagLog.info(
            "stt",
            "recorder stopped bytes=${file.length()} pcm=$pcmBytesWritten dur=${durMs}ms",
        )
        return file
    }

    /**
     * Abandon the in-flight recording without producing a file. Used when
     * the hold ended in an error path (permission revoked mid-hold, etc.).
     */
    fun cancel() {
        if (!running.compareAndSet(true, false)) return
        val record = audioRecord
        val file = outFile
        audioRecord = null
        outFile = null
        try {
            record?.stop()
        } catch (_: Throwable) {
            // Ignored; best-effort.
        }
        try {
            record?.release()
        } catch (_: Throwable) {
            // Ignored.
        }
        writerThread = null
        file?.delete()
        PhoneDiagLog.info("stt", "recorder cancelled")
    }

    private fun writeLoop(record: AudioRecord, file: File, bufferSize: Int) {
        val buf = ByteArray(bufferSize)
        try {
            FileOutputStream(file, true).use { out ->
                while (running.get()) {
                    val read = record.read(buf, 0, buf.size)
                    if (read > 0) {
                        out.write(buf, 0, read)
                        pcmBytesWritten += read.toLong()
                        onInputLevel(computeRmsLevel(buf, read))
                    } else if (read < 0) {
                        PhoneDiagLog.warn("stt", "AudioRecord.read error=$read; ending")
                        break
                    }
                }
                out.flush()
            }
        } catch (e: Throwable) {
            PhoneDiagLog.error("stt", "writeLoop threw: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Returns a 0..1-ish loudness estimate from a 16-bit PCM buffer. Matches
     * the perceptual range the SpeechRecognizer path's RMS-dB input produces,
     * so the UI ring animates consistently across both paths.
     */
    private fun computeRmsLevel(buf: ByteArray, readBytes: Int): Float {
        var sumSq = 0.0
        var samples = 0
        var i = 0
        while (i + 1 < readBytes) {
            // Little-endian 16-bit signed.
            val lo = buf[i].toInt() and 0xFF
            val hi = buf[i + 1].toInt()
            val sample = (hi shl 8) or lo
            sumSq += (sample * sample).toDouble()
            samples++
            i += 2
        }
        if (samples == 0) return 0f
        val rms = kotlin.math.sqrt(sumSq / samples)
        // 32767 = full-scale; log-compress so the UI reflects perceived loudness.
        val norm = (rms / 32767.0).coerceIn(0.0, 1.0)
        return (kotlin.math.sqrt(norm)).toFloat()
    }

    private fun patchWavHeader(file: File, pcmBytes: Long) {
        val totalSize = pcmBytes + WAV_HEADER_BYTES - 8
        val byteRate = SAMPLE_RATE_HZ * CHANNELS * BITS_PER_SAMPLE / 8
        val blockAlign = CHANNELS * BITS_PER_SAMPLE / 8
        val header = ByteBuffer.allocate(WAV_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(totalSize.toInt())
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16) // PCM fmt chunk size
            putShort(1) // PCM format tag
            putShort(CHANNELS.toShort())
            putInt(SAMPLE_RATE_HZ)
            putInt(byteRate)
            putShort(blockAlign.toShort())
            putShort(BITS_PER_SAMPLE.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(pcmBytes.toInt())
        }.array()
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.write(header)
        }
    }

    companion object {
        internal const val SAMPLE_RATE_HZ = 16_000
        private const val CHANNELS = 1
        private const val BITS_PER_SAMPLE = 16
        private const val WAV_HEADER_BYTES = 44
    }
}
