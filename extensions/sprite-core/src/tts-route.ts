import type { IncomingMessage, ServerResponse } from "node:http";
import { isSecretRef, resolveSecretInputString } from "openclaw/plugin-sdk/secret-input";
import { sendJson, sendMethodNotAllowed } from "./http-helpers.js";
import type { SpriteCoreStreamTtsConfig } from "./types.js";

export const TTS_ROUTE_PATH = "/stream/tts";

export type TtsRouteOptions = {
  config: SpriteCoreStreamTtsConfig;
  /**
   * Optional override for the upstream fetcher. Primarily for tests.
   */
  fetchImpl?: typeof fetch;
  /**
   * Optional override for resolving the provider API key.
   * Primarily for tests that want to bypass the secret runtime.
   */
  resolveApiKey?: () => Promise<string | undefined>;
};

async function resolveProviderApiKey(opts: TtsRouteOptions): Promise<string | undefined> {
  if (opts.resolveApiKey) {
    return opts.resolveApiKey();
  }
  const raw = opts.config.apiKey;
  if (raw === undefined || raw === null) {
    return undefined;
  }
  // First try the plain-string path via the SDK resolver. This also rejects
  // malformed input via its normalization rules.
  const resolved = resolveSecretInputString({
    value: raw,
    path: "plugins.entries.sprite-core.config.streamTts.apiKey",
  });
  if (resolved.status === "available") {
    return resolved.value;
  }
  // Ref-backed secret: dereference via env. Future backends (file/exec) can
  // be added here; env covers every current SpriteCore deployment.
  if (isSecretRef(raw) && raw.source === "env" && typeof raw.id === "string") {
    const value = process.env[raw.id];
    if (typeof value === "string" && value.trim().length > 0) {
      return value;
    }
  }
  return undefined;
}

function parseFloatParam(value: string | null, fallback: number): number {
  if (value === null) {
    return fallback;
  }
  const n = Number.parseFloat(value);
  if (!Number.isFinite(n)) {
    return fallback;
  }
  return n;
}

function parseOptionalFloatParam(value: string | null): number | undefined {
  if (value === null) {
    return undefined;
  }
  const n = Number.parseFloat(value);
  return Number.isFinite(n) ? n : undefined;
}

function parseOptionalBooleanParam(value: string | null): boolean | undefined {
  if (value === null) {
    return undefined;
  }
  const normalized = value.trim().toLowerCase();
  if (normalized === "true" || normalized === "1") {
    return true;
  }
  if (normalized === "false" || normalized === "0") {
    return false;
  }
  return undefined;
}

/**
 * HTTP handler for `GET /stream/tts`. Registered with `auth: "gateway"` — the
 * plugin HTTP dispatcher enforces gateway auth before this handler runs.
 *
 * The TTS proxy always requires auth because it costs money; there is no
 * `publicAssets`-style escape hatch.
 */
export async function handleTtsRequest(
  req: IncomingMessage,
  res: ServerResponse,
  opts: TtsRouteOptions,
): Promise<boolean> {
  if (opts.config.enabled !== true) {
    return false;
  }
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
  if (url.pathname !== TTS_ROUTE_PATH && url.pathname !== "/tts") {
    return false;
  }

  if (req.method !== "GET") {
    sendMethodNotAllowed(res, "GET");
    return true;
  }

  const voice = url.searchParams.get("voice")?.trim() ?? "";
  const text = url.searchParams.get("text") ?? "";
  if (!voice || !text) {
    sendJson(res, 400, {
      error: {
        message: "Missing required params: voice, text",
        type: "invalid_request_error",
      },
    });
    return true;
  }

  if (opts.config.provider !== "elevenlabs") {
    sendJson(res, 503, {
      error: { message: "TTS provider not configured", type: "unavailable" },
    });
    return true;
  }

  const apiKey = await resolveProviderApiKey(opts);
  if (!apiKey) {
    sendJson(res, 503, {
      error: { message: "TTS API key not available", type: "unavailable" },
    });
    return true;
  }

  const defaultModel =
    (typeof opts.config.defaultModel === "string" && opts.config.defaultModel.trim()) ||
    "eleven_turbo_v2";
  const model = url.searchParams.get("model")?.trim() || defaultModel;
  const stability = parseFloatParam(url.searchParams.get("stability"), 0.5);
  const similarity = parseFloatParam(url.searchParams.get("similarity"), 0.75);
  // Optional emotion-driven overrides. Absent → ElevenLabs uses the model
  // defaults (voice-specific). Present → included in `voice_settings`.
  const style = parseOptionalFloatParam(url.searchParams.get("style"));
  const speakerBoost = parseOptionalBooleanParam(url.searchParams.get("speaker_boost"));
  const fetchImpl = opts.fetchImpl ?? fetch;

  let decodedText: string;
  try {
    decodedText = decodeURIComponent(text);
  } catch {
    sendJson(res, 400, {
      error: { message: "Invalid text encoding", type: "invalid_request_error" },
    });
    return true;
  }

  let upstream: Response;
  try {
    upstream = await fetchImpl(
      `https://api.elevenlabs.io/v1/text-to-speech/${encodeURIComponent(voice)}/stream`,
      {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "xi-api-key": apiKey,
        },
        body: JSON.stringify({
          text: decodedText,
          model_id: model,
          voice_settings: {
            stability,
            similarity_boost: similarity,
            ...(style !== undefined ? { style } : {}),
            ...(speakerBoost !== undefined ? { use_speaker_boost: speakerBoost } : {}),
          },
        }),
      },
    );
  } catch {
    sendJson(res, 502, {
      error: { message: "Failed to reach TTS provider", type: "upstream_error" },
    });
    return true;
  }

  if (upstream.status === 429) {
    sendJson(res, 429, {
      error: { message: "Rate limited by upstream TTS provider", type: "rate_limited" },
    });
    return true;
  }
  if (!upstream.ok) {
    const body = await upstream.text().catch(() => "");
    sendJson(res, upstream.status, {
      error: {
        message: `Upstream TTS error: ${upstream.status}`,
        type: "upstream_error",
        detail: body.slice(0, 200),
      },
    });
    return true;
  }

  res.statusCode = 200;
  res.setHeader("Content-Type", "audio/mpeg");
  res.setHeader("Transfer-Encoding", "chunked");
  res.setHeader("Cache-Control", "no-store");

  const body = upstream.body;
  if (!body) {
    res.end();
    return true;
  }

  const reader = body.getReader();
  try {
    while (true) {
      const chunk = await reader.read();
      if (chunk.done) {
        break;
      }
      if (!res.write(chunk.value)) {
        await new Promise<void>((resolve) => res.once("drain", () => resolve()));
      }
    }
    res.end();
  } catch {
    res.destroy();
  }
  return true;
}
