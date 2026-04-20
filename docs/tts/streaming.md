# TTS Streaming — Phone → Watch

Design doc for sub-second time-to-first-audio on the Wear OS dial. The current production path buffers the entire ElevenLabs response on the phone then ships the blob to the watch; this doc sketches the chunked replacement that's wired through `NodeRuntime.wearRelayChatStream` via a pinned TODO in the source.

Status: **design + wire format pinned, runtime not yet implemented**. The TODO in `apps/android/app/src/main/java/ai/openclaw/app/NodeRuntime.kt` at the `wearRelayTalkSpeak` call site points at this doc.

## Why

`/stream/tts` on the gateway already streams from ElevenLabs — the server emits audio bytes in chunks starting ~300ms after the request lands. Today the phone waits for the whole response (~1.5–2s for a short reply) before pushing a single DataClient asset to the watch, so the watch's perceived time-to-first-audio is the full ElevenLabs end-to-end latency plus the DataClient transfer. Chunking along the whole path can drop that to ~400ms.

## Wire format

Four DataClient paths — two are new, two already exist.

| Path                           | Kind                       | Payload                                                                                                             | Direction     |
| ------------------------------ | -------------------------- | ------------------------------------------------------------------------------------------------------------------- | ------------- |
| `/openclaw/tts/<turnId>/<seq>` | Chunk                      | `{ data: Asset(bytes), mime, seq, final?: true }`                                                                   | phone → watch |
| `/openclaw/tts/<turnId>/end`   | Marker                     | `{ ts, totalChunks }`                                                                                               | phone → watch |
| `/openclaw/chat/reply`         | Reply envelope             | existing — carries `audioTurnId` pointing at the chunk tree instead of `audioBase64`/`audioAssetRef` when streaming | phone → watch |
| `/openclaw/tts/<turnId>`       | Fallback single-blob asset | `{ data: Asset, mime }`                                                                                             | phone → watch |

`turnId` is a UUID the phone mints per reply. The watch uses it to correlate chunks to the reply envelope.

When the phone elects to stream (TTS response looks streamable, DataClient is healthy, watch appears connected) it emits `audioTurnId` on the reply envelope and publishes chunk items as the ElevenLabs response arrives. When it elects not to, it uses the existing `audioBase64` or `audioAssetRef` fields and skips chunk publication entirely — no regression for small replies or for environments where chunking would be wasteful.

## Phone side

```kotlin
// Replaces the wearRelayTalkSpeak blob call in NodeRuntime.wearRelayChatStream.

suspend fun streamTtsChunks(
    text: String,
    agentId: String,
    turnId: String,
    publishChunk: suspend (seq: Int, bytes: ByteArray, mime: String, final: Boolean) -> Unit,
): Result {
    val voice = resolveVoiceId(agentId) ?: return Result.Skipped
    val url = "${dataPlane.baseUrl}/stream/tts?voice=${voice}&text=${text.encoded()}&token=${token}"
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 5_000
        readTimeout = 30_000
        requestMethod = "GET"
        setRequestProperty("Accept", "audio/mpeg")
    }
    if (conn.responseCode != 200) return Result.Failed(conn.responseCode)
    val mime = conn.contentType?.substringBefore(';')?.trim() ?: "audio/mpeg"
    conn.inputStream.use { input ->
        val buffer = ByteArray(CHUNK_SIZE)
        var seq = 0
        while (true) {
            val n = input.read(buffer)
            if (n <= 0) break
            val chunk = buffer.copyOf(n)
            publishChunk(seq, chunk, mime, false)
            seq++
        }
        publishChunk(seq, ByteArray(0), mime, true)  // final marker
    }
    return Result.Ok
}
```

`CHUNK_SIZE`: start at 8 KB (≈ 200 ms of 64 kbps MP3). Small enough to arrive before watch starts playback, large enough to amortize DataClient overhead per item (putDataItem is ~10–30 ms round trip). Tune based on measured first-audio latency after landing.

Each `publishChunk` call writes a `PutDataMapRequest` with `data: Asset.createFromBytes(chunk)`, `mime`, `seq`, `final` (bool), `ts`.

## Watch side

New state in `WearAssetStore`:

```kotlin
// Per-turnId assembly buffer. SortedMap keeps chunks ordered by seq so late
// arrivals (rare but possible under DataClient backpressure) don't corrupt
// playback order.
private val _ttsChunks = MutableStateFlow<Map<String, SortedMap<Int, ByteArray>>>(emptyMap())
private val _ttsFinal = MutableStateFlow<Set<String>>(emptySet())
```

`handleChanged` adds a path branch:

```kotlin
path.matches(Regex("/openclaw/tts/[^/]+/\\d+")) -> {
    val parts = path.split("/")
    val turnId = parts[3]; val seq = parts[4].toInt()
    val isFinal = dm.getBoolean("final", false)
    _ttsChunks.update { current ->
        val per = (current[turnId] ?: sortedMapOf()).also { it[seq] = bytes }
        current + (turnId to per)
    }
    if (isFinal) _ttsFinal.update { it + turnId }
}
```

Playback entry point — either a tempfile-backed MediaPlayer that starts on first chunk (true streaming) or an assembled-blob MediaPlayer that starts on `final` (simpler; no latency benefit yet but completes the round trip):

### Phase 1 — assembled blob (no latency win, infrastructure only)

```kotlin
suspend fun awaitTtsStream(turnId: String, timeoutMs: Long = 30_000): ByteArray? {
    withTimeoutOrNull(timeoutMs) {
        ttsFinal.first { turnId in it }
    } ?: return null
    val chunks = _ttsChunks.value[turnId] ?: return null
    val out = ByteArrayOutputStream()
    for ((_, chunk) in chunks) out.write(chunk)
    _ttsChunks.update { it - turnId }
    _ttsFinal.update { it - turnId }
    return out.toByteArray()
}
```

Integrated with existing `playElevenLabsAudio(bytes)`. Ships the wire format, gets parity with today's blob path, sets the stage for Phase 2.

### Phase 2 — true streaming playback (the actual latency win)

Wear MediaPlayer supports `setDataSource(FileDescriptor, offset, length)` against a file that's still being written — the decoder blocks briefly when it catches up to the write head and resumes when more bytes arrive. Pattern:

1. On first chunk arrival, create a `File` in app cache (`context.cacheDir/tts/<turnId>.mp3`), open a `FileOutputStream` for appending, write chunk bytes.
2. Start `MediaPlayer` with `setDataSource(ParcelFileDescriptor.open(file, MODE_READ_ONLY).fileDescriptor)`, then `prepareAsync()` + `start()` on the `onPrepared` callback.
3. For each subsequent chunk, append to the file. If the MediaPlayer has buffered past the current file size it'll briefly stall then catch up.
4. On the `final` marker, close the write stream; MediaPlayer plays to EOF naturally and fires `onCompletion`.
5. Delete the temp file on completion + clear per-turn state.

Gotchas:

- **MediaPlayer decoder behavior is device-dependent.** Pixel Watch 2 + 3 handle growing files cleanly; older Wear devices may need a minimum pre-buffer (~32 KB) before `prepareAsync()` to avoid `onError` at start. Add a buffer threshold.
- **Clock skew between chunk publish and file write is fine.** DataClient OnDataChangedListener fires on the main looper; chunk assembly runs off that → I/O. Writes are serialized per-turn with a `Mutex`.
- **Cache dir pressure.** Clean up stale `<turnId>.mp3` files at app start (older than 24h) and on `onCompletion`/`onError`.
- **Watch disconnects mid-stream.** If the final marker never arrives, `awaitTtsStream` times out after 30s; tempfile is deleted; playback falls back to nothing. Consider a "partial playback of what we have" heuristic if UX feedback demands it.

## Phase 0 — what ships in the interim

The existing single-blob path (`audioAssetRef` → DataClient asset → `awaitTts`) stays in production. The streaming path is plumbing only until Phase 2 lands. The TODO in `NodeRuntime.kt` at `wearRelayTalkSpeak` marks where Phase 1 swap-in goes.

## Migration

- **Phase 1**: phone always writes chunks for any reply >4 KB audio; watch assembles + plays on `final`. Equal latency to today, but moves the wire format. Measure chunk count distribution and tune `CHUNK_SIZE`.
- **Phase 2**: watch switches to tempfile-fed MediaPlayer. Phone unchanged. This is the "time-to-first-audio drops to ~400 ms" commit.
- **Phase 3 (optional)**: gateway-side TTS prefetch cache so repeat requests for the same `(voice, text)` skip ElevenLabs entirely. Saves money + latency on conversational repeats.

## Update protocol

Update this doc when:

- `NodeRuntime.wearRelayChatStream`'s TTS call changes shape
- DataClient path schema evolves
- Chunk size or buffer thresholds change
- A new playback runtime (ExoPlayer, etc.) replaces MediaPlayer

Linked from `docs/avatars/formats.md` via the TTS cross-reference block (if present) and from `AGENTS.md` → _Scoped Workflow Guides_ if we decide to promote it.
