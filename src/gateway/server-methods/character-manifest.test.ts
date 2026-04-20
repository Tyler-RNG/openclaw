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

describe("buildCharacterManifest — kind:states (legacy GIF)", () => {
  it("synthesizes a headshot manifest with one-frame animations per state", async () => {
    const cfg = cfgWithAgent("ginger", {
      kind: "states",
      default: "neutral",
      states: {
        neutral: { file: "avatars/ginger/neutral.gif", description: "resting" },
        happy: { file: "avatars/ginger/happy.gif" },
      },
    });
    const result = await buildCharacterManifest({ cfg, agentId: "ginger" });
    expect(result.ok).toBe(true);
    if (!result.ok) {
      return;
    }

    expect(validateCharacterManifest(result.manifest)).toBe(true);
    expect(result.manifest.modes).toEqual(["headshot"]);
    expect(result.manifest.agentId).toBe("ginger");
    expect(result.manifest.stateMap).toEqual({ neutral: "neutral", happy: "happy" });

    const headshot = result.manifest.content.headshot;
    expect(Object.keys(headshot.animations)).toEqual(["neutral", "happy"]);
    expect(headshot.animations.neutral?.description).toBe("resting");
    expect(headshot.animations.neutral?.sequence?.frames).toEqual([{ ref: "neutral" }]);
    expect(result.manifest.assets.refs).toEqual({
      neutral: "avatars/ginger/neutral.gif",
      happy: "avatars/ginger/happy.gif",
    });
  });
});

describe("buildCharacterManifest — kind:sprites (per-frame images)", () => {
  it("expands flat state frame counts into per-frame refs + file paths", async () => {
    const cfg = cfgWithAgent("ginger", {
      kind: "sprites",
      default: "neutral",
      basePath: "avatars/ginger/frames",
      format: "webp",
      states: {
        neutral: { count: 3, fps: 12, loop: "infinite", description: "idle" },
      },
    });
    const result = await buildCharacterManifest({ cfg, agentId: "ginger" });
    expect(result.ok).toBe(true);
    if (!result.ok) {
      return;
    }

    const headshot = result.manifest.content.headshot;
    const seq = headshot.animations.neutral?.sequence;
    expect(seq?.frames.map((f) => f.ref)).toEqual(["neutral/00", "neutral/01", "neutral/02"]);
    expect(seq?.fps).toBe(12);
    expect(seq?.loop).toBe("infinite");
    expect(result.manifest.assets.refs).toMatchObject({
      "neutral/00": "avatars/ginger/frames/neutral/00.webp",
      "neutral/01": "avatars/ginger/frames/neutral/01.webp",
      "neutral/02": "avatars/ginger/frames/neutral/02.webp",
    });
  });

  it("expands a phased state into intro/loop/outro sequences", async () => {
    const cfg = cfgWithAgent("ginger", {
      kind: "sprites",
      default: "neutral",
      basePath: "avatars/ginger/frames",
      states: {
        thinking: {
          intro: { count: 2, fps: 24, loop: "once" },
          loop: { count: 2, fps: 12, loop: "infinite" },
          outro: { count: 1, fps: 24, loop: "once" },
          description: "processing",
        },
      },
    });
    const result = await buildCharacterManifest({ cfg, agentId: "ginger" });
    expect(result.ok).toBe(true);
    if (!result.ok) {
      return;
    }

    const thinking = result.manifest.content.headshot.animations.thinking;
    expect(thinking.description).toBe("processing");
    expect(thinking.intro?.frames.map((f) => f.ref)).toEqual([
      "thinking/intro/00",
      "thinking/intro/01",
    ]);
    expect(thinking.loop?.frames.map((f) => f.ref)).toEqual([
      "thinking/loop/00",
      "thinking/loop/01",
    ]);
    expect(thinking.outro?.frames.map((f) => f.ref)).toEqual(["thinking/outro/00"]);
    expect(result.manifest.assets.refs["thinking/intro/01"]).toBe(
      "avatars/ginger/frames/thinking/intro/01.webp",
    );
  });

  it("carries transitions through untouched", async () => {
    const cfg = cfgWithAgent("ginger", {
      kind: "sprites",
      default: "neutral",
      basePath: "avatars/ginger/frames",
      states: { neutral: { count: 1, fps: 12, loop: "infinite" } },
      transitions: {
        "*->thinking": "thinking.intro",
        "*->happy": { blend: "crossfade", ms: 150 },
      },
    });
    const result = await buildCharacterManifest({ cfg, agentId: "ginger" });
    expect(result.ok).toBe(true);
    if (!result.ok) {
      return;
    }
    expect(result.manifest.content.headshot?.transitions).toEqual({
      "*->thinking": "thinking.intro",
      "*->happy": { blend: "crossfade", ms: 150 },
    });
  });

  it("uses 3-digit frame indexes when count >= 100", async () => {
    const cfg = cfgWithAgent("ginger", {
      kind: "sprites",
      default: "neutral",
      basePath: "avatars/ginger/frames",
      states: { neutral: { count: 120, fps: 30, loop: "infinite" } },
    });
    const result = await buildCharacterManifest({ cfg, agentId: "ginger" });
    expect(result.ok).toBe(true);
    if (!result.ok) {
      return;
    }
    const refs =
      result.manifest.content.headshot.animations.neutral?.sequence?.frames.map((f) => f.ref) ?? [];
    expect(refs[0]).toBe("neutral/000");
    expect(refs[119]).toBe("neutral/119");
  });
});

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
  const cfg = cfgWithAgent("ginger", {
    kind: "states",
    default: "neutral",
    states: { neutral: { file: "a.gif" } },
  });

  it("returns the headshot mode when the operator advertises no display caps", async () => {
    const result = await buildCharacterManifest({ cfg, agentId: "ginger" });
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
      kind: "states",
      default: "n",
      states: { n: { file: "a" } },
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
  const avatar = {
    kind: "states",
    default: "neutral",
    states: { neutral: { file: "a.gif" }, happy: { file: "b.gif" } },
  };

  it("returns a stable revision for identical configs", async () => {
    const cfgA = cfgWithAgent("ginger", avatar);
    const cfgB = cfgWithAgent("ginger", avatar);
    const a = await buildCharacterManifest({ cfg: cfgA, agentId: "ginger" });
    const b = await buildCharacterManifest({ cfg: cfgB, agentId: "ginger" });
    expect(a.ok && b.ok).toBe(true);
    if (!a.ok || !b.ok) {
      return;
    }
    expect(a.revision).toBe(b.revision);
  });

  it("changes revision when the avatar config changes", async () => {
    const a = await buildCharacterManifest({
      cfg: cfgWithAgent("ginger", avatar),
      agentId: "ginger",
    });
    const b = await buildCharacterManifest({
      cfg: cfgWithAgent("ginger", { ...avatar, states: { neutral: { file: "c.gif" } } }),
      agentId: "ginger",
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
    });
    expect(result.ok).toBe(true);
    if (!result.ok) {
      return;
    }
    expect(Number.isInteger(result.revision)).toBe(true);
    expect(result.revision).toBeGreaterThanOrEqual(0);
  });
});
