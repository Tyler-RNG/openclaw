import type {
  AgentAvatarAtlasConfig,
  AgentAvatarSpriteState,
  AgentAvatarSpritesConfig,
  AgentAvatarStateEntry,
  AgentAvatarStatesConfig,
} from "../config/types.base.js";
import { isRecord } from "../utils.js";

export type {
  AgentAvatarAtlasConfig,
  AgentAvatarSpriteState,
  AgentAvatarSpritesConfig,
  AgentAvatarStateEntry,
  AgentAvatarStatesConfig,
};

const STATE_NAME_RE = /^[a-zA-Z0-9_-]+$/;

export function isValidAvatarStateName(name: string): boolean {
  return typeof name === "string" && name.length > 0 && STATE_NAME_RE.test(name);
}

function isAvatarStateEntry(value: unknown): value is AgentAvatarStateEntry {
  if (!isRecord(value)) {
    return false;
  }
  if (typeof value.file !== "string" || value.file.trim().length === 0) {
    return false;
  }
  if (value.description !== undefined && typeof value.description !== "string") {
    return false;
  }
  return true;
}

export function isAgentAvatarStatesConfig(value: unknown): value is AgentAvatarStatesConfig {
  if (!isRecord(value)) {
    return false;
  }
  if (value.kind !== "states") {
    return false;
  }
  if (typeof value.default !== "string" || !isValidAvatarStateName(value.default)) {
    return false;
  }
  if (!isRecord(value.states)) {
    return false;
  }
  const entries = Object.entries(value.states);
  if (entries.length === 0) {
    return false;
  }
  for (const [name, entry] of entries) {
    if (!isValidAvatarStateName(name)) {
      return false;
    }
    if (!isAvatarStateEntry(entry)) {
      return false;
    }
  }
  if (!(value.default in value.states)) {
    return false;
  }
  if (value.instruction !== undefined && typeof value.instruction !== "string") {
    return false;
  }
  return true;
}

/**
 * Build the instruction text the client should inject as a system message when
 * starting a new session with a state-aware agent. Uses `config.instruction`
 * verbatim if set; otherwise auto-generates from state descriptions.
 */
export function buildAvatarStateInstruction(cfg: AgentAvatarStatesConfig): string {
  const override = cfg.instruction?.trim();
  if (override) {
    return override;
  }
  return buildInstructionFromNames({
    defaultState: cfg.default,
    stateDescriptions: Object.fromEntries(
      Object.entries(cfg.states).map(([name, entry]) => [name, entry.description?.trim() ?? ""]),
    ),
  });
}

export function isAgentAvatarSpritesConfig(value: unknown): value is AgentAvatarSpritesConfig {
  if (!isRecord(value)) {
    return false;
  }
  if (value.kind !== "sprites") {
    return false;
  }
  if (typeof value.default !== "string") {
    return false;
  }
  if (typeof value.basePath !== "string") {
    return false;
  }
  if (!isRecord(value.states)) {
    return false;
  }
  return true;
}

export function isAgentAvatarAtlasConfig(value: unknown): value is AgentAvatarAtlasConfig {
  if (!isRecord(value)) {
    return false;
  }
  if (value.kind !== "atlas") {
    return false;
  }
  if (typeof value.default !== "string") {
    return false;
  }
  if (typeof value.manifest !== "string") {
    return false;
  }
  return true;
}

export function buildAvatarSpritesInstruction(cfg: AgentAvatarSpritesConfig): string {
  const override = cfg.instruction?.trim();
  if (override) {
    return override;
  }
  const descriptions: Record<string, string> = {};
  for (const [name, entry] of Object.entries(cfg.states)) {
    if (!isRecord(entry)) {
      continue;
    }
    const desc = typeof entry.description === "string" ? entry.description.trim() : "";
    descriptions[name] = desc;
  }
  return buildInstructionFromNames({
    defaultState: cfg.default,
    stateDescriptions: descriptions,
  });
}

export function buildAvatarAtlasInstruction(cfg: AgentAvatarAtlasConfig): string {
  const override = cfg.instruction?.trim();
  if (override) {
    return override;
  }
  const descriptions: Record<string, string> = {};
  if (isRecord(cfg.descriptions)) {
    for (const [name, desc] of Object.entries(cfg.descriptions)) {
      if (typeof desc === "string") {
        descriptions[name] = desc.trim();
      }
    }
  }
  return buildInstructionFromNames({
    defaultState: cfg.default,
    stateDescriptions: descriptions,
  });
}

function buildInstructionFromNames(params: {
  defaultState: string;
  stateDescriptions: Record<string, string>;
}): string {
  const lines: string[] = [];
  lines.push(
    "You can change your avatar expression during a reply by writing a marker on its own line.",
  );
  lines.push("Marker format: `[avatar:<state>]` — the line must contain nothing else.");
  lines.push("The marker is stripped from the visible reply; use it to convey tone as you speak.");
  lines.push("");
  lines.push("Available states:");
  for (const [name, desc] of Object.entries(params.stateDescriptions)) {
    lines.push(desc ? `- ${name}: ${desc}` : `- ${name}`);
  }
  lines.push("");
  lines.push(`Default state: ${params.defaultState}.`);
  lines.push(
    "Switch states multiple times per reply when it helps the tone land. Do not mention this marker system in your reply.",
  );
  return lines.join("\n");
}
