import type { IncomingMessage, ServerResponse } from "node:http";
import { sendJson, sendMethodNotAllowed } from "./http-helpers.js";
import type { SpriteCoreAgentEntry, SpriteCoreAssetsConfig } from "./types.js";

export const AGENTS_ROUTE_PATH = "/sprite-core/agents";

export type AgentsRouteOptions = {
  agents: Record<string, SpriteCoreAgentEntry> | undefined;
  assets: SpriteCoreAssetsConfig | undefined;
};

/**
 * `GET /sprite-core/agents` — returns `{ agents: { <id>: { avatar, voice } } }`
 * for clients (phone, watch) to merge with the gateway's `agents.list`
 * response. This keeps rich avatar/voice config out of `openclaw.json` and
 * behind the plugin boundary.
 *
 * Registered with `auth: "gateway"` — the plugin HTTP dispatcher enforces
 * gateway auth before this handler runs.
 */
export async function handleAgentsRequest(
  req: IncomingMessage,
  res: ServerResponse,
  opts: AgentsRouteOptions,
): Promise<boolean> {
  const urlRaw = req.url;
  if (!urlRaw) {
    return false;
  }
  let url: URL;
  try {
    url = new URL(urlRaw, "http://localhost");
  } catch {
    return false;
  }
  if (url.pathname !== AGENTS_ROUTE_PATH) {
    return false;
  }

  if (req.method !== "GET") {
    sendMethodNotAllowed(res, "GET");
    return true;
  }

  const publicBaseUrl = opts.assets?.publicBaseUrl?.trim() || undefined;
  const agentsOut: Record<string, SpriteCoreAgentEntry> = {};

  for (const [id, entry] of Object.entries(opts.agents ?? {})) {
    if (!entry) {
      continue;
    }
    agentsOut[id] = {
      ...(entry.avatar ? { avatar: entry.avatar } : {}),
      ...(entry.voice ? { voice: entry.voice } : {}),
      ...(entry.prompting ? { prompting: entry.prompting } : {}),
      ...(entry.emotions ? { emotions: entry.emotions } : {}),
    };
  }

  sendJson(res, 200, {
    agents: agentsOut,
    ...(publicBaseUrl ? { publicBaseUrl } : {}),
  });
  return true;
}
