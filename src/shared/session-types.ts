export type GatewayAgentAvatarStateEntry = {
  file: string;
  description?: string;
};

export type GatewayAgentAvatarStates = {
  default: string;
  states: Record<string, GatewayAgentAvatarStateEntry>;
  instruction: string;
};

/** Agent identity fields returned by gateway session listing APIs. */
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

/** Model summary returned for an agent/session row. */
export type GatewayAgentModel = {
  primary?: string;
  fallbacks?: string[];
};

/** Runtime selection metadata for an agent row. */
export type GatewayAgentRuntime = {
  id: string;
  fallback?: "openclaw" | "none";
  source: "env" | "agent" | "defaults" | "model" | "provider" | "implicit" | "session-key";
};

/** Thinking-level option exposed to UI clients. */
export type GatewayThinkingLevelOption = {
  id: string;
  label: string;
};

/** Common agent row shape used by session list responses. */
export type GatewayAgentRow = {
  id: string;
  name?: string;
  identity?: GatewayAgentIdentity;
  workspace?: string;
  model?: GatewayAgentModel;
  agentRuntime?: GatewayAgentRuntime;
  thinkingLevels?: GatewayThinkingLevelOption[];
  thinkingOptions?: string[];
  thinkingDefault?: string;
};

/** Generic base for paged session-list responses. */
export type SessionsListResultBase<TDefaults, TRow> = {
  ts: number;
  path: string;
  count: number;
  totalCount?: number;
  limitApplied?: number;
  offset?: number;
  nextOffset?: number | null;
  hasMore?: boolean;
  defaults: TDefaults;
  sessions: TRow[];
};

/** Generic base for successful session patch responses. */
export type SessionsPatchResultBase<TEntry> = {
  ok: true;
  path: string;
  key: string;
  entry: TEntry;
};
