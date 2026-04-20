import { describe, expect, it } from "vitest";
import {
  validateCharacterManifest,
  validateNodeGetCharacterManifestParams,
  validateNodeGetCharacterManifestResult,
} from "../index.js";

const minimalManifest = {
  version: 1 as const,
  agentId: "ginger",
  modes: ["headshot"],
  stateMap: { Idle: "neutral", Speaking: "happy" },
  content: {
    headshot: {
      animations: {
        neutral: {
          sequence: {
            frames: [{ ref: "neutral.gif" }],
            fps: 12,
            loop: "infinite",
          },
        },
        happy: {
          sequence: {
            frames: [{ ref: "happy.gif" }],
            fps: 24,
            loop: "ping-pong",
            holdLastFrame: true,
          },
        },
      },
    },
  },
  assets: { refs: { "neutral.gif": "avatars/ginger/neutral.gif" } },
};

describe("CharacterManifest schema", () => {
  it("accepts a minimal headshot manifest", () => {
    expect(validateCharacterManifest(minimalManifest)).toBe(true);
  });

  it("accepts a phased animation with intro/loop/outro", () => {
    const manifest = {
      ...minimalManifest,
      content: {
        headshot: {
          animations: {
            thinking: {
              intro: {
                frames: [{ ref: "a" }],
                fps: 24,
                loop: "once",
              },
              loop: {
                frames: [{ ref: "b" }],
                fps: 12,
                loop: "infinite",
              },
              outro: {
                frames: [{ ref: "c" }],
                fps: 24,
                loop: "once",
              },
            },
          },
        },
      },
      assets: { refs: { a: "p/a", b: "p/b", c: "p/c" } },
    };
    expect(validateCharacterManifest(manifest)).toBe(true);
  });

  it("accepts an atlas mode with transitions", () => {
    const manifest = {
      ...minimalManifest,
      content: {
        headshot: {
          atlas: {
            image: "atlas.webp",
            size: { w: 1024, h: 1024 },
            frameSize: { w: 256, h: 256 },
          },
          animations: {
            neutral: {
              sequence: {
                frames: [{ ref: "atlas.webp", x: 0, y: 0, w: 256, h: 256 }],
                fps: 12,
                loop: "infinite",
              },
            },
          },
          transitions: {
            "*->neutral": "neutral.intro",
            "*->happy": { blend: "crossfade", ms: 150 },
          },
        },
      },
      assets: { refs: { "atlas.webp": "avatars/ginger/atlas.webp" } },
    };
    expect(validateCharacterManifest(manifest)).toBe(true);
  });

  it("rejects an unknown loop mode", () => {
    const bad = {
      ...minimalManifest,
      content: {
        headshot: {
          animations: {
            neutral: {
              sequence: { frames: [{ ref: "x" }], fps: 12, loop: "bounce" },
            },
          },
        },
      },
    };
    expect(validateCharacterManifest(bad)).toBe(false);
  });

  it("rejects fps outside the allowed range", () => {
    const bad = {
      ...minimalManifest,
      content: {
        headshot: {
          animations: {
            neutral: {
              sequence: { frames: [{ ref: "x" }], fps: 999, loop: "infinite" },
            },
          },
        },
      },
    };
    expect(validateCharacterManifest(bad)).toBe(false);
  });

  it("rejects unknown top-level properties", () => {
    expect(validateCharacterManifest({ ...minimalManifest, extra: true })).toBe(false);
  });
});

describe("NodeGetCharacterManifestParams schema", () => {
  it("accepts agentId alone", () => {
    expect(validateNodeGetCharacterManifestParams({ agentId: "ginger" })).toBe(true);
  });

  it("accepts agentId + modes filter", () => {
    expect(
      validateNodeGetCharacterManifestParams({
        agentId: "ginger",
        modes: ["headshot", "fullbody"],
      }),
    ).toBe(true);
  });

  it("rejects empty agentId", () => {
    expect(validateNodeGetCharacterManifestParams({ agentId: "" })).toBe(false);
  });

  it("rejects empty modes array", () => {
    expect(validateNodeGetCharacterManifestParams({ agentId: "g", modes: [] })).toBe(false);
  });
});

describe("NodeGetCharacterManifestResult schema", () => {
  it("accepts a manifest + revision", () => {
    expect(
      validateNodeGetCharacterManifestResult({
        manifest: minimalManifest,
        revision: 0,
      }),
    ).toBe(true);
  });

  it("rejects negative revision", () => {
    expect(
      validateNodeGetCharacterManifestResult({
        manifest: minimalManifest,
        revision: -1,
      }),
    ).toBe(false);
  });
});
