// Shared wire-level constants for avatar/asset references exchanged between
// the gateway, the Android phone relay, and the Wear OS watch app. The phone
// rewrites an agent's `identity.avatarUrl` to `wear-asset:avatar:<agentId>`
// so the watch (which fetches bytes via DataClient, not HTTP) knows to look
// up cached bytes in its asset store instead of fetching the URL directly.
//
// Kotlin mirrors this same shape — keep the constants in sync:
//   apps/android/app/src/main/java/ai/openclaw/app/protocol/WearAsset.kt
//   apps/wearable/app/src/main/java/ai/openclaw/wear/protocol/WearAsset.kt

export const WEAR_ASSET_AVATAR_PREFIX = "wear-asset:avatar:";

/** DataClient path root the phone publishes avatar bytes to. */
export const WEAR_DATA_AVATAR_PATH = "/openclaw/avatars";

/** DataClient path root the phone publishes TTS audio bytes to. */
export const WEAR_DATA_TTS_PATH = "/openclaw/tts";

export function buildWearAssetAvatarRef(agentId: string): string {
  return `${WEAR_ASSET_AVATAR_PREFIX}${agentId}`;
}

/** Returns the agentId when `raw` is a well-formed wear-asset avatar ref, else null. */
export function parseWearAssetAvatarRef(raw: string | null | undefined): string | null {
  if (!raw || !raw.startsWith(WEAR_ASSET_AVATAR_PREFIX)) {
    return null;
  }
  const id = raw.slice(WEAR_ASSET_AVATAR_PREFIX.length);
  return id.length > 0 ? id : null;
}
