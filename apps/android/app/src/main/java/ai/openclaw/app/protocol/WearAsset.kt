package ai.openclaw.app.protocol

/**
 * Wire-level constants for avatar/asset references exchanged with the Wear
 * watch. Mirrors src/protocol/wear-asset.ts on the gateway — keep in sync.
 */
object WearAsset {
    const val AVATAR_REF_PREFIX = "wear-asset:avatar:"
    const val SPRITES_REF_PREFIX = "wear-asset:sprites:"
    const val ATLAS_REF_PREFIX = "wear-asset:atlas:"
    const val DATA_AVATAR_PATH = "/openclaw/avatars"
    const val DATA_TTS_PATH = "/openclaw/tts"

    fun buildAvatarRef(agentId: String): String = "$AVATAR_REF_PREFIX$agentId"
    fun buildSpritesRef(agentId: String): String = "$SPRITES_REF_PREFIX$agentId"
    fun buildAtlasRef(agentId: String): String = "$ATLAS_REF_PREFIX$agentId"

    fun parseAvatarRef(raw: String?): String? {
        if (raw == null || !raw.startsWith(AVATAR_REF_PREFIX)) return null
        val id = raw.substring(AVATAR_REF_PREFIX.length)
        return id.takeIf { it.isNotEmpty() }
    }

    fun avatarDataPath(agentId: String): String = "$DATA_AVATAR_PATH/$agentId"
    fun avatarFramePath(agentId: String, state: String, phase: String?, index: Int): String {
        val padded = index.toString().padStart(if (index >= 100) 3 else 2, '0')
        return if (phase != null) "$DATA_AVATAR_PATH/$agentId/frames/$state/$phase/$padded"
        else "$DATA_AVATAR_PATH/$agentId/frames/$state/$padded"
    }
    fun atlasImagePath(agentId: String): String = "$DATA_AVATAR_PATH/$agentId/atlas/image"
    fun atlasManifestPath(agentId: String): String = "$DATA_AVATAR_PATH/$agentId/atlas/manifest"

    /**
     * State-change signal path. DataItem body: `{ state: "<name>", ts: Long }`.
     * Watch subscribes here and pipes updates into AvatarRuntime.requestState
     * so sprite/atlas agents can swap mid-reply without byte re-pushes.
     * Format-agnostic: the phone publishes this for every agent kind; the
     * watch's AvatarRuntime ignores unknown state names.
     */
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
}
