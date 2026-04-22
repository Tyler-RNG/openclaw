import type { SecretInput } from "openclaw/plugin-sdk/secret-input";

export type SpriteCoreAvatarStateEntry = {
  file: string;
  description?: string;
};

export type SpriteCoreAvatarStatesConfig = {
  kind: "states";
  default: string;
  states: Record<string, SpriteCoreAvatarStateEntry>;
  instruction?: string;
};

export type SpriteCoreAvatarLoopMode = "infinite" | "once" | "ping-pong";

export type SpriteCoreAvatarSpriteSequence = {
  count: number;
  fps?: number;
  loop?: SpriteCoreAvatarLoopMode;
  holdLastFrame?: boolean;
  iterations?: number;
};

export type SpriteCoreAvatarSpriteStatePhased = {
  intro?: SpriteCoreAvatarSpriteSequence;
  loop: SpriteCoreAvatarSpriteSequence;
  outro?: SpriteCoreAvatarSpriteSequence;
  description?: string;
};

export type SpriteCoreAvatarSpriteState =
  | (SpriteCoreAvatarSpriteSequence & { description?: string })
  | SpriteCoreAvatarSpriteStatePhased;

export type SpriteCoreAvatarTransition = string | { blend: "crossfade"; ms: number };

export type SpriteCoreAvatarSpritesConfig = {
  kind: "sprites";
  default: string;
  basePath: string;
  format?: "webp" | "png" | "jpg";
  states: Record<string, SpriteCoreAvatarSpriteState>;
  transitions?: Record<string, SpriteCoreAvatarTransition>;
  instruction?: string;
};

export type SpriteCoreAvatarAtlasConfig = {
  kind: "atlas";
  default: string;
  manifest: string;
  descriptions?: Record<string, string>;
  instruction?: string;
};

export type SpriteCoreAvatarConfig =
  | SpriteCoreAvatarStatesConfig
  | SpriteCoreAvatarSpritesConfig
  | SpriteCoreAvatarAtlasConfig;

export type SpriteCoreVoiceConfig = {
  provider?: string;
  voiceId?: string;
  label?: string;
  [key: string]: unknown;
};

export type SpriteCorePromptingConfig = {
  /**
   * Per-state human-readable description.
   *
   * @deprecated Prefer `agents.<id>.emotions.<state>.description` — the new
   * shape co-locates the description with its voice-directive override and is
   * the source of truth for the injected prompt block. This field is still
   * honored as a fallback when no `emotions[state].description` is set, to
   * give operators a soft migration window.
   */
  descriptions?: Record<string, string>;
  /** Optional explicit override; when set, replaces the auto-generated instruction text entirely. */
  instruction?: string;
};

/**
 * Per-emotion TalkDirective override.
 *
 * When the model emits `<<<state>>>` in a reply, the text segment that
 * follows inherits the base voice directive merged with this override
 * (field-by-field; fields unset here fall back to the base directive). Applied
 * by the client after marker parsing; shipped to clients via the
 * `CharacterManifest.emotions` field of `node.getCharacterManifest`.
 */
export type SpriteCoreEmotionDirective = {
  voiceId?: string;
  stability?: number;
  similarity?: number;
  style?: number;
  speakerBoost?: boolean;
  speed?: number;
  /**
   * Optional inline audio-tag prefix applied to the segment text before TTS
   * synthesis. Use with models that support inline emotion tags
   * (e.g. ElevenLabs `eleven_v3`: `[happy]`, `[sad]`, `[excited]`,
   * `[whispers]`, `[laughs]`). The tag is prepended with a trailing space:
   * `audioTag: "[happy]"` turns `"great today"` into `"[happy] great today"`
   * before synthesis. Older ElevenLabs models ignore the tag — safe to leave
   * unset when using `eleven_turbo_v2` / `eleven_multilingual_v2`.
   */
  audioTag?: string;
};

/**
 * Per-state emotion entry: the prompt-visible description and the optional
 * voice-directive override. The `description` feeds the injected system-prompt
 * block (server-side only, not shipped to clients); the `directive` is what
 * clients apply to TTS for the text segment following a marker.
 */
export type SpriteCoreEmotionEntry = {
  description: string;
  directive?: SpriteCoreEmotionDirective;
};

export type SpriteCoreAgentEntry = {
  avatar?: SpriteCoreAvatarConfig;
  voice?: SpriteCoreVoiceConfig;
  prompting?: SpriteCorePromptingConfig;
  /**
   * Per-state emotion configuration. Keys are state names (e.g. `happy`,
   * `thinking`); values carry the human-readable description used in the
   * prompt block and an optional TTS directive override. State names must
   * match the avatar's render states so `<<<state>>>` from the model maps
   * cleanly to both an animation and a voice.
   */
  emotions?: Record<string, SpriteCoreEmotionEntry>;
};

export type SpriteCoreAssetsConfig = {
  enabled?: boolean;
  assetsDir?: string;
  publicAssets?: boolean;
  maxAssetSizeBytes?: number;
  publicBaseUrl?: string;
};

export type SpriteCoreStreamTtsConfig = {
  enabled?: boolean;
  provider?: "elevenlabs";
  apiKey?: SecretInput;
  defaultModel?: string;
};

export type SpriteCoreConfig = {
  assets?: SpriteCoreAssetsConfig;
  streamTts?: SpriteCoreStreamTtsConfig;
  agents?: Record<string, SpriteCoreAgentEntry>;
};
