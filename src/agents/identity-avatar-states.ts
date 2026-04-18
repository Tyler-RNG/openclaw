import type {
  AgentAvatarStateEntry,
  AgentAvatarStatesConfig,
} from "../config/types.base.js";
import { isRecord } from "../utils.js";

export type { AgentAvatarStateEntry, AgentAvatarStatesConfig };

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

export function isAgentAvatarStatesConfig(
  value: unknown,
): value is AgentAvatarStatesConfig {
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
  const entries = Object.entries(value.states as Record<string, unknown>);
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
  if (!(value.default in (value.states as Record<string, unknown>))) {
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
  const lines: string[] = [];
  lines.push(
    "You can change your avatar expression during a reply by writing a marker on its own line.",
  );
  lines.push(
    "Marker format: `[avatar:<state>]` — the line must contain nothing else.",
  );
  lines.push(
    "The marker is stripped from the visible reply; use it to convey tone as you speak.",
  );
  lines.push("");
  lines.push("Available states:");
  for (const [name, entry] of Object.entries(cfg.states)) {
    const desc = entry.description?.trim() ?? "";
    lines.push(desc ? `- ${name}: ${desc}` : `- ${name}`);
  }
  lines.push("");
  lines.push(`Default state: ${cfg.default}.`);
  lines.push(
    "Switch states multiple times per reply when it helps the tone land. Do not mention this marker system in your reply.",
  );
  return lines.join("\n");
}
