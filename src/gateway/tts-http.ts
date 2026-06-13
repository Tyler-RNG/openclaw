import type { IncomingMessage, ServerResponse } from "node:http";
import { loadConfig } from "../config/config.js";
import type { GatewayHttpStreamTtsConfig } from "../config/types.gateway.js";
import { resolveSecretInputString } from "../secrets/resolve-secret-input-string.js";
import {
  authorizeHttpGatewayConnect,
  type ResolvedGatewayAuth,
} from "./auth.js";
import type { AuthRateLimiter } from "./auth-rate-limit.js";
import {
  sendGatewayAuthFailure,
  sendJson,
  sendMethodNotAllowed,
} from "./http-common.js";
import { getBearerToken, resolveHttpBrowserOriginPolicy } from "./http-utils.js";

const STREAM_TTS_PATHS: ReadonlySet<string> = new Set(["/stream/tts", "/tts"]);

export type StreamTtsHttpOptions = {
  auth: ResolvedGatewayAuth;
  config: GatewayHttpStreamTtsConfig;
  trustedProxies?: string[];
  allowRealIpFallback?: boolean;
  rateLimiter?: AuthRateLimiter;
  /**
   * Optional override for resolving the provider API key.
   * Defaults to resolving `config.apiKey` via the gateway secret resolver.
   * Primarily exists to let tests bypass the secret runtime.
   */
  resolveApiKey?: () => Promise<string | undefined>;
  /**
   * Optional override for the upstream HTTP fetcher. Defaults to global `fetch`.
   * Primarily exists for tests.
   */
  fetchImpl?: typeof fetch;
};

export function isStreamTtsHttpPath(pathname: string): boolean {
  return STREAM_TTS_PATHS.has(pathname);
}

function resolveStreamTtsAuthToken(req: IncomingMessage): string | undefined {
  const bearer = getBearerToken(req);
  if (bearer) {
    return bearer;
  }
  const urlRaw = req.url;
  if (!urlRaw) {
    return undefined;
  }
  try {
    const url = new URL(urlRaw, "http://localhost");
    const tok = url.searchParams.get("token")?.trim();
    return tok || undefined;
  } catch {
    return undefined;
  }
}

async function authorizeStreamTtsRequest(params: {
  req: IncomingMessage;
  res: ServerResponse;
  opts: StreamTtsHttpOptions;
}): Promise<boolean> {
  const token = resolveStreamTtsAuthToken(params.req);
  const authResult = await authorizeHttpGatewayConnect({
    auth: params.opts.auth,
    connectAuth: token ? { token, password: token } : null,
    req: params.req,
    browserOriginPolicy: resolveHttpBrowserOriginPolicy(params.req),
    trustedProxies: params.opts.trustedProxies,
    allowRealIpFallback: params.opts.allowRealIpFallback,
    rateLimiter: params.opts.rateLimiter,
  });
  if (!authResult.ok) {
    sendGatewayAuthFailure(params.res, authResult);
    return false;
  }
  return true;
}

async function resolveProviderApiKey(
  opts: StreamTtsHttpOptions,
): Promise<string | undefined> {
  if (opts.resolveApiKey) {
    return opts.resolveApiKey();
  }
  const raw = opts.config.apiKey;
  if (raw === undefined || raw === null) {
    return undefined;
  }
  return await resolveSecretInputString({
    config: loadConfig(),
    value: raw,
    env: process.env,
    onResolveRefError: () => undefined as never,
  });
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

export async function handleStreamTtsHttpRequest(
  req: IncomingMessage,
  res: ServerResponse,
  opts: StreamTtsHttpOptions,
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
  if (!isStreamTtsHttpPath(url.pathname)) {
    return false;
  }

  if (req.method !== "GET") {
    sendMethodNotAllowed(res, "GET");
    return true;
  }

  // Always require auth. `publicAssets` does not apply to TTS — it costs money.
  const authorized = await authorizeStreamTtsRequest({ req, res, opts });
  if (!authorized) {
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
