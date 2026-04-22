import { describe, expect, test } from "vitest";
import type { OpenClawConfig } from "../config/types.openclaw.js";
import { listAgentsForGateway } from "./session-utils.js";

// Contract: every `AgentConfig` field intended to flow out to phone/watch
// clients MUST round-trip through `listAgentsForGateway`. This test exists
// because we've had the same class of bug twice now — config schema accepts
// a new field (`voice`, `dataPlane`, etc.), the emitter silently drops it,
// downstream clients report "0 with voice" and nobody notices until TTS
// stops working. Every new publicly-surfaced agent field should add a case
// below; the list is the public contract.

describe("listAgentsForGateway — public agent-row contract", () => {
  test("carries per-agent identity (name, emoji, theme) through", () => {
    const cfg = {
      agents: {
        list: [
          {
            id: "ginger",
            name: "Ginger",
            identity: { name: "Ginger", emoji: "💕", theme: "#FF6B35" },
          },
        ],
      },
    } as OpenClawConfig;

    const { agents } = listAgentsForGateway(cfg);
    const ginger = agents.find((a) => a.id === "ginger");
    expect(ginger?.identity).toMatchObject({
      name: "Ginger",
      emoji: "💕",
      theme: "#FF6B35",
    });
  });

  test("avatar field on agent identity stays a string after the SpriteCore migration", () => {
    // The agent's identity.avatar is now string-only. Multi-state sprite
    // avatars + per-state prompting live in the SpriteCore plugin and are
    // delivered to clients via /sprite-core/agents and the
    // node.getCharacterManifest RPC, not via the gateway agent row.
    const cfg = {
      agents: {
        list: [
          {
            id: "ginger",
            identity: { avatar: "avatars/ginger.png" },
          },
        ],
      },
    } as OpenClawConfig;

    const { agents } = listAgentsForGateway(cfg);
    const ginger = agents.find((a) => a.id === "ginger");
    expect(ginger?.identity?.avatar).toBe("avatars/ginger.png");
  });

  test("carries per-agent model override through", () => {
    const cfg = {
      agents: {
        list: [
          {
            id: "ginger",
            model: {
              primary: "openrouter/xiaomi/mimo-v2-flash",
              fallbacks: ["openrouter/auto"],
            },
          },
        ],
      },
    } as OpenClawConfig;

    const { agents } = listAgentsForGateway(cfg);
    const ginger = agents.find((a) => a.id === "ginger");
    expect(ginger?.model).toEqual({
      primary: "openrouter/xiaomi/mimo-v2-flash",
      fallbacks: ["openrouter/auto"],
    });
  });

  test("does not carry per-agent voice on the core row (plugin owns voice now)", () => {
    // Voice moved end-to-end to the SpriteCore plugin. Clients (phone, watch)
    // fetch per-agent voice from GET /sprite-core/agents instead of reading
    // it off the gateway agent row. The core agent row has no voice field at
    // the type level and must not accidentally surface one at runtime either.
    const cfg = {
      agents: { list: [{ id: "bare" }] },
    } as OpenClawConfig;

    const { agents } = listAgentsForGateway(cfg);
    expect("voice" in (agents[0] ?? {})).toBe(false);
  });
});
