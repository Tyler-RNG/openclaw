package ai.openclaw.displaykit

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Pure-Kotlin mirror of the `CharacterManifest` wire schema served by the
 * gateway at `node.getCharacterManifest`. These data classes carry zero
 * platform deps — Android, iOS, tests, and future thin clients all parse the
 * same bytes.
 *
 * Keep in sync with src/gateway/protocol/schema/display.ts.
 */
@Serializable
data class CharacterManifest(
    val version: Int,
    val agentId: String,
    val name: String? = null,
    val modes: List<String>,
    val stateMap: Map<String, String>,
    val content: Map<String, ModeContent>,
    val assets: AssetBundle,
)

@Serializable
data class ModeContent(
    val atlas: AtlasRef? = null,
    val animations: Map<String, Animation>,
    val transitions: Map<String, TransitionRef>? = null,
)

@Serializable
data class AtlasRef(
    val image: String,
    val size: Size,
    val frameSize: Size? = null,
)

@Serializable
data class Size(val w: Int, val h: Int)

@Serializable
data class FrameRef(
    val ref: String,
    val x: Int? = null,
    val y: Int? = null,
    val w: Int? = null,
    val h: Int? = null,
)

@Serializable
data class FrameSequence(
    val frames: List<FrameRef>,
    val fps: Int,
    val loop: LoopMode,
    val holdLastFrame: Boolean = false,
    val iterations: Int? = null,
)

@Serializable
data class Animation(
    val description: String? = null,
    val sequence: FrameSequence? = null,
    val intro: FrameSequence? = null,
    val loop: FrameSequence? = null,
    val outro: FrameSequence? = null,
) {
    /**
     * Treat a flat sequence as the `loop` phase so the player can always look
     * up phases by name without special-casing flat vs phased at every site.
     */
    val effectiveLoop: FrameSequence? get() = loop ?: sequence
}

@Serializable(with = LoopModeSerializer::class)
enum class LoopMode(val wire: String) {
    INFINITE("infinite"),
    ONCE("once"),
    PING_PONG("ping-pong"),
    ;

    companion object {
        fun fromWire(value: String): LoopMode =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("unknown loop mode: $value")
    }
}

private object LoopModeSerializer : KSerializer<LoopMode> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("LoopMode", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: LoopMode) {
        encoder.encodeString(value.wire)
    }

    override fun deserialize(decoder: Decoder): LoopMode = LoopMode.fromWire(decoder.decodeString())
}

/**
 * A transition is either a named phase reference (e.g. `"thinking.intro"`)
 * the runtime plays once on state swap, or an inline blend directive the
 * runtime applies as a visual effect during the swap.
 */
@Serializable(with = TransitionRefSerializer::class)
sealed class TransitionRef {
    @Serializable
    data class Phase(val value: String) : TransitionRef()

    @Serializable
    data class Crossfade(val blend: String = "crossfade", val ms: Int) : TransitionRef()
}

private object TransitionRefSerializer :
    JsonContentPolymorphicSerializer<TransitionRef>(TransitionRef::class) {
    override fun selectDeserializer(element: JsonElement) = when (element) {
        is JsonPrimitive -> PhaseStringSerializer
        else -> TransitionRef.Crossfade.serializer()
    }
}

private object PhaseStringSerializer : KSerializer<TransitionRef.Phase> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("TransitionRef.Phase", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: TransitionRef.Phase) {
        encoder.encodeString(value.value)
    }

    override fun deserialize(decoder: Decoder): TransitionRef.Phase =
        TransitionRef.Phase(decoder.decodeString())
}

@Serializable
data class AssetBundle(val refs: Map<String, String>) {
    /** Look up the asset path for a frame ref. Returns null if the ref is unknown. */
    fun pathFor(ref: FrameRef): String? = refs[ref.ref]
}

@Serializable
data class CharacterManifestEnvelope(
    val manifest: CharacterManifest,
    val revision: Int,
)
