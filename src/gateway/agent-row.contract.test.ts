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
  test("carries per-agent voice through to the wire", () => {
    const cfg = {
      agents: {
        list: [
          {
            id: "ginger",
            voice: {
              provider: "elevenlabs",
              voiceId: "FGY2WhTYpPnrIDTdsKH5",
              label: "Laura",
            },
          },
        ],
      },
    } as OpenClawConfig;

    const { agents } = listAgentsForGateway(cfg);
    const ginger = agents.find((a) => a.id === "ginger");
    expect(ginger?.voice).toEqual({
      provider: "elevenlabs",
      voiceId: "FGY2WhTYpPnrIDTdsKH5",
      label: "Laura",
    });
  });

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

  test("carries avatar-states descriptor through for state-based agents", () => {
    const cfg = {
      agents: {
        list: [
          {
            id: "ginger",
            identity: {
              avatar: {
                kind: "states",
                default: "neutral",
                states: {
                  neutral: { file: "avatars/ginger/neutral.gif" },
                  thinking: { file: "avatars/ginger/thinking.gif" },
                },
              },
            },
          },
        ],
      },
    } as OpenClawConfig;

    const { agents } = listAgentsForGateway(cfg);
    const ginger = agents.find((a) => a.id === "ginger");
    expect(ginger?.identity?.avatarStates).toBeDefined();
    expect(ginger?.identity?.avatarStates?.default).toBe("neutral");
    expect(ginger?.identity?.avatarStates?.states.neutral?.file).toBe("avatars/ginger/neutral.gif");
    expect(ginger?.identity?.avatarStates?.states.thinking?.file).toBe(
      "avatars/ginger/thinking.gif",
    );
    expect(ginger?.identity?.avatarStates?.instruction).toBeTruthy();
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

  test("omits voice when not configured (no synthetic defaults)", () => {
    const cfg = {
      agents: { list: [{ id: "bare" }] },
    } as OpenClawConfig;

    const { agents } = listAgentsForGateway(cfg);
    expect(agents[0]?.voice).toBeUndefined();
  });
});
