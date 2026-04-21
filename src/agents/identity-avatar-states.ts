// Atlas-only avatar helpers. The `-states` filename is historical; since
// `kind: "states"` and `kind: "sprites"` were retired from the gateway,
// this module only covers atlas config recognition + instruction synthesis.
import type { AgentAvatarAtlasConfig } from "../config/types.base.js";
import { isRecord } from "../utils.js";

const STATE_NAME_RE = /^[a-zA-Z0-9_-]+$/;

export function isValidAvatarStateName(name: string): boolean {
  return typeof name === "string" && name.length > 0 && STATE_NAME_RE.test(name);
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
