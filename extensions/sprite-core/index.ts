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
    // vocabulary, but only for sessions whose connected client can render a
    // sprite. Dashboard / Telegram / headless chat never see this block even
    // when the plugin is installed. Config is read fresh per turn so reloads
    // (new emotion entries, description edits) take effect immediately.
    api.registerSystemPromptContribution((promptCtx) => {
      if (!promptCtx.agentId) {
        return undefined;
      }
      if (!hasSpriteDisplayCapability(promptCtx.runtimeCapabilities)) {
        return undefined;
      }
      const fresh = readPluginConfig();
      const agent = fresh?.agents?.[promptCtx.agentId];
      if (!agent?.avatar || !isAtlasAvatarConfig(agent.avatar)) {
        return undefined;
      }
      const text = buildPromptingInstruction({
        avatar: agent.avatar,
        prompting: agent.prompting,
        emotions: agent.emotions,
      });
      return text ? { stablePrefix: text } : undefined;
    });

    // Gateway RPC: per-agent avatar + voice + prompting descriptors. Mirrors
    // the GET /sprite-core/agents HTTP endpoint over the WebSocket so clients
    // (phone, watch) that already speak RPC don't need a second HTTP path +
    // auth-token juggling. Reads fresh plugin config each call.
    api.registerGatewayMethod("sprite-core.agents", async (ctx) => {
      const fresh = readPluginConfig();
      const publicBaseUrl = fresh?.assets?.publicBaseUrl?.trim() || undefined;
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
    });

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
      const caps = ctx.client?.connect?.caps;
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
