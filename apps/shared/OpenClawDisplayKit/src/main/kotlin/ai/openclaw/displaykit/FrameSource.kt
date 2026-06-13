package ai.openclaw.displaykit

/**
 * Platform-specific resolver from a [FrameRef] to a concrete renderable
 * (e.g. Android `Bitmap`, iOS `UIImage`, a byte array, whatever the caller
 * chooses). The kit itself never constructs frames — callers own the pixel
 * pipeline and only feed [SpriteAnimationPlayer.currentRef] into their own
 * [FrameSource] when rendering.
 *
 * Atlas sources honor the optional `x/y/w/h` fields on [FrameRef]; sprite
 * sources ignore them and treat `ref` as the whole-image key.
 */
fun interface FrameSource<FrameT> {
    /** Return the frame for [ref], or null if the ref is unknown. */
    fun frame(ref: FrameRef): FrameT?
}

/**
 * Simple in-memory sprite source: callers prime a byte-array map, decode
 * happens lazily through [decode]. Useful for unit tests and thin clients
 * that don't need the platform-specific image types.
 */
class InMemorySpriteSource<FrameT>(
    private val decode: (ByteArray) -> FrameT?,
) : FrameSource<FrameT> {
    private val bytesByRef = mutableMapOf<String, ByteArray>()
    private val cache = mutableMapOf<String, FrameT>()

    fun put(refKey: String, bytes: ByteArray) {
        bytesByRef[refKey] = bytes
        cache.remove(refKey)
    }

    fun keys(): Set<String> = bytesByRef.keys

    override fun frame(ref: FrameRef): FrameT? {
        cache[ref.ref]?.let { return it }
        val bytes = bytesByRef[ref.ref] ?: return null
        val decoded = decode(bytes) ?: return null
        cache[ref.ref] = decoded
        return decoded
    }
}
