// Prompt-builder for atlas avatars. Owns the keyword vocabulary and the
// system-prompt block that teaches the model how to emit `<<<state>>>`
// markers. Pairs with the streaming marker parser on the gateway side
// (`src/gateway/avatar-marker-parser.ts`), which strips matching markers from
// the visible reply and surfaces state changes to clients.
//
// The instruction is injected only for sessions whose connected client
// advertises a sprite display capability; callers pass `runtimeCapabilities`
// from the plugin-hook context (see `index.ts#register` which wires this via
// `api.registerSystemPromptContribution`). Non-sprite clients (dashboard,
// Telegram, headless chat) never see the sprite vocabulary even when the
// plugin is installed.

import type {
  SpriteCoreAvatarAtlasConfig,
  SpriteCoreEmotionEntry,
  SpriteCorePromptingConfig,
} from "./types.js";

const STATE_NAME_RE = /^[a-zA-Z0-9_-]+$/;

// Wire-stable display caps the client advertises at pair time. Inlined to
// avoid importing the heavy protocol barrel from extension production code.
// Keep in sync with `src/gateway/protocol/schema/display.ts`.
const DISPLAY_CAP_SPRITE_HEADSHOT = "display:sprite-headshot";
const DISPLAY_CAP_SPRITE_FULLBODY = "display:sprite-fullbody";

export function isValidAvatarStateName(name: string): boolean {
  return typeof name === "string" && name.length > 0 && STATE_NAME_RE.test(name);
}

export function isAtlasAvatarConfig(value: unknown): value is SpriteCoreAvatarAtlasConfig {
  if (!value || typeof value !== "object") {
    return false;
  }
  const obj = value as Record<string, unknown>;
  return (
    obj.kind === "atlas" && typeof obj.default === "string" && typeof obj.manifest === "string"
  );
}

/** True when the client has any sprite-rendering display capability. */
export function hasSpriteDisplayCapability(
  runtimeCapabilities: readonly string[] | undefined,
): boolean {
  if (!runtimeCapabilities || runtimeCapabilities.length === 0) {
    return false;
  }
  for (const cap of runtimeCapabilities) {
    if (cap === DISPLAY_CAP_SPRITE_HEADSHOT || cap === DISPLAY_CAP_SPRITE_FULLBODY) {
      return true;
    }
  }
  return false;
}

export type BuildPromptingInstructionInput = {
  avatar: SpriteCoreAvatarAtlasConfig;
  prompting?: SpriteCorePromptingConfig;
  /**
   * Per-state emotion entries. Descriptions here are the source of truth for
   * the prompt block; `prompting.descriptions` is honored as a fallback only
   * for states whose emotion entry lacks one.
   */
  emotions?: Record<string, SpriteCoreEmotionEntry>;
};

/**
 * Build the system-prompt block that teaches the model the sprite marker
 * vocabulary, or return `null` when there is nothing to teach (no described
 * states available).
 *
 * When `prompting.instruction` is set, it overrides the auto-generated text
 * entirely (the operator vouches for the wording).
 */
export function buildPromptingInstruction(input: BuildPromptingInstructionInput): string | null {
  const override = input.prompting?.instruction?.trim();
  if (override) {
    return override;
  }
  const descriptions = resolveDescriptions(input);
  if (Object.keys(descriptions).length === 0) {
    return null;
  }
  return renderInstruction({
    defaultState: input.avatar.default,
    stateDescriptions: descriptions,
  });
}

function resolveDescriptions(input: BuildPromptingInstructionInput): Record<string, string> {
  const merged: Record<string, string> = {};
  // Legacy fallback: `prompting.descriptions` fills the picture for states
  // that have no emotion entry yet. Emotion entries take precedence below.
  const legacy = input.prompting?.descriptions;
  if (legacy && typeof legacy === "object") {
    for (const [name, desc] of Object.entries(legacy)) {
      if (typeof desc !== "string") {
        continue;
      }
      const trimmed = desc.trim();
      if (trimmed.length > 0) {
        merged[name] = trimmed;
      }
    }
  }
  if (input.emotions) {
    for (const [name, entry] of Object.entries(input.emotions)) {
      if (!entry || typeof entry !== "object") {
        continue;
      }
      const raw = entry.description;
      if (typeof raw !== "string") {
        continue;
      }
      const trimmed = raw.trim();
      if (trimmed.length > 0) {
        merged[name] = trimmed;
      }
    }
  }
  return merged;
}

function renderInstruction(params: {
  defaultState: string;
  stateDescriptions: Record<string, string>;
}): string {
  const lines: string[] = [];
  lines.push(
    "You control an on-screen sprite that can play different emotion animations while you speak.",
  );
  lines.push(
    "To change the emotion mid-reply, write the state name wrapped in triple angle brackets: `<<<happy>>>`, `<<<sad>>>`, `<<<thinking>>>`, etc.",
  );
  lines.push(
    "The marker is stripped from the visible text, and the sprite plays that emotion until the next marker. Markers may appear inline or on their own line.",
  );
  lines.push("");
  lines.push("Available states:");
  for (const [name, desc] of Object.entries(params.stateDescriptions)) {
    lines.push(`- <<<${name}>>> — ${desc}`);
  }
  lines.push("");
  lines.push(`Default state: ${params.defaultState}.`);
  lines.push(
    "Switch emotions multiple times per reply when it helps the tone land. Do not mention this marker system in your reply.",
  );
  return lines.join("\n");
}
