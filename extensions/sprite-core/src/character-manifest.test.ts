import { describe, expect, it } from "vitest";
import { buildCharacterManifest } from "./character-manifest.js";
import type { SpriteCoreConfig } from "./types.js";

function pluginCfgWithAgent(agentId: string, avatar: unknown): SpriteCoreConfig {
  return {
    assets: { enabled: true, assetsDir: "/any/state/assets" },
    agents: {
      [agentId]: {
        avatar: avatar as never,
      },
    },
  };
}

describe("buildCharacterManifest — kind:atlas", () => {
  const atlasJson = {
    version: 1,
    agent: "agent",
    image: "agent.atlas.webp",
    size: { w: 1024, h: 1024 },
    frameSize: { w: 256, h: 256 },
    frames: {
      "idle/00": { x: 0, y: 0, w: 256, h: 256 },
      "idle/01": { x: 256, y: 0, w: 256, h: 256 },
      "thinking.intro/00": { x: 0, y: 256, w: 256, h: 256 },
      "thinking.loop/00": { x: 0, y: 512, w: 256, h: 256 },
    },
    animations: {
      idle: { frames: ["idle/00", "idle/01"], fps: 12, loop: "infinite" },
      thinking: {
        intro: { frames: ["thinking.intro/00"], fps: 24, loop: "once" },
        loop: { frames: ["thinking.loop/00"], fps: 12, loop: "infinite" },
      },
    },
    transitions: { "*->thinking": "thinking.intro" },
  };

  it("inlines the atlas JSON into a headshot content block", async () => {
    const result = await buildCharacterManifest({
      pluginConfig: pluginCfgWithAgent("agent", {
        kind: "atlas",
        default: "idle",
        manifest: "avatars/agent/agent.atlas.json",
      }),
      agentId: "agent",
      assetsDir: "/any/state/assets",
      readAtlasManifest: async () => atlasJson,
    });
    expect(result.ok).toBe(true);
    if (!result.ok) {
      return;
    }

    const headshot = result.manifest.content.headshot;
    expect(headshot.atlas).toEqual({
      image: "agent.atlas.webp",
      size: { w: 1024, h: 1024 },
      frameSize: { w: 256, h: 256 },
    });
    expect(headshot.animations.idle?.sequence?.frames).toEqual([
      { ref: "agent.atlas.webp", x: 0, y: 0, w: 256, h: 256 },
      { ref: "agent.atlas.webp", x: 256, y: 0, w: 256, h: 256 },
    ]);
    expect(headshot.animations.thinking?.intro?.frames?.[0]).toEqual({
      ref: "agent.atlas.webp",
      x: 0,
      y: 256,
      w: 256,
      h: 256,
    });
    expect(result.manifest.assets.refs).toEqual({
      "agent.atlas.webp": "avatars/agent/agent.atlas.webp",
    });
    expect(headshot.transitions).toEqual({ "*->thinking": "thinking.intro" });
  });

  it("returns atlas-unreadable when the manifest JSON is missing required fields", async () => {
    const result = await buildCharacterManifest({
      pluginConfig: pluginCfgWithAgent("agent", {
        kind: "atlas",
        default: "idle",
        manifest: "avatars/agent/agent.atlas.json",
      }),
      agentId: "agent",
      assetsDir: "/any/state/assets",
      readAtlasManifest: async () => ({ image: "x.webp" }),
    });
    expect(result.ok).toBe(false);
    if (result.ok) {
      return;
    }
    expect(result.code).toBe("atlas-unreadable");
  });

  it("surfaces disk read failures as atlas-unreadable", async () => {
    const result = await buildCharacterManifest({
      pluginConfig: pluginCfgWithAgent("agent", {
        kind: "atlas",
        default: "idle",
        manifest: "avatars/agent/agent.atlas.json",
      }),
      agentId: "agent",
      assetsDir: "/any/state/assets",
      readAtlasManifest: async () => {
        throw new Error("ENOENT");
      },
    });
    expect(result.ok).toBe(false);
    if (result.ok) {
      return;
    }
    expect(result.code).toBe("atlas-unreadable");
  });
});

describe("buildCharacterManifest — filtering", () => {
  const atlasJson = {
    image: "agent.atlas.webp",
    size: { w: 256, h: 256 },
    frames: { "idle/00": { x: 0, y: 0, w: 256, h: 256 } },
    animations: {
      idle: { frames: ["idle/00"], fps: 12, loop: "infinite" },
    },
  };
  const cfg = pluginCfgWithAgent("agent", {
    kind: "atlas",
    default: "idle",
    manifest: "avatars/agent/agent.atlas.json",
  });

  it("returns the headshot mode when the operator advertises no display caps", async () => {
    const result = await buildCharacterManifest({
      pluginConfig: cfg,
      agentId: "agent",
      readAtlasManifest: async () => atlasJson,
    });
    expect(result.ok).toBe(true);
    if (!result.ok) {
      return;
    }
    expect(result.manifest.modes).toEqual(["headshot"]);
    expect(Object.keys(result.manifest.content)).toEqual(["headshot"]);
  });

  it("includes headshot when the client advertises display:sprite-headshot", async () => {
    const result = await buildCharacterManifest({
      pluginConfig: cfg,
      agentId: "agent",
      caps: ["display:sprite-headshot", "display:tts"],
      readAtlasManifest: async () => atlasJson,
    });
    expect(result.ok).toBe(true);
    if (!result.ok) {
      return;
    }
    expect(result.manifest.modes).toEqual(["headshot"]);
  });

  it("strips headshot when the client only advertises display:sprite-fullbody", async () => {
    const result = await buildCharacterManifest({
      pluginConfig: cfg,
      agentId: "agent",
      caps: ["display:sprite-fullbody"],
      readAtlasManifest: async () => atlasJson,
    });
    expect(result.ok).toBe(true);
    if (!result.ok) {
      return;
    }
    expect(result.manifest.modes).toEqual([]);
    expect(result.manifest.content).toEqual({});
    expect(result.manifest.assets.refs).toEqual({});
  });

  it("honors the request-side modes filter", async () => {
    const result = await buildCharacterManifest({
      pluginConfig: cfg,
      agentId: "agent",
      modes: ["fullbody"],
      readAtlasManifest: async () => atlasJson,
    });
    expect(result.ok).toBe(true);
    if (!result.ok) {
      return;
    }
    expect(result.manifest.modes).toEqual([]);
  });
});

describe("buildCharacterManifest — errors", () => {
  it("reports unknown-agent when the agent is missing from plugin config", async () => {
    const cfg = pluginCfgWithAgent("other", {
      kind: "atlas",
      default: "idle",
      manifest: "x.json",
    });
    const result = await buildCharacterManifest({ pluginConfig: cfg, agentId: "agent" });
    expect(result.ok).toBe(false);
    if (result.ok) {
      return;
    }
    expect(result.code).toBe("unknown-agent");
  });

  it("reports no-avatar when the agent has no avatar block", async () => {
    const cfg: SpriteCoreConfig = { agents: { agent: {} } };
    const result = await buildCharacterManifest({ pluginConfig: cfg, agentId: "agent" });
    expect(result.ok).toBe(false);
    if (result.ok) {
      return;
    }
    expect(result.code).toBe("no-avatar");
  });

  it("reports unsupported-kind for non-atlas avatar shapes", async () => {
    const cfg = pluginCfgWithAgent("agent", { kind: "states", default: "idle", states: {} });
    const result = await buildCharacterManifest({ pluginConfig: cfg, agentId: "agent" });
    expect(result.ok).toBe(false);
    if (result.ok) {
      return;
    }
    expect(result.code).toBe("unsupported-kind");
  });
});

describe("buildCharacterManifest — revision", () => {
  const atlasJson = {
    image: "agent.atlas.webp",
    size: { w: 256, h: 256 },
    frames: {
      "idle/00": { x: 0, y: 0, w: 256, h: 256 },
      "happy/00": { x: 0, y: 0, w: 256, h: 256 },
    },
    animations: {
      idle: { frames: ["idle/00"], fps: 12, loop: "infinite" },
      happy: { frames: ["happy/00"], fps: 12, loop: "infinite" },
    },
  };
  const avatar = {
    kind: "atlas",
    default: "idle",
    manifest: "avatars/agent/agent.atlas.json",
  };

  it("returns a stable revision for identical configs", async () => {
    const a = await buildCharacterManifest({
      pluginConfig: pluginCfgWithAgent("agent", avatar),
      agentId: "agent",
      readAtlasManifest: async () => atlasJson,
    });
    const b = await buildCharacterManifest({
      pluginConfig: pluginCfgWithAgent("agent", avatar),
      agentId: "agent",
      readAtlasManifest: async () => atlasJson,
    });
    expect(a.ok && b.ok).toBe(true);
    if (!a.ok || !b.ok) {
      return;
    }
    expect(a.revision).toBe(b.revision);
  });

  it("changes revision when the avatar content changes", async () => {
    const a = await buildCharacterManifest({
      pluginConfig: pluginCfgWithAgent("agent", avatar),
      agentId: "agent",
      readAtlasManifest: async () => atlasJson,
    });
    const b = await buildCharacterManifest({
      pluginConfig: pluginCfgWithAgent("agent", avatar),
      agentId: "agent",
      readAtlasManifest: async () => ({
        ...atlasJson,
        animations: {
          ...atlasJson.animations,
          happy: { frames: ["happy/00"], fps: 24, loop: "once" },
        },
      }),
    });
    expect(a.ok && b.ok).toBe(true);
    if (!a.ok || !b.ok) {
      return;
    }
    expect(a.revision).not.toBe(b.revision);
  });

  it("produces a non-negative integer revision", async () => {
    const result = await buildCharacterManifest({
      pluginConfig: pluginCfgWithAgent("agent", avatar),
      agentId: "agent",
      readAtlasManifest: async () => atlasJson,
    });
    expect(result.ok).toBe(true);
    if (!result.ok) {
      return;
    }
    expect(Number.isInteger(result.revision)).toBe(true);
    expect(result.revision).toBeGreaterThanOrEqual(0);
  });
});

describe("buildCharacterManifest — name pass-through", () => {
  const atlasJson = {
    image: "agent.atlas.webp",
    size: { w: 256, h: 256 },
    frames: { "idle/00": { x: 0, y: 0, w: 256, h: 256 } },
    animations: { idle: { frames: ["idle/00"], fps: 12, loop: "infinite" } },
  };

  it("emits the optional name field when the caller passes agentName", async () => {
    const result = await buildCharacterManifest({
      pluginConfig: pluginCfgWithAgent("agent", {
        kind: "atlas",
        default: "idle",
        manifest: "avatars/agent/agent.atlas.json",
      }),
      agentId: "agent",
      agentName: "OpenClaw",
      readAtlasManifest: async () => atlasJson,
    });
    expect(result.ok).toBe(true);
    if (!result.ok) {
      return;
    }
    expect(result.manifest.name).toBe("OpenClaw");
  });
});

describe("buildCharacterManifest — emotions", () => {
  const atlasJson = {
    image: "agent.atlas.webp",
    size: { w: 256, h: 256 },
    frames: { "idle/00": { x: 0, y: 0, w: 256, h: 256 } },
    animations: { idle: { frames: ["idle/00"], fps: 12, loop: "infinite" } },
  };

  type Emotions = NonNullable<NonNullable<SpriteCoreConfig["agents"]>[string]["emotions"]>;
  function cfgWithEmotions(emotions: Emotions): SpriteCoreConfig {
    return {
      assets: { enabled: true, assetsDir: "/any/state/assets" },
      agents: {
        agent: {
          avatar: {
            kind: "atlas",
            default: "idle",
            manifest: "avatars/agent/agent.atlas.json",
          },
          emotions,
        },
      },
    };
  }

  it("surfaces directive overrides on the wire and drops descriptions", async () => {
    const result = await buildCharacterManifest({
      pluginConfig: cfgWithEmotions({
        happy: {
          description: "warm and bright",
          directive: { style: 0.6, speakerBoost: true, voiceId: "vhappy" },
        },
        sad: {
          description: "soft and slower",
          directive: { stability: 0.85, speed: 0.9 },
        },
      }),
      agentId: "agent",
      readAtlasManifest: async () => atlasJson,
    });
    expect(result.ok).toBe(true);
    if (!result.ok) {
      return;
    }
    expect(result.manifest.emotions).toEqual({
      happy: { directive: { voiceId: "vhappy", style: 0.6, speakerBoost: true } },
      sad: { directive: { stability: 0.85, speed: 0.9 } },
    });
  });

  it("ships audioTag on the wire when configured", async () => {
    const result = await buildCharacterManifest({
      pluginConfig: cfgWithEmotions({
        happy: { description: "warm", directive: { audioTag: "[happy]" } },
        excited: {
          description: "keyed up",
          directive: { audioTag: "[excited]", style: 0.8 },
        },
        plain: { description: "no tag", directive: { stability: 0.5 } },
      }),
      agentId: "agent",
      readAtlasManifest: async () => atlasJson,
    });
    expect(result.ok).toBe(true);
    if (!result.ok) {
      return;
    }
    expect(result.manifest.emotions).toEqual({
      happy: { directive: { audioTag: "[happy]" } },
      excited: { directive: { audioTag: "[excited]", style: 0.8 } },
      plain: { directive: { stability: 0.5 } },
    });
  });

  it("ignores whitespace-only audioTag", async () => {
    const result = await buildCharacterManifest({
      pluginConfig: cfgWithEmotions({
        happy: { description: "warm", directive: { audioTag: "   ", style: 0.5 } },
      }),
      agentId: "agent",
      readAtlasManifest: async () => atlasJson,
    });
    expect(result.ok).toBe(true);
    if (!result.ok) {
      return;
    }
    expect(result.manifest.emotions).toEqual({
      happy: { directive: { style: 0.5 } },
    });
  });

  it("omits the field entirely when no emotion has a directive", async () => {
    const result = await buildCharacterManifest({
      pluginConfig: cfgWithEmotions({
        happy: { description: "warm" },
        sad: { description: "soft" },
      }),
      agentId: "agent",
      readAtlasManifest: async () => atlasJson,
    });
    expect(result.ok).toBe(true);
    if (!result.ok) {
      return;
    }
    expect(result.manifest).not.toHaveProperty("emotions");
  });

  it("sanitizes out-of-range or malformed directive fields", async () => {
    const result = await buildCharacterManifest({
      pluginConfig: cfgWithEmotions({
        happy: {
          description: "warm",
          directive: {
            stability: 1.5, // out of [0,1]
            style: "0.6" as unknown as number, // wrong type
            speakerBoost: true,
            voiceId: "   ", // whitespace-only
          },
        },
      }),
      agentId: "agent",
      readAtlasManifest: async () => atlasJson,
    });
    expect(result.ok).toBe(true);
    if (!result.ok) {
      return;
    }
    expect(result.manifest.emotions).toEqual({
      happy: { directive: { speakerBoost: true } },
    });
  });
});
