import { loadConfig } from "openclaw/plugin-sdk/config-runtime";
import { definePluginEntry } from "openclaw/plugin-sdk/plugin-entry";
import { AGENTS_ROUTE_PATH, handleAgentsRequest } from "./src/agents-route.js";
import { ASSETS_ROUTE_PATH, handleAssetsRequest } from "./src/assets-route.js";
import { buildCharacterManifest } from "./src/character-manifest.js";
import {
  buildPromptingInstruction,
  hasSpriteDisplayCapability,
  isAtlasAvatarConfig,
} from "./src/prompting.js";
import { handleTtsRequest, TTS_ROUTE_PATH } from "./src/tts-route.js";
import type { SpriteCoreConfig } from "./src/types.js";

const SPRITE_CORE_PLUGIN_ID = "sprite-core";

/**
 * Agents currently known to have a sprite-rendering client attached.
 *
 * The old `registerSystemPromptContribution` API handed us the session's
 * `runtimeCapabilities`, so the `<<<state>>>` vocabulary could be gated per
 * turn and never leaked into Telegram / dashboard / headless sessions. The
 * modern `before_prompt_build` hook context carries no capability list, so we
 * infer it instead: a sprite client necessarily calls `node.getCharacterManifest`
 * (or `sprite-core.agents`) for the agent it is about to render, and those RPCs
 * still see `ctx.client.connect.caps`.
 *
 * Entries expire so a disconnected watch stops injecting marker vocabulary into
 * unrelated sessions for that agent. Falls closed: no sighting, no contribution.
 */
const SPRITE_CLIENT_TTL_MS = 60 * 60 * 1000;
const spriteCapableAgents = new Map<string, number>();

function markSpriteCapableAgent(agentId: string, caps: readonly string[] | undefined): void {
  if (!hasSpriteDisplayCapability(caps)) {
    return;
  }
  spriteCapableAgents.set(agentId, Date.now());
}

function hasRecentSpriteClient(agentId: string): boolean {
  const seenAt = spriteCapableAgents.get(agentId);
  if (seenAt === undefined) {
    return false;
  }
  if (Date.now() - seenAt > SPRITE_CLIENT_TTL_MS) {
    spriteCapableAgents.delete(agentId);
    return false;
  }
  return true;
}

function readClientCaps(client: unknown): readonly string[] | undefined {
  const caps = (client as { connect?: { caps?: unknown } } | undefined)?.connect?.caps;
  return Array.isArray(caps) ? caps.filter((c): c is string => typeof c === "string") : undefined;
}

function readPluginConfig(): SpriteCoreConfig | undefined {
  const cfg = loadConfig();
  return cfg.plugins?.entries?.[SPRITE_CORE_PLUGIN_ID]?.config as SpriteCoreConfig | undefined;
}

export default definePluginEntry({
  id: SPRITE_CORE_PLUGIN_ID,
  name: "SpriteCore",
  description:
    "In-gateway data plane for multi-state sprite/atlas avatars: asset serving, TTS streaming, and character-manifest RPC.",
  register(api) {
    const cfg = (api.pluginConfig ?? {}) as SpriteCoreConfig;
    const assetsCfg = cfg.assets;
    const ttsCfg = cfg.streamTts;

    if (assetsCfg?.enabled === true) {
      const assetsAuth = assetsCfg.publicAssets === true ? "plugin" : "gateway";
      api.registerHttpRoute({
        path: ASSETS_ROUTE_PATH,
        match: "prefix",
        auth: assetsAuth,
        handler: (req, res) => handleAssetsRequest(req, res, { config: assetsCfg }),
      });
    }

    if (ttsCfg?.enabled === true) {
      const ttsHandler = (
        req: Parameters<typeof handleTtsRequest>[0],
        res: Parameters<typeof handleTtsRequest>[1],
      ) => handleTtsRequest(req, res, { config: ttsCfg });
      api.registerHttpRoute({
        path: TTS_ROUTE_PATH,
        match: "exact",
        auth: "gateway",
        handler: ttsHandler,
      });
      api.registerHttpRoute({
        path: "/tts",
        match: "exact",
        auth: "gateway",
        handler: ttsHandler,
      });
    }

    api.registerHttpRoute({
      path: AGENTS_ROUTE_PATH,
      match: "exact",
      auth: "gateway",
      handler: (req, res) => {
        const fresh = readPluginConfig();
        return handleAgentsRequest(req, res, {
          agents: fresh?.agents,
          assets: fresh?.assets,
        });
      },
    });

    // System-prompt contribution: teach the model the `<<<state>>>` marker
    // vocabulary, but only for agents with a sprite-rendering client attached.
    // Dashboard / Telegram / headless chat never see this block even when the
    // plugin is installed. Config is read fresh per turn so reloads (new
    // emotion entries, description edits) take effect immediately.
    api.on("before_prompt_build", (_event, ctx) => {
      const agentId = ctx.agentId;
      if (!agentId || !hasRecentSpriteClient(agentId)) {
        return undefined;
      }
      const fresh = readPluginConfig();
      const agent = fresh?.agents?.[agentId];
      if (!agent?.avatar || !isAtlasAvatarConfig(agent.avatar)) {
        return undefined;
      }
      const text = buildPromptingInstruction({
        avatar: agent.avatar,
        prompting: agent.prompting,
        emotions: agent.emotions,
      });
      // prependSystemContext is the cacheable slot (the old `stablePrefix`),
      // so this static guidance costs no per-turn tokens.
      return text ? { prependSystemContext: text } : undefined;
    });

    // Gateway RPC: per-agent avatar + voice + prompting descriptors. Mirrors
    // the GET /sprite-core/agents HTTP endpoint over the WebSocket so clients
    // (phone, watch) that already speak RPC don't need a second HTTP path +
    // auth-token juggling. Reads fresh plugin config each call.
    //
    // Scope: `operator.read` — any connected operator (phone, watch relay)
    // can fetch this, same as `agents.list`. Without an explicit scope the
    // gateway defaults unclassified methods to `operator.admin`, which
    // blocks the phone's TalkSpeaker from resolving voice and silently
    // drops ElevenLabs TTS for every reply.
    api.registerGatewayMethod(
      "sprite-core.agents",
      async (ctx) => {
        const fresh = readPluginConfig();
        const publicBaseUrl = fresh?.assets?.publicBaseUrl?.trim() || undefined;
        // A sprite client asking for the agent roster is a capability sighting
        // for every agent it may render; see spriteCapableAgents.
        const rosterCaps = readClientCaps(ctx.client);
        for (const id of Object.keys(fresh?.agents ?? {})) {
          markSpriteCapableAgent(id, rosterCaps);
        }
        const agentsOut: Record<string, unknown> = {};
        for (const [id, entry] of Object.entries(fresh?.agents ?? {})) {
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
        ctx.respond(
          true,
          { agents: agentsOut, ...(publicBaseUrl ? { publicBaseUrl } : {}) },
          undefined,
        );
      },
      { scope: "operator.read" },
    );

    // Gateway RPC: ship the watch a ready-to-render character manifest. Reads
    // fresh plugin config each call so config reload is observed.
    api.registerGatewayMethod("node.getCharacterManifest", async (ctx) => {
      const params = ctx.params as { agentId?: unknown; modes?: unknown };
      const agentId =
        typeof params.agentId === "string" && params.agentId.trim().length > 0
          ? params.agentId.trim()
          : null;
      if (!agentId) {
        ctx.respond(false, undefined, {
          code: "INVALID_REQUEST",
          message: "agentId required",
        });
        return;
      }
      const modes = Array.isArray(params.modes)
        ? params.modes.filter((m): m is string => typeof m === "string")
        : undefined;
      const caps = readClientCaps(ctx.client);
      markSpriteCapableAgent(agentId, caps);
      const pluginConfig = readPluginConfig();
      try {
        const result = await buildCharacterManifest({
          pluginConfig,
          agentId,
          modes,
          caps,
        });
        if (!result.ok) {
          const code = result.code === "unknown-agent" ? "INVALID_REQUEST" : "UNAVAILABLE";
          ctx.respond(false, undefined, { code, message: result.message });
          return;
        }
        ctx.respond(true, { manifest: result.manifest, revision: result.revision }, undefined);
      } catch (err) {
        const message = err instanceof Error ? err.message : String(err);
        api.logger.warn?.(`sprite-core: node.getCharacterManifest failed — ${message}`);
        ctx.respond(false, undefined, {
          code: "UNAVAILABLE",
          message: "character manifest unavailable",
        });
      }
    });
  },
});
