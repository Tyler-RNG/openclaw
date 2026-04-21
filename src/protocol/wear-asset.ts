// Shared wire-level constants for avatar/asset references exchanged between
// the gateway, the Android phone relay, and the Wear OS watch app. The phone
// rewrites an agent's `identity.avatarUrl` to `wear-asset:avatar:<agentId>`
// so the watch (which fetches bytes via DataClient, not HTTP) knows to look
// up cached bytes in its asset store instead of fetching the URL directly.
//
// Kotlin mirrors this same shape — keep the constants in sync:
//   apps/android/app/src/main/java/ai/openclaw/app/protocol/WearAsset.kt
//   apps/wearable/app/src/main/java/ai/openclaw/wear/protocol/WearAsset.kt

/** Legacy GIF or single-image avatar — bytes published at WEAR_DATA_AVATAR_PATH/<agentId>. */
export const WEAR_ASSET_AVATAR_PREFIX = "wear-asset:avatar:";
/** Sprite-frames avatar — frames published under WEAR_DATA_AVATAR_PATH/<agentId>/frames/... */
export const WEAR_ASSET_SPRITES_PREFIX = "wear-asset:sprites:";
/** Sprite-atlas avatar — image + manifest published under WEAR_DATA_AVATAR_PATH/<agentId>/atlas/... */
export const WEAR_ASSET_ATLAS_PREFIX = "wear-asset:atlas:";

/** DataClient path root the phone publishes avatar bytes to. */
export const WEAR_DATA_AVATAR_PATH = "/openclaw/avatars";

/** DataClient path root the phone publishes TTS audio bytes to. */
export const WEAR_DATA_TTS_PATH = "/openclaw/tts";

export function buildWearAssetAvatarRef(agentId: string): string {
  return `${WEAR_ASSET_AVATAR_PREFIX}${agentId}`;
}

export function buildWearAssetSpritesRef(agentId: string): string {
  return `${WEAR_ASSET_SPRITES_PREFIX}${agentId}`;
}

export function buildWearAssetAtlasRef(agentId: string): string {
  return `${WEAR_ASSET_ATLAS_PREFIX}${agentId}`;
}

/**
 * DataClient path the phone publishes `{ state, ts }` signals to when an
 * agent's avatar should swap mid-reply. Watch subscribes here and drives
 * `AvatarRuntime.requestState`. Format-agnostic across all three avatar
 * kinds — runtime ignores unknown state names.
 */
export function wearAvatarStatePath(agentId: string): string {
  return `${WEAR_DATA_AVATAR_PATH}/${agentId}/state`;
}

/**
 * DataClient path for the per-agent CharacterManifest JSON bundle. The phone
 * calls `node.getCharacterManifest(agentId)` on the gateway, publishes the
 * envelope (manifest + revision) here, and publishes each asset ref's bytes
 * under `wearCharacterManifestAssetPath(agentId, refKey)`. Watch subscribes
 * to both, assembles a ready-to-play bundle, and feeds it to DisplayKit's
 * `AnimationGraph.fromManifest(...) + SpriteAnimationPlayer`. Supersedes the
 * per-kind legacy paths (`/frames/...`, `/atlas/image`, `/atlas/manifest`)
 * once clients have migrated.
 */
export function wearCharacterManifestPath(agentId: string): string {
  return `${WEAR_DATA_AVATAR_PATH}/${agentId}/character-manifest`;
}

/** Per-asset byte path under the manifest. `refKey` matches `assets.refs` keys. */
export function wearCharacterManifestAssetPath(agentId: string, refKey: string): string {
  return `${WEAR_DATA_AVATAR_PATH}/${agentId}/character-assets/${refKey}`;
}

/** Returns the agentId when `raw` is a well-formed wear-asset avatar ref, else null. */
export function parseWearAssetAvatarRef(raw: string | null | undefined): string | null {
  if (!raw || !raw.startsWith(WEAR_ASSET_AVATAR_PREFIX)) {
    return null;
  }
  const id = raw.slice(WEAR_ASSET_AVATAR_PREFIX.length);
  return id.length > 0 ? id : null;
}
