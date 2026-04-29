package ai.openclaw.wear.protocol

/**
 * Wire-level constants for avatar/asset references exchanged with the phone.
 * Mirrors src/protocol/wear-asset.ts on the gateway — keep in sync.
 */
object WearAsset {
    const val AVATAR_REF_PREFIX = "wear-asset:avatar:"
    const val SPRITES_REF_PREFIX = "wear-asset:sprites:"
    const val ATLAS_REF_PREFIX = "wear-asset:atlas:"
    const val DATA_AVATAR_PATH = "/openclaw/avatars"
    const val DATA_TTS_PATH = "/openclaw/tts"

    fun parseAvatarRef(raw: String?): String? = stripPrefix(raw, AVATAR_REF_PREFIX)
    fun parseSpritesRef(raw: String?): String? = stripPrefix(raw, SPRITES_REF_PREFIX)
    fun parseAtlasRef(raw: String?): String? = stripPrefix(raw, ATLAS_REF_PREFIX)

    /** Return the ref kind ("avatar" | "sprites" | "atlas") or null if unrecognized. */
    fun refKind(raw: String?): String? = when {
        raw == null -> null
        raw.startsWith(AVATAR_REF_PREFIX) -> "avatar"
        raw.startsWith(SPRITES_REF_PREFIX) -> "sprites"
        raw.startsWith(ATLAS_REF_PREFIX) -> "atlas"
        else -> null
    }

    fun avatarStatePath(agentId: String): String = "$DATA_AVATAR_PATH/$agentId/state"

    /**
     * DataClient path for the per-agent CharacterManifest JSON envelope
     * ({manifest, revision}) synthesized by the gateway's
     * node.getCharacterManifest RPC. Phone publishes this + each
     * asset-ref's bytes (see [characterManifestAssetPath]); watch
     * subscribes and drives the SpriteCore SDK's SpriteAnimationPlayer via
     * AnimationGraph.fromManifest(). Supersedes the per-kind sprite
     * `frames/...` and atlas `atlas/image`/`atlas/manifest` paths once
     * clients have migrated.
     */
    fun characterManifestPath(agentId: String): String =
        "$DATA_AVATAR_PATH/$agentId/character-manifest"

    fun characterManifestAssetPath(agentId: String, refKey: String): String =
        "$DATA_AVATAR_PATH/$agentId/character-assets/$refKey"

    /** True if [path] is a character-manifest envelope DataItem path. */
    fun isCharacterManifestPath(path: String): Boolean {
        return characterManifestRegex.matches(path)
    }

    /** True if [path] is a character-asset bytes DataItem path. Returns null if not. */
    fun parseCharacterAssetPath(path: String): Pair<String, String>? {
        val m = characterAssetRegex.matchEntire(path) ?: return null
        return m.groupValues[1] to m.groupValues[2]
    }

    private val characterManifestRegex =
        Regex("${Regex.escape(DATA_AVATAR_PATH)}/([^/]+)/character-manifest")
    private val characterAssetRegex =
        Regex("${Regex.escape(DATA_AVATAR_PATH)}/([^/]+)/character-assets/(.+)")

    private fun stripPrefix(raw: String?, prefix: String): String? {
        if (raw == null || !raw.startsWith(prefix)) return null
        val id = raw.substring(prefix.length)
        return id.takeIf { it.isNotEmpty() }
    }
}
