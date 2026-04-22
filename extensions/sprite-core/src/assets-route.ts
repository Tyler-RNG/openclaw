import { createReadStream } from "node:fs";
import fs from "node:fs/promises";
import type { IncomingMessage, ServerResponse } from "node:http";
import path from "node:path";
import { resolveStateDir } from "openclaw/plugin-sdk/state-paths";
import { sendJson, sendMethodNotAllowed } from "./http-helpers.js";
import type { SpriteCoreAssetsConfig } from "./types.js";

export const ASSETS_ROUTE_PATH = "/openclaw-assets";

const DEFAULT_MAX_ASSET_BYTES = 10 * 1024 * 1024;

const MIME_BY_EXT: Record<string, string> = {
  ".gif": "image/gif",
  ".png": "image/png",
  ".jpg": "image/jpeg",
  ".jpeg": "image/jpeg",
  ".webp": "image/webp",
  ".svg": "image/svg+xml",
  ".json": "application/json",
  ".mp3": "audio/mpeg",
  ".wav": "audio/wav",
  ".ogg": "audio/ogg",
};

function resolveAssetsDir(cfg: SpriteCoreAssetsConfig): string {
  const raw =
    typeof cfg.assetsDir === "string" && cfg.assetsDir.trim().length > 0
      ? cfg.assetsDir.trim()
      : "./assets";
  if (path.isAbsolute(raw)) {
    return path.resolve(raw);
  }
  return path.resolve(resolveStateDir(), raw);
}

function resolveMaxBytes(cfg: SpriteCoreAssetsConfig): number {
  const n = cfg.maxAssetSizeBytes;
  if (typeof n === "number" && Number.isFinite(n) && n > 0) {
    return Math.floor(n);
  }
  return DEFAULT_MAX_ASSET_BYTES;
}

type ValidatedAsset =
  | { ok: true; stat: { size: number; mtimeMs: number } }
  | { ok: false; status: number; message: string };

async function validateAssetPath(params: {
  assetsDir: string;
  absPath: string;
  maxBytes: number;
}): Promise<ValidatedAsset> {
  const rel = path.relative(params.assetsDir, params.absPath);
  if (rel.startsWith("..") || path.isAbsolute(rel)) {
    return { ok: false, status: 403, message: "Path traversal rejected" };
  }
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

export type AssetsRouteOptions = {
  config: SpriteCoreAssetsConfig;
};

/**
 * HTTP handler for `GET /openclaw-assets/<path>`. Returns `true` if it handled
 * the request (including error responses), `false` if it did not.
 *
 * Gateway-level auth is enforced by the plugin HTTP dispatcher when the route
 * is registered with `auth: "gateway"`. When `config.publicAssets === true`,
 * the route is registered with `auth: "plugin"` and this handler serves
 * unauthenticated requests.
 */
export async function handleAssetsRequest(
  req: IncomingMessage,
  res: ServerResponse,
  opts: AssetsRouteOptions,
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
  if (!url.pathname.startsWith(`${ASSETS_ROUTE_PATH}/`) && url.pathname !== ASSETS_ROUTE_PATH) {
    return false;
  }

  if (req.method !== "GET" && req.method !== "HEAD") {
    sendMethodNotAllowed(res, "GET, HEAD");
    return true;
  }

  const relPath = url.pathname.replace(/^\/openclaw-assets\/?/, "");
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
  const validated = await validateAssetPath({ assetsDir, absPath, maxBytes });
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
