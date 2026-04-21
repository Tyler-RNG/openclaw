/**
 * Sprite-atlas avatar descriptor (see docs/avatars/formats.md).
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
