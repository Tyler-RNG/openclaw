import { resolveAgentAvatar } from "../agents/identity-avatar.js";
import type { OpenClawConfig } from "../config/types.openclaw.js";
import type { AgentEventPayload } from "../infra/agent-events.js";
import { resolveAgentIdFromSessionKey } from "../routing/session-key.js";
import { parseAvatarMarkers, type AvatarMarker } from "./avatar-marker-parser.js";

/**
 * Live-stream splice: rewrites assistant text payloads to strip `[avatar:<state>]`
 * markers (when the speaking agent has a multi-state avatar configured) and
 * surfaces the markers as a list the caller can broadcast as
 * `avatar.state.change` events.
 *
 * The gateway stores assistant deltas as cumulative text each push. To avoid
 * re-emitting the same marker every delta, we track how many markers we've
 * already seen per run and emit only the tail.
 */

export type AvatarMarkerBroadcastEvent = {
  runId: string;
  sessionKey?: string;
  agentId: string;
  state: string;
  /** File reference from the config (free-form string: path / URL / etc). */
  file: string;
};

export type AvatarMarkerBroadcastResult = {
  /**
   * Rewritten event payload where `data.delta` and `data.text` have markers
   * stripped. `null` means "no rewriting happened, pass the original through".
   */
  event: AgentEventPayload | null;
  /** Newly observed markers for this delta, in order. */
  events: AvatarMarkerBroadcastEvent[];
};

export type AvatarMarkerBroadcast = {
  process(
    evt: AgentEventPayload,
    ctx: { sessionKey?: string },
  ): AvatarMarkerBroadcastResult;
  clearRun(runId: string): void;
};

function isAssistantEvent(evt: AgentEventPayload): boolean {
  return evt.stream === "assistant";
}

function stringOrEmpty(value: unknown): string {
  return typeof value === "string" ? value : "";
}

export function createAvatarMarkerBroadcast(params: {
  getConfig: () => OpenClawConfig;
}): AvatarMarkerBroadcast {
  // How many markers we've already surfaced per run. We use this to emit only
  // the new tail each time the cumulative `data.text` is re-parsed.
  const emittedCountByRun = new Map<string, number>();

  function stripAndDiff(params: {
    evt: AgentEventPayload;
    agentId: string;
    sessionKey?: string;
    stateMap: Record<string, { file: string; description?: string }>;
  }): AvatarMarkerBroadcastResult {
    const { evt, agentId, sessionKey, stateMap } = params;
    const rawText = stringOrEmpty(evt.data?.text);
    const rawDelta = stringOrEmpty(evt.data?.delta);
    const parsedText = parseAvatarMarkers(rawText);
    const parsedDelta = parseAvatarMarkers(rawDelta);

    // Cumulative-text diff: the marker list on the FULL text is authoritative.
    // Emit only markers past the previously-emitted count.
    const previousCount = emittedCountByRun.get(evt.runId) ?? 0;
    const newMarkers: AvatarMarker[] = parsedText.markers.slice(previousCount);
    emittedCountByRun.set(evt.runId, parsedText.markers.length);

    const events: AvatarMarkerBroadcastEvent[] = [];
    for (const marker of newMarkers) {
      const entry = stateMap[marker.state];
      if (!entry) {
        // Unknown state — silently skip per spec.
        continue;
      }
      events.push({
        runId: evt.runId,
        sessionKey,
        agentId,
        state: marker.state,
        file: entry.file,
      });
    }

    // If the text had no markers at all, pass the original event through so
    // we don't clone objects unnecessarily.
    if (parsedText.markers.length === 0 && parsedDelta.markers.length === 0) {
      return { event: null, events };
    }

    const rewrittenData: Record<string, unknown> = { ...evt.data };
    if (rawText) {
      rewrittenData.text = parsedText.cleanedText;
    }
    if (rawDelta) {
      rewrittenData.delta = parsedDelta.cleanedText;
    }
    return {
      event: { ...evt, data: rewrittenData },
      events,
    };
  }

  return {
    process(evt, ctx) {
      if (!isAssistantEvent(evt)) {
        return { event: null, events: [] };
      }
      const sessionKey = ctx.sessionKey ?? evt.sessionKey;
      const agentId = resolveAgentIdFromSessionKey(sessionKey);
      if (!agentId) {
        return { event: null, events: [] };
      }
      const cfg = params.getConfig();
      const resolved = resolveAgentAvatar(cfg, agentId);
      if (resolved.kind !== "states") {
        return { event: null, events: [] };
      }
      return stripAndDiff({
        evt,
        agentId,
        sessionKey,
        stateMap: resolved.states,
      });
    },
    clearRun(runId) {
      emittedCountByRun.delete(runId);
    },
  };
}
