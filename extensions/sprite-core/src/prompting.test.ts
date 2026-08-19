import { describe, expect, it } from "vitest";
import {
  buildPromptingInstruction,
  hasSpriteDisplayCapability,
  isAtlasAvatarConfig,
  isValidAvatarStateName,
} from "./prompting.js";
import type { SpriteCoreAvatarAtlasConfig } from "./types.js";

const baseAvatar: SpriteCoreAvatarAtlasConfig = {
  kind: "atlas",
  default: "idle",
  manifest: "avatars/agent/agent.atlas.json",
};

describe("isValidAvatarStateName", () => {
  it("accepts plain state names", () => {
    expect(isValidAvatarStateName("idle")).toBe(true);
    expect(isValidAvatarStateName("thinking")).toBe(true);
    expect(isValidAvatarStateName("state-1_v2")).toBe(true);
  });

  it("rejects empty / non-string / disallowed-character names", () => {
    expect(isValidAvatarStateName("")).toBe(false);
    expect(isValidAvatarStateName("has space")).toBe(false);
    expect(isValidAvatarStateName("has.dot")).toBe(false);
    expect(isValidAvatarStateName(null as unknown as string)).toBe(false);
  });
});

describe("isAtlasAvatarConfig", () => {
  it("recognizes a well-formed atlas config", () => {
    expect(isAtlasAvatarConfig(baseAvatar)).toBe(true);
  });

  it("rejects strings, nulls, and other kinds", () => {
    expect(isAtlasAvatarConfig("avatars/agent.png")).toBe(false);
    expect(isAtlasAvatarConfig(null)).toBe(false);
    expect(isAtlasAvatarConfig({ kind: "states" })).toBe(false);
    expect(isAtlasAvatarConfig({ kind: "atlas", default: "idle" })).toBe(false);
  });
});

describe("hasSpriteDisplayCapability", () => {
  it("returns true when the sprite-headshot cap is present", () => {
    expect(hasSpriteDisplayCapability(["display:sprite-headshot"])).toBe(true);
    expect(hasSpriteDisplayCapability(["display:tts", "display:sprite-headshot"])).toBe(true);
  });
  it("returns true when the sprite-fullbody cap is present", () => {
    expect(hasSpriteDisplayCapability(["display:sprite-fullbody"])).toBe(true);
  });
  it("returns false when no sprite cap is advertised", () => {
    expect(hasSpriteDisplayCapability(undefined)).toBe(false);
    expect(hasSpriteDisplayCapability([])).toBe(false);
    expect(hasSpriteDisplayCapability(["display:text", "display:tts"])).toBe(false);
  });
});

describe("buildPromptingInstruction", () => {
  it("renders the <<<state>>> vocabulary with one bullet per emotion", () => {
    const out = buildPromptingInstruction({
      avatar: baseAvatar,
      emotions: {
        idle: { description: "calm / listening" },
        thinking: { description: "processing" },
        happy: { description: "warm" },
        sad: { description: "sympathy" },
      },
    });
    expect(out).not.toBeNull();
    expect(out).toContain("<<<happy>>>");
    expect(out).toContain("- <<<idle>>> — calm / listening");
    expect(out).toContain("- <<<thinking>>> — processing");
    expect(out).toContain("- <<<happy>>> — warm");
    expect(out).toContain("- <<<sad>>> — sympathy");
    expect(out).toContain("Default state: idle.");
  });

  it("falls back to legacy prompting.descriptions when emotions is absent", () => {
    const out = buildPromptingInstruction({
      avatar: baseAvatar,
      prompting: { descriptions: { idle: "calm", happy: "warm" } },
    });
    expect(out).toContain("- <<<idle>>> — calm");
    expect(out).toContain("- <<<happy>>> — warm");
  });

  it("prefers emotions[state].description over prompting.descriptions for the same state", () => {
    const out = buildPromptingInstruction({
      avatar: baseAvatar,
      prompting: { descriptions: { happy: "legacy wording" } },
      emotions: { happy: { description: "new wording" } },
    });
    expect(out).toContain("- <<<happy>>> — new wording");
    expect(out).not.toContain("legacy wording");
  });

  it("returns the explicit override verbatim when prompting.instruction is set", () => {
    const out = buildPromptingInstruction({
      avatar: baseAvatar,
      prompting: { instruction: "  custom instruction  " },
    });
    expect(out).toBe("custom instruction");
  });

  it("returns null when no described states are available", () => {
    expect(buildPromptingInstruction({ avatar: baseAvatar })).toBeNull();
    expect(
      buildPromptingInstruction({
        avatar: baseAvatar,
        prompting: { descriptions: { happy: "  " } },
      }),
    ).toBeNull();
    expect(
      buildPromptingInstruction({
        avatar: baseAvatar,
        emotions: { happy: { description: "" } },
      }),
    ).toBeNull();
  });
});
