import { describe, expect, it } from "vitest";
import type { OpenClawConfig } from "../../config/types.openclaw.js";
import { validateCharacterManifest } from "../protocol/index.js";
import { buildCharacterManifest } from "./character-manifest.js";

function cfgWithAgent(agentId: string, avatar: unknown, name?: string): OpenClawConfig {
  return {
    agents: {
      list: [
        {
          id: agentId,
          identity: {
            ...(name ? { name } : {}),
            avatar,
          },
        },
      ],
    },
  } as unknown as OpenClawConfig;
}

describe("buildCharacterManifest — kind:atlas", () => {
  const atlasJson = {
    version: 1,
    agent: "ginger",
    image: "ginger.atlas.webp",
    size: { w: 1024, h: 1024 },
    frameSize: { w: 256, h: 256 },
    frames: {
      "neutral/00": { x: 0, y: 0, w: 256, h: 256 },
      "neutral/01": { x: 256, y: 0, w: 256, h: 256 },
      "thinking.intro/00": { x: 0, y: 256, w: 256, h: 256 },
      "thinking.loop/00": { x: 0, y: 512, w: 256, h: 256 },
    },
    animations: {
      neutral: { frames: ["neutral/00", "neutral/01"], fps: 12, loop: "infinite" },
      thinking: {
        intro: { frames: ["thinking.intro/00"], fps: 24, loop: "once" },
        loop: { frames: ["thinking.loop/00"], fps: 12, loop: "infinite" },
      },
    },
    transitions: { "*->thinking": "thinking.intro" },
  };

  it("inlines the atlas JSON into a headshot content block", async () => {
    const cfg = cfgWithAgent("ginger", {
      kind: "atlas",
      default: "neutral",
      manifest: "avatars/ginger/ginger.atlas.json",
    });
    const result = await buildCharacterManifest({
      cfg,
      agentId: "ginger",
      assetsDir: "/any/state/assets",
      readAtlasManifest: async () => atlasJson,
    });
    expect(result.ok).toBe(true);
    if (!result.ok) {
      return;
    }
    expect(validateCharacterManifest(result.manifest)).toBe(true);

    const headshot = result.manifest.content.headshot;
    expect(headshot.atlas).toEqual({
      image: "ginger.atlas.webp",
      size: { w: 1024, h: 1024 },
      frameSize: { w: 256, h: 256 },
    });
    expect(headshot.animations.neutral?.sequence?.frames).toEqual([
      { ref: "ginger.atlas.webp", x: 0, y: 0, w: 256, h: 256 },
      { ref: "ginger.atlas.webp", x: 256, y: 0, w: 256, h: 256 },
    ]);
    expect(headshot.animations.thinking?.intro?.frames?.[0]).toEqual({
      ref: "ginger.atlas.webp",
      x: 0,
      y: 256,
      w: 256,
      h: 256,
    });
    expect(result.manifest.assets.refs).toEqual({
      "ginger.atlas.webp": "avatars/ginger/ginger.atlas.webp",
    });
    expect(headshot.transitions).toEqual({ "*->thinking": "thinking.intro" });
  });

  it("returns atlas-unreadable when the manifest JSON is missing required fields", async () => {
    const cfg = cfgWithAgent("ginger", {
      kind: "atlas",
      default: "neutral",
      manifest: "avatars/ginger/ginger.atlas.json",
    });
    const result = await buildCharacterManifest({
      cfg,
      agentId: "ginger",
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
    const cfg = cfgWithAgent("ginger", {
      kind: "atlas",
      default: "neutral",
      manifest: "avatars/ginger/ginger.atlas.json",
    });
    const result = await buildCharacterManifest({
      cfg,
      agentId: "ginger",
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
    image: "ginger.atlas.webp",
    size: { w: 256, h: 256 },
    frames: { "neutral/00": { x: 0, y: 0, w: 256, h: 256 } },
    animations: {
      neutral: { frames: ["neutral/00"], fps: 12, loop: "infinite" },
    },
  };
  const cfg = cfgWithAgent("ginger", {
    kind: "atlas",
    default: "neutral",
    manifest: "avatars/ginger/ginger.atlas.json",
  });

  it("returns the headshot mode when the operator advertises no display caps", async () => {
    const result = await buildCharacterManifest({
      cfg,
      agentId: "ginger",
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
      cfg,
      agentId: "ginger",
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
      cfg,
      agentId: "ginger",
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
      cfg,
      agentId: "ginger",
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
  it("reports unknown-agent when the agent is missing", async () => {
    const cfg = cfgWithAgent("other", {
      kind: "atlas",
      default: "n",
      manifest: "x.json",
    });
    const result = await buildCharacterManifest({ cfg, agentId: "ginger" });
    expect(result.ok).toBe(false);
    if (result.ok) {
      return;
    }
    expect(result.code).toBe("unknown-agent");
  });

  it("reports no-avatar when the agent has only a plain string avatar", async () => {
    const cfg = cfgWithAgent("ginger", "avatars/ginger.png");
    const result = await buildCharacterManifest({ cfg, agentId: "ginger" });
    expect(result.ok).toBe(false);
    if (result.ok) {
      return;
    }
    expect(result.code).toBe("no-avatar");
  });

  it("reports no-avatar when the agent has no avatar at all", async () => {
    const cfg = cfgWithAgent("ginger", undefined);
    const result = await buildCharacterManifest({ cfg, agentId: "ginger" });
    expect(result.ok).toBe(false);
    if (result.ok) {
      return;
    }
    expect(result.code).toBe("no-avatar");
  });
});

describe("buildCharacterManifest — revision", () => {
  const atlasJson = {
    image: "ginger.atlas.webp",
    size: { w: 256, h: 256 },
    frames: {
      "neutral/00": { x: 0, y: 0, w: 256, h: 256 },
      "happy/00": { x: 0, y: 0, w: 256, h: 256 },
    },
    animations: {
      neutral: { frames: ["neutral/00"], fps: 12, loop: "infinite" },
      happy: { frames: ["happy/00"], fps: 12, loop: "infinite" },
    },
  };
  const avatar = {
    kind: "atlas",
    default: "neutral",
    manifest: "avatars/ginger/ginger.atlas.json",
  };

  it("returns a stable revision for identical configs", async () => {
    const cfgA = cfgWithAgent("ginger", avatar);
    const cfgB = cfgWithAgent("ginger", avatar);
    const a = await buildCharacterManifest({
      cfg: cfgA,
      agentId: "ginger",
      readAtlasManifest: async () => atlasJson,
    });
    const b = await buildCharacterManifest({
      cfg: cfgB,
      agentId: "ginger",
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
      cfg: cfgWithAgent("ginger", avatar),
      agentId: "ginger",
      readAtlasManifest: async () => atlasJson,
    });
    const b = await buildCharacterManifest({
      cfg: cfgWithAgent("ginger", avatar),
      agentId: "ginger",
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
      cfg: cfgWithAgent("ginger", avatar),
      agentId: "ginger",
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
