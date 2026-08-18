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

function makeConfigWithThinking(): OpenClawConfig {
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
                neutral:  { file: "avatars/ginger/neutral.gif",  description: "resting" },
                thinking: { file: "avatars/ginger/think.gif",    description: "working" },
                happy:    { file: "avatars/ginger/smile.gif",    description: "warm" },
              },
            },
          },
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

function makeLifecycleEvent(
  runId: string,
  phase: "start" | "end",
  sessionKey: string,
): AgentEventPayload {
  return {
    runId,
    seq: 1,
    stream: "lifecycle",
    ts: Date.now(),
    sessionKey,
    data: { phase },
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

describe("avatar-marker-broadcast: gateway-driven auto-emit", () => {
  it("emits thinking on lifecycle.start when the agent has a thinking state", () => {
    const broadcast = createAvatarMarkerBroadcast({ getConfig: () => makeConfigWithThinking() });
    const r = broadcast.process(
      makeLifecycleEvent("r1", "start", "agent:ginger:main"),
      { sessionKey: "agent:ginger:main" },
    );
    expect(r.event).toBeNull();
    expect(r.events).toHaveLength(1);
    expect(r.events[0]).toMatchObject({
      runId: "r1",
      agentId: "ginger",
      state: "thinking",
      file: "avatars/ginger/think.gif",
    });
  });

  it("stays silent on lifecycle.start when the agent has no thinking state", () => {
    const broadcast = createAvatarMarkerBroadcast({ getConfig: () => makeConfig() });
    const r = broadcast.process(
      makeLifecycleEvent("r1", "start", "agent:ginger:main"),
      { sessionKey: "agent:ginger:main" },
    );
    expect(r.events).toEqual([]);
  });

  it("stays silent on lifecycle.start for non-states agents", () => {
    const broadcast = createAvatarMarkerBroadcast({ getConfig: () => makeConfigWithThinking() });
    const r = broadcast.process(
      makeLifecycleEvent("r1", "start", "agent:plain:main"),
      { sessionKey: "agent:plain:main" },
    );
    expect(r.events).toEqual([]);
  });

  it("emits default state on lifecycle.end after auto-thinking was emitted", () => {
    const broadcast = createAvatarMarkerBroadcast({ getConfig: () => makeConfigWithThinking() });
    broadcast.process(makeLifecycleEvent("r1", "start", "agent:ginger:main"), {
      sessionKey: "agent:ginger:main",
    });
    const r = broadcast.process(
      makeLifecycleEvent("r1", "end", "agent:ginger:main"),
      { sessionKey: "agent:ginger:main" },
    );
    expect(r.events).toHaveLength(1);
    expect(r.events[0]).toMatchObject({
      runId: "r1",
      state: "neutral",
      file: "avatars/ginger/neutral.gif",
    });
  });

  it("does not emit default on lifecycle.end if we never auto-emitted thinking", () => {
    const broadcast = createAvatarMarkerBroadcast({ getConfig: () => makeConfig() });
    const r = broadcast.process(
      makeLifecycleEvent("r1", "end", "agent:ginger:main"),
      { sessionKey: "agent:ginger:main" },
    );
    expect(r.events).toEqual([]);
  });

  it("does not emit default on end if the last emitted state was already default", () => {
    const broadcast = createAvatarMarkerBroadcast({ getConfig: () => makeConfigWithThinking() });
    // Simulate: thinking on start, then model emits [avatar:neutral] during reply,
    // then lifecycle.end — should not duplicate neutral.
    broadcast.process(makeLifecycleEvent("r1", "start", "agent:ginger:main"), {
      sessionKey: "agent:ginger:main",
    });
    broadcast.process(
      makeAssistantEvent(
        "r1",
        "[avatar:neutral]\nHello\n",
        "[avatar:neutral]\nHello\n",
        "agent:ginger:main",
      ),
      { sessionKey: "agent:ginger:main" },
    );
    const r = broadcast.process(
      makeLifecycleEvent("r1", "end", "agent:ginger:main"),
      { sessionKey: "agent:ginger:main" },
    );
    expect(r.events).toEqual([]);
  });

  it("still emits default on end when the model ended on a non-default tone state", () => {
    const broadcast = createAvatarMarkerBroadcast({ getConfig: () => makeConfigWithThinking() });
    broadcast.process(makeLifecycleEvent("r1", "start", "agent:ginger:main"), {
      sessionKey: "agent:ginger:main",
    });
    broadcast.process(
      makeAssistantEvent(
        "r1",
        "[avatar:happy]\nHi!\n",
        "[avatar:happy]\nHi!\n",
        "agent:ginger:main",
      ),
      { sessionKey: "agent:ginger:main" },
    );
    const r = broadcast.process(
      makeLifecycleEvent("r1", "end", "agent:ginger:main"),
      { sessionKey: "agent:ginger:main" },
    );
    // Last state was happy → end should reset to neutral.
    expect(r.events).toHaveLength(1);
    expect(r.events[0]?.state).toBe("neutral");
  });

  it("only auto-emits thinking once per run even if lifecycle.start fires twice", () => {
    const broadcast = createAvatarMarkerBroadcast({ getConfig: () => makeConfigWithThinking() });
    const first = broadcast.process(
      makeLifecycleEvent("r1", "start", "agent:ginger:main"),
      { sessionKey: "agent:ginger:main" },
    );
    expect(first.events).toHaveLength(1);
    const second = broadcast.process(
      makeLifecycleEvent("r1", "start", "agent:ginger:main"),
      { sessionKey: "agent:ginger:main" },
    );
    expect(second.events).toEqual([]);
  });

  it("ignores non-start/-end lifecycle phases", () => {
    const broadcast = createAvatarMarkerBroadcast({ getConfig: () => makeConfigWithThinking() });
    const r = broadcast.process(
      makeLifecycleEvent("r1", "start" as "start", "agent:ginger:main"),
      { sessionKey: "agent:ginger:main" },
    );
    expect(r.events).toHaveLength(1);
    const r2 = broadcast.process(
      { ...makeLifecycleEvent("r1", "end", "agent:ginger:main"), data: { phase: "heartbeat" } },
      { sessionKey: "agent:ginger:main" },
    );
    expect(r2.events).toEqual([]);
  });
});
