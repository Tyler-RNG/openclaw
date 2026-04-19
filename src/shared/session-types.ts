export type GatewayAgentAvatarStateEntry = {
  file: string;
  description?: string;
};

export type GatewayAgentAvatarStates = {
  default: string;
  states: Record<string, GatewayAgentAvatarStateEntry>;
  instruction: string;
};

/**
 * Sprite-frames avatar descriptor (see docs/avatars/formats.md — Format 2).
 * Passed through from config nearly verbatim so the client has everything it
 * needs to prefetch the right files and drive playback timing. `instruction`
 * is synthesized from per-state descriptions the same way `avatarStates.instruction` is.
 */
export type GatewayAgentAvatarSprites = {
  default: string;
  basePath: string;
  format: "webp" | "png" | "jpg";
  states: Record<string, unknown>;
  transitions?: Record<string, unknown>;
  instruction: string;
};

/**
 * Sprite-atlas avatar descriptor (see docs/avatars/formats.md — Format 3).
 * Client fetches `manifest` + its sibling atlas image via the asset endpoint;
 * manifest owns frames + animations + transitions, this row owns descriptions
 * and the synthesized instruction.
 */
export type GatewayAgentAvatarAtlas = {
  default: string;
  manifest: string;
  descriptions?: Record<string, string>;
  instruction: string;
};

export type GatewayAgentIdentity = {
  name?: string;
  theme?: string;
  emoji?: string;
  avatar?: string;
  avatarUrl?: string;
  /**
   * Multi-state avatar descriptor. Present only when the agent's identity is
   * configured with `avatar.kind: "states"`. Clients that render multi-state
   * avatars should read this and inject `instruction` as a system message on
   * new sessions; other clients can safely ignore it.
   */
  avatarStates?: GatewayAgentAvatarStates;
  /** Present when `avatar.kind: "sprites"`. See GatewayAgentAvatarSprites. */
  avatarSprites?: GatewayAgentAvatarSprites;
  /** Present when `avatar.kind: "atlas"`. See GatewayAgentAvatarAtlas. */
  avatarAtlas?: GatewayAgentAvatarAtlas;
};

export type GatewayAgentModel = {
  primary?: string;
  fallbacks?: string[];
};

export type GatewayAgentVoice = {
  provider?: string;
  voiceId?: string;
  label?: string;
  [key: string]: unknown;
};

export type GatewayAgentRow = {
  id: string;
  name?: string;
  identity?: GatewayAgentIdentity;
  workspace?: string;
  model?: GatewayAgentModel;
  voice?: GatewayAgentVoice;
};

export type SessionsListResultBase<TDefaults, TRow> = {
  ts: number;
  path: string;
  count: number;
  defaults: TDefaults;
  sessions: TRow[];
};

export type SessionsPatchResultBase<TEntry> = {
  ok: true;
  path: string;
  key: string;
  entry: TEntry;
};
