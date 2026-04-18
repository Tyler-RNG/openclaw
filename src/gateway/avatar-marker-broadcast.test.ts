import { describe, expect, it } from "vitest";
import type { OpenClawConfig } from "../config/types.openclaw.js";
import type { AgentEventPayload } from "../infra/agent-events.js";
import { createAvatarMarkerBroadcast } from "./avatar-marker-broadcast.js";

function makeConfig(): OpenClawConfig {
  return {
    agents: {
      list: [
        {
          id: "ginger",
          identity: {
            avatar: {
              kind: "states",
              default: "neutral",
              states: {
                neutral: { file: "avatars/ginger/neutral.gif", description: "resting" },
                happy: { file: "avatars/ginger/smile.gif", description: "warm" },
                sad: { file: "avatars/ginger/frown.gif", description: "sympathy" },
              },
            },
          },
        },
        {
          // Legacy static-string avatar — hook should pass-through untouched.
          id: "plain",
          identity: { avatar: "avatars/plain.png" },
        },
      ],
    },
  } as OpenClawConfig;
}

function makeAssistantEvent(
  runId: string,
  text: string,
  delta: string,
  sessionKey: string,
): AgentEventPayload {
  return {
    runId,
    seq: 1,
    stream: "assistant",
    ts: Date.now(),
    sessionKey,
    data: { text, delta },
  };
}

describe("avatar-marker-broadcast: gating", () => {
  it("passes through when the agent is not states-configured", () => {
    const broadcast = createAvatarMarkerBroadcast({ getConfig: () => makeConfig() });
    const evt = makeAssistantEvent("r1", "[avatar:happy]\nhi", "[avatar:happy]\nhi", "agent:plain:main");
    const result = broadcast.process(evt, { sessionKey: "agent:plain:main" });
    expect(result.event).toBeNull();
    expect(result.events).toEqual([]);
  });

  it("passes through non-assistant events", () => {
    const broadcast = createAvatarMarkerBroadcast({ getConfig: () => makeConfig() });
    const evt: AgentEventPayload = {
      runId: "r1",
      seq: 1,
      stream: "tool",
      ts: Date.now(),
      sessionKey: "agent:ginger:main",
      data: { delta: "[avatar:happy]\n" },
    };
    const result = broadcast.process(evt, { sessionKey: "agent:ginger:main" });
    expect(result.event).toBeNull();
    expect(result.events).toEqual([]);
  });

  it("passes through assistant events without markers", () => {
    const broadcast = createAvatarMarkerBroadcast({ getConfig: () => makeConfig() });
    const evt = makeAssistantEvent("r1", "hello world", "hello world", "agent:ginger:main");
    const result = broadcast.process(evt, { sessionKey: "agent:ginger:main" });
    expect(result.event).toBeNull();
    expect(result.events).toEqual([]);
  });
});

describe("avatar-marker-broadcast: stripping + event emission", () => {
  it("strips markers and emits one event per new marker", () => {
    const broadcast = createAvatarMarkerBroadcast({ getConfig: () => makeConfig() });
    const evt = makeAssistantEvent(
      "r1",
      "[avatar:happy]\nHi there!\n",
      "[avatar:happy]\nHi there!\n",
      "agent:ginger:main",
    );
    const result = broadcast.process(evt, { sessionKey: "agent:ginger:main" });
    expect(result.event).not.toBeNull();
    expect(result.event?.data.text).toBe("Hi there!\n");
    expect(result.event?.data.delta).toBe("Hi there!\n");
    expect(result.events).toHaveLength(1);
    expect(result.events[0]).toMatchObject({
      runId: "r1",
      agentId: "ginger",
      state: "happy",
      file: "avatars/ginger/smile.gif",
      sessionKey: "agent:ginger:main",
    });
  });

  it("does not re-emit markers that were in a previous cumulative text", () => {
    const broadcast = createAvatarMarkerBroadcast({ getConfig: () => makeConfig() });
    // First push: cumulative text has one marker; emit it.
    const r1 = broadcast.process(
      makeAssistantEvent(
        "r1",
        "[avatar:happy]\nHi!\n",
        "[avatar:happy]\nHi!\n",
        "agent:ginger:main",
      ),
      { sessionKey: "agent:ginger:main" },
    );
    expect(r1.events.map((e) => e.state)).toEqual(["happy"]);
    // Second push: cumulative text still has the same marker; don't re-emit.
    const r2 = broadcast.process(
      makeAssistantEvent(
        "r1",
        "[avatar:happy]\nHi! More text.\n",
        " More text.\n",
        "agent:ginger:main",
      ),
      { sessionKey: "agent:ginger:main" },
    );
    expect(r2.events).toEqual([]);
    expect(r2.event?.data.text).toBe("Hi! More text.\n");
  });

  it("emits subsequent markers as they appear", () => {
    const broadcast = createAvatarMarkerBroadcast({ getConfig: () => makeConfig() });
    broadcast.process(
      makeAssistantEvent("r1", "[avatar:happy]\nA\n", "[avatar:happy]\nA\n", "agent:ginger:main"),
      { sessionKey: "agent:ginger:main" },
    );
    const r2 = broadcast.process(
      makeAssistantEvent(
        "r1",
        "[avatar:happy]\nA\n[avatar:sad]\nB\n",
        "[avatar:sad]\nB\n",
        "agent:ginger:main",
      ),
      { sessionKey: "agent:ginger:main" },
    );
    expect(r2.events.map((e) => e.state)).toEqual(["sad"]);
    expect(r2.event?.data.text).toBe("A\nB\n");
  });

  it("silently ignores markers for states not present in the config", () => {
    const broadcast = createAvatarMarkerBroadcast({ getConfig: () => makeConfig() });
    const result = broadcast.process(
      makeAssistantEvent(
        "r1",
        "[avatar:nonexistent]\nHi\n",
        "[avatar:nonexistent]\nHi\n",
        "agent:ginger:main",
      ),
      { sessionKey: "agent:ginger:main" },
    );
    // Marker stripped from visible text even though the state is unknown.
    expect(result.event?.data.text).toBe("Hi\n");
    // No event emitted for unknown state.
    expect(result.events).toEqual([]);
  });
});

describe("avatar-marker-broadcast: per-run cleanup", () => {
  it("clearRun resets the emitted-marker counter", () => {
    const broadcast = createAvatarMarkerBroadcast({ getConfig: () => makeConfig() });
    broadcast.process(
      makeAssistantEvent("r1", "[avatar:happy]\nA\n", "[avatar:happy]\nA\n", "agent:ginger:main"),
      { sessionKey: "agent:ginger:main" },
    );
    broadcast.clearRun("r1");
    // After clear, the same cumulative text re-emits the marker because we've
    // lost track of how many we'd already seen — this is correct for a brand
    // new run, and tests that state is actually cleared.
    const r2 = broadcast.process(
      makeAssistantEvent("r1", "[avatar:happy]\nA\n", "[avatar:happy]\nA\n", "agent:ginger:main"),
      { sessionKey: "agent:ginger:main" },
    );
    expect(r2.events.map((e) => e.state)).toEqual(["happy"]);
  });
});
