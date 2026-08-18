import { createReadStream } from "node:fs";
import fs from "node:fs/promises";
import type { IncomingMessage, ServerResponse } from "node:http";
import path from "node:path";
import { resolveStateDir } from "../config/paths.js";
import type { GatewayHttpAssetsConfig } from "../config/types.gateway.js";
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

const DEFAULT_MAX_ASSET_BYTES = 10 * 1024 * 1024;

const MIME_BY_EXT: Record<string, string> = {
  ".gif": "image/gif",
  ".png": "image/png",
  ".jpg": "image/jpeg",
  ".jpeg": "image/jpeg",
  ".webp": "image/webp",
  ".svg": "image/svg+xml",
  ".mp3": "audio/mpeg",
  ".wav": "audio/wav",
  ".ogg": "audio/ogg",
};

export type AssetsHttpOptions = {
  auth: ResolvedGatewayAuth;
  config: GatewayHttpAssetsConfig;
  trustedProxies?: string[];
  allowRealIpFallback?: boolean;
  rateLimiter?: AuthRateLimiter;
};

export function isAssetsHttpPath(pathname: string): boolean {
  return pathname === "/assets" || pathname.startsWith("/assets/");
}

function resolveAssetsDir(cfg: GatewayHttpAssetsConfig): string {
  const raw =
    typeof cfg.assetsDir === "string" && cfg.assetsDir.trim().length > 0
      ? cfg.assetsDir.trim()
      : "./assets";
  if (path.isAbsolute(raw)) {
    return path.resolve(raw);
  }
  return path.resolve(resolveStateDir(), raw);
}

function resolveMaxBytes(cfg: GatewayHttpAssetsConfig): number {
  const n = cfg.maxAssetSizeBytes;
  if (typeof n === "number" && Number.isFinite(n) && n > 0) {
    return Math.floor(n);
  }
  return DEFAULT_MAX_ASSET_BYTES;
}

function resolveAssetAuthToken(req: IncomingMessage): string | undefined {
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

async function authorizeAssetRequest(params: {
  req: IncomingMessage;
  res: ServerResponse;
  opts: AssetsHttpOptions;
}): Promise<boolean> {
  if (params.opts.config.publicAssets === true) {
    return true;
  }
  const token = resolveAssetAuthToken(params.req);
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

type ValidatedAsset =
  | { ok: true; stat: { size: number; mtimeMs: number } }
  | { ok: false; status: number; message: string };

async function validateAssetPath(params: {
  assetsDir: string;
  absPath: string;
  relPath: string;
  maxBytes: number;
}): Promise<ValidatedAsset> {
  const rel = path.relative(params.assetsDir, params.absPath);
  if (rel.startsWith("..") || path.isAbsolute(rel)) {
    return { ok: false, status: 403, message: "Path traversal rejected" };
  }
  // POSIX-style relative path check against segments (defense in depth on Windows).
  for (const seg of rel.split(/[/\\]/)) {
    if (seg.startsWith(".") && seg !== "" && seg !== "." && seg !== "..") {
      return { ok: false, status: 403, message: "Hidden files not accessible" };
    }
  }

  let lstat;
  try {
    lstat = await fs.lstat(params.absPath);
  } catch (err) {
    const code = (err as { code?: string }).code;
    if (code === "ENOENT" || code === "ENOTDIR") {
      return { ok: false, status: 404, message: "File not found" };
    }
    return { ok: false, status: 500, message: "Internal error" };
  }

  if (lstat.isSymbolicLink()) {
    try {
      const real = await fs.realpath(params.absPath);
      const rrel = path.relative(params.assetsDir, real);
      if (rrel.startsWith("..") || path.isAbsolute(rrel)) {
        return {
          ok: false,
          status: 403,
          message: "Symlink points outside assets directory",
        };
      }
      lstat = await fs.stat(params.absPath);
    } catch {
      return { ok: false, status: 404, message: "Symlink target not found" };
    }
  }

  if (!lstat.isFile()) {
    return { ok: false, status: 404, message: "Not a file" };
  }
  if (lstat.size > params.maxBytes) {
    return {
      ok: false,
      status: 413,
      message: `File exceeds ${params.maxBytes} bytes`,
    };
  }
  return { ok: true, stat: { size: lstat.size, mtimeMs: lstat.mtimeMs } };
}

function buildEtag(stat: { size: number; mtimeMs: number }): string {
  return `"${stat.mtimeMs.toString(36)}-${stat.size.toString(36)}"`;
}

export async function handleAssetsHttpRequest(
  req: IncomingMessage,
  res: ServerResponse,
  opts: AssetsHttpOptions,
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
  if (!isAssetsHttpPath(url.pathname)) {
    return false;
  }

  if (req.method !== "GET" && req.method !== "HEAD") {
    sendMethodNotAllowed(res, "GET, HEAD");
    return true;
  }

  const authorized = await authorizeAssetRequest({ req, res, opts });
  if (!authorized) {
    return true;
  }

  const relPath = url.pathname.replace(/^\/assets\/?/, "");
  if (!relPath) {
    sendJson(res, 400, {
      error: { message: "Missing asset path", type: "invalid_request_error" },
    });
    return true;
  }

  let decodedRel: string;
  try {
    decodedRel = decodeURIComponent(relPath);
  } catch {
    sendJson(res, 400, {
      error: { message: "Invalid asset path encoding", type: "invalid_request_error" },
    });
    return true;
  }

  if (decodedRel.includes("\0")) {
    sendJson(res, 400, {
      error: { message: "Invalid asset path", type: "invalid_request_error" },
    });
    return true;
  }

  const assetsDir = resolveAssetsDir(opts.config);
  const maxBytes = resolveMaxBytes(opts.config);
  const absPath = path.resolve(assetsDir, decodedRel);
  const validated = await validateAssetPath({
    assetsDir,
    absPath,
    relPath: decodedRel,
    maxBytes,
  });
  if (!validated.ok) {
    sendJson(res, validated.status, {
      error: { message: validated.message, type: "invalid_request_error" },
    });
    return true;
  }

  const ext = path.extname(absPath).toLowerCase();
  const contentType = MIME_BY_EXT[ext] ?? "application/octet-stream";
  const etag = buildEtag(validated.stat);

  res.setHeader("Cache-Control", "public, max-age=86400");
  res.setHeader("ETag", etag);

  if (req.headers["if-none-match"] === etag) {
    res.statusCode = 304;
    res.end();
    return true;
  }

  res.statusCode = 200;
  res.setHeader("Content-Type", contentType);
  res.setHeader("Content-Length", String(validated.stat.size));

  if (req.method === "HEAD") {
    res.end();
    return true;
  }

  const stream = createReadStream(absPath);
  stream.once("error", () => {
    if (!res.headersSent) {
      sendJson(res, 500, {
        error: { message: "Read error", type: "invalid_request_error" },
      });
      return;
    }
    res.destroy();
  });
  stream.pipe(res);
  return true;
}
