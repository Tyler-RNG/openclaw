package ai.openclaw.app.voice

import ai.openclaw.app.diag.PhoneDiagLog
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Direct-upload client for the gateway's `POST /stream/stt` route (served by
 * the SpriteCore plugin). Mirrors [TalkDataPlaneTtsFetcher]: the phone has
 * network access to the gateway and operator-bearer auth, so it uploads the
 * recorded audio and the gateway forwards to ElevenLabs (the API key never
 * leaves the gateway host).
 *
 * Phase 1 is batch-only — press-and-hold records locally, releases POSTs the
 * whole clip, returns the transcript JSON. A later realtime/WebSocket client
 * can sit beside this fetcher when streaming partials are wanted.
 */
internal class TalkDataPlaneSttFetcher(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    /**
     * Upload [audioFile] as the POST body to `/stream/stt` and return the
     * transcribed text, or null on any failure so the caller can fall back
     * to Android's on-device [android.speech.SpeechRecognizer].
     *
     * [contentType] is the audio MIME the gateway forwards to ElevenLabs —
     * typically `audio/wav` for PCM-wrapped recordings.
     *
     * Logs under the `stt` tag at every state transition so operators can
     * trace: request construction, upstream latency, response body size,
     * parse outcome, and error cause.
     */
    suspend fun transcribe(
        baseUrl: String,
        token: String,
        audioFile: File,
        contentType: String = "audio/wav",
        model: String? = null,
        language: String? = null,
    ): SttResult = withContext(Dispatchers.IO) {
        val size = audioFile.length()
        val url = buildUrl(baseUrl, model = model, language = language)
        PhoneDiagLog.outgoing(
            "stt",
            "POST /stream/stt bytes=$size contentType=$contentType" +
                (model?.let { " model=$it" } ?: "") +
                (language?.let { " lang=$it" } ?: ""),
        )
        val start = System.currentTimeMillis()

        val conn = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = 60_000
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", contentType)
                setFixedLengthStreamingMode(size)
            }
        } catch (e: Throwable) {
            PhoneDiagLog.error("stt", "connect setup failed: ${e.javaClass.simpleName} ${e.message?.take(60)}")
            return@withContext SttResult.Failure("connect setup failed: ${e.javaClass.simpleName}")
        }

        try {
            FileInputStream(audioFile).use { input ->
                conn.outputStream.use { output ->
                    input.copyTo(output, bufferSize = 16 * 1024)
                }
            }
        } catch (e: SocketTimeoutException) {
            PhoneDiagLog.warn("stt", "upload timeout after ${System.currentTimeMillis() - start}ms")
            conn.disconnect()
            return@withContext SttResult.Failure("upload timeout")
        } catch (e: UnknownHostException) {
            PhoneDiagLog.warn("stt", "dns fail: ${e.message?.take(60)}")
            conn.disconnect()
            return@withContext SttResult.Failure("dns fail")
        } catch (e: Throwable) {
            PhoneDiagLog.error("stt", "upload failed: ${e.javaClass.simpleName} ${e.message?.take(80)}")
            conn.disconnect()
            return@withContext SttResult.Failure("upload failed: ${e.javaClass.simpleName}")
        }

        val code = try {
            conn.responseCode
        } catch (e: Throwable) {
            PhoneDiagLog.error("stt", "responseCode threw: ${e.javaClass.simpleName}")
            conn.disconnect()
            return@withContext SttResult.Failure("responseCode threw: ${e.javaClass.simpleName}")
        }

        val body = try {
            val stream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
            stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        } catch (e: Throwable) {
            PhoneDiagLog.error("stt", "read body threw: ${e.javaClass.simpleName}")
            conn.disconnect()
            return@withContext SttResult.Failure("read body threw: ${e.javaClass.simpleName}")
        } finally {
            conn.disconnect()
        }

        val tookMs = System.currentTimeMillis() - start
        if (code !in 200..299) {
            // Surface the plugin / upstream error body so the diagnostic panel
            // can show whether it was auth, config, quota, upstream 5xx, etc.
            PhoneDiagLog.warn("stt", "HTTP $code (${tookMs}ms) body=${body.take(200)}")
            return@withContext SttResult.Failure("http $code: ${body.take(120)}")
        }

        val parsed = parseTranscriptBody(body)
        if (parsed == null) {
            PhoneDiagLog.warn("stt", "parse fail (${tookMs}ms) body=${body.take(200)}")
            return@withContext SttResult.Failure("parse fail")
        }
        PhoneDiagLog.incoming(
            "stt",
            "HTTP 200 (${tookMs}ms) chars=${parsed.text.length} lang=${parsed.languageCode ?: "?"}" +
                (parsed.languageProbability?.let { " prob=${"%.2f".format(it)}" } ?: ""),
        )
        SttResult.Success(parsed)
    }

    private fun buildUrl(baseUrl: String, model: String?, language: String?): String {
        val sb = StringBuilder("${baseUrl.trimEnd('/')}/stream/stt")
        val params = buildList {
            model?.takeIf { it.isNotBlank() }?.let { add("model=$it") }
            language?.takeIf { it.isNotBlank() }?.let { add("language=$it") }
        }
        if (params.isNotEmpty()) sb.append('?').append(params.joinToString("&"))
        return sb.toString()
    }

    private fun parseTranscriptBody(body: String): SttTranscript? {
        if (body.isBlank()) return null
        val root = try {
            json.parseToJsonElement(body) as? JsonObject
        } catch (_: Throwable) {
            return null
        } ?: return null
        val text = (root["text"] as? JsonPrimitive)?.content?.trim() ?: return null
        val languageCode = (root["language_code"] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotBlank() }
        val languageProbability = (root["language_probability"] as? JsonPrimitive)?.content?.toDoubleOrNull()
        return SttTranscript(
            text = text,
            languageCode = languageCode,
            languageProbability = languageProbability,
        )
    }
}

/** Parsed subset of ElevenLabs' `/v1/speech-to-text` response, as proxied by the gateway. */
internal data class SttTranscript(
    val text: String,
    val languageCode: String?,
    val languageProbability: Double?,
)

internal sealed interface SttResult {
    data class Success(val transcript: SttTranscript) : SttResult
    data class Failure(val message: String) : SttResult
}
