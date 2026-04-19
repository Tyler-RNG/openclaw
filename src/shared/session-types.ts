export type GatewayAgentAvatarStateEntry = {
  file: string;
  description?: string;
};

export type GatewayAgentAvatarStates = {
  default: string;
  states: Record<string, GatewayAgentAvatarStateEntry>;
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
