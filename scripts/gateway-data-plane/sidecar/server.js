"use strict";

const express  = require("express");
const path     = require("node:path");
const fs       = require("node:fs/promises");
const { createReadStream, existsSync, readFileSync } = require("node:fs");

// ─── Config ──────────────────────────────────────────────────────────────────
const cfg = JSON.parse(readFileSync(path.join(__dirname, "config.json"), "utf8"));

const HTTP           = cfg.http ?? {};
const PORT           = cfg.port ?? 8443;
const ASSETS_DIR     = path.resolve(__dirname, HTTP.assetsDir ?? "./assets");
const BASE_URL       = (HTTP.publicBaseUrl ?? "").replace(/\/+$/, "");
const PUBLIC_ASSETS  = HTTP.publicAssets === true;
const MAX_SIZE       = HTTP.maxAssetSizeBytes ?? 10_485_760;
const AUTH_TOKEN     = cfg.auth?.token ?? "";
const TTS_CFG        = HTTP.streamTts ?? {};
const CORS_ORIGINS   = HTTP.corsOrigins ?? [];

const GW_HOST  = cfg.gateway?.host ?? "127.0.0.1";
const GW_PORT  = cfg.gateway?.port ?? 18789;
const GW_TOKEN = cfg.gateway?.token ?? AUTH_TOKEN;

if (!AUTH_TOKEN && !PUBLIC_ASSETS) {
  console.error("FATAL: auth.token required when publicAssets is false");
  process.exit(1);
}

// ElevenLabs key resolution
function elevenKey() {
  if (TTS_CFG.provider !== "elevenlabs") return "";
  const ref = TTS_CFG.providerApiKeyRef;
  if (!ref) return "";
  if (ref.kind === "env") return process.env[ref.name] ?? "";
  if (ref.kind === "inline") return ref.value ?? "";
  return "";
}

// ─── MIME ─────────────────────────────────────────────────────────────────────
const MIME = {
  ".gif": "image/gif", ".png": "image/png", ".jpg": "image/jpeg",
  ".jpeg": "image/jpeg", ".webp": "image/webp", ".svg": "image/svg+xml",
  ".mp3": "audio/mpeg", ".wav": "audio/wav", ".ogg": "audio/ogg"
};

// ─── Rate limiter ─────────────────────────────────────────────────────────────
const fails = new Map();
function authOk(ip) {
  const now = Date.now();
  let e = fails.get(ip);
  if (!e || now > e.r) { e = { n: 0, r: now + 60000 }; fails.set(ip, e); }
  e.n++;
  return e.n <= 20;
}
setInterval(() => { const n = Date.now(); for (const [k, v] of fails) if (n > v.r) fails.delete(k); }, 60000);

// ─── Helpers ──────────────────────────────────────────────────────────────────
function scrub(u) { return u.replace(/([?&])token=[^&]*/g, "$1token=***"); }

function ok(req) {
  if (!AUTH_TOKEN) return false;
  const h = req.headers.authorization;
  if (h) { const m = h.match(/^Bearer\s+(.+)$/i); if (m && m[1] === AUTH_TOKEN) return true; }
  if (req.query.token === AUTH_TOKEN) return true;
  return false;
}

function eTag(s) { return `"${s.mtimeMs.toString(36)}-${s.size.toString(36)}"`; }

async function validate(fp) {
  const rel = path.relative(ASSETS_DIR, fp);
  if (rel.startsWith("..") || path.isAbsolute(rel)) return { e: 403, m: "Path traversal rejected" };
  for (const p of rel.split(path.sep)) {
    if (p.startsWith(".") && p !== "." && p !== "..") return { e: 403, m: "Hidden files not accessible" };
  }
  let st;
  try { st = await fs.lstat(fp); } catch (err) {
    if (err.code === "ENOENT") return { e: 404, m: "File not found" };
    return { e: 500, m: "Internal server error" };
  }
  if (st.isSymbolicLink()) {
    try {
      const real = await fs.realpath(fp);
      const rrel = path.relative(ASSETS_DIR, real);
      if (rrel.startsWith("..") || path.isAbsolute(rrel)) return { e: 403, m: "Symlink points outside assets directory" };
      st = await fs.stat(fp);
    } catch { return { e: 404, m: "Symlink target not found" }; }
  }
  if (!st.isFile()) return { e: 404, m: "Not a file" };
  if (st.size > MAX_SIZE) return { e: 413, m: `File exceeds ${MAX_SIZE} bytes` };
  return { ok: true, st };
}

function buildUrl(rel, tok) {
  const b = BASE_URL || "http://localhost:" + PORT;
  const u = `${b}/assets/${rel}`;
  if (PUBLIC_ASSETS || !tok) return u;
  return `${u}?token=${encodeURIComponent(tok)}`;
}

// ─── Express ──────────────────────────────────────────────────────────────────
const app = express();
app.set("trust proxy", true);

// CORS
if (CORS_ORIGINS.length > 0) {
  app.use((req, res, next) => {
    const o = req.headers.origin;
    if (o && CORS_ORIGINS.includes(o)) {
      res.set("Access-Control-Allow-Origin", o);
      res.set("Access-Control-Allow-Methods", "GET, OPTIONS");
      res.set("Access-Control-Allow-Headers", "Authorization");
      res.set("Access-Control-Max-Age", "86400");
    }
    if (req.method === "OPTIONS") return res.status(204).end();
    next();
  });
}

// Health
app.get("/health", (_req, res) => {
  res.json({ status: "ok", service: "openclaw-asset-sidecar" });
});

// ─── Asset serving handler (used at both /assets and / mounts) ────────────────
const assetHandler = async (req, res) => {
  if (req.method !== "GET") return res.status(405).json({ error: "Method not allowed" });
  console.log(`[assets] ${scrub(req.originalUrl)} from ${req.ip}`);
  if (!PUBLIC_ASSETS && !ok(req)) {
    if (!authOk(req.ip)) return res.status(429).json({ error: "Too many auth failures" });
    return res.status(401).json({ error: "Unauthorized" });
  }
  const rel = req.path.replace(/^\//, "");
  if (!rel) return res.status(400).json({ error: "Missing asset path" });
  const fp = path.resolve(ASSETS_DIR, rel);
  const v = await validate(fp);
  if (!v.ok) return res.status(v.e).json({ error: v.m });
  const ext = path.extname(fp).toLowerCase();
  const ct = MIME[ext] ?? "application/octet-stream";
  const et = eTag(v.st);
  res.set("Cache-Control", "public, max-age=86400");
  res.set("ETag", et);
  if (req.headers["if-none-match"] === et) return res.status(304).end();
  res.set("Content-Type", ct);
  res.set("Content-Length", v.st.size.toString());
  res.status(200);
  createReadStream(fp).pipe(res);
};

// Mount at /assets (direct access)
app.use("/assets", assetHandler);

// Mount at / for Tailscale-proxied requests (Tailscale serve strips /assets prefix)
app.use("/", (req, res, next) => {
  if (req.path === "/health" || req.path.startsWith("/stream") || req.path === "/tts" || req.path === "/agents") return next();
  if (req.path === "/") return next();
  return assetHandler(req, res);
});

// ─── /stream/tts ─────────────────────────────────────────────────────────────
app.get("/stream/tts", async (req, res) => {
  if (!ok(req)) {
    if (!authOk(req.ip)) return res.status(429).json({ error: "Too many auth failures" });
    return res.status(401).json({ error: "Unauthorized" });
  }
  const { voice, text, model, stability, similarity } = req.query;
  if (!voice || !text) return res.status(400).json({ error: "Missing required params: voice, text" });
  if (!TTS_CFG.provider) return res.status(503).json({ error: "TTS not configured" });
  const key = elevenKey();
  if (!key) return res.status(503).json({ error: "TTS API key not found" });
  const ttsModel = model ?? "eleven_turbo_v2";
  console.log(`[tts] streaming voice=${voice} model=${ttsModel} from ${req.ip}`);
  try {
    const up = await fetch(`https://api.elevenlabs.io/v1/text-to-speech/${voice}/stream`, {
      method: "POST",
      headers: { "Content-Type": "application/json", "xi-api-key": key },
      body: JSON.stringify({
        text: decodeURIComponent(text),
        model_id: ttsModel,
        voice_settings: { stability: parseFloat(stability ?? "0.5"), similarity_boost: parseFloat(similarity ?? "0.75") }
      })
    });
    if (up.status === 429) return res.status(429).json({ error: "Rate limited by upstream TTS provider" });
    if (!up.ok) {
      const eb = await up.text().catch(() => "");
      return res.status(up.status).json({ error: `Upstream TTS error: ${up.status}`, detail: eb.slice(0, 200) });
    }
    res.set("Content-Type", "audio/mpeg");
    res.set("Transfer-Encoding", "chunked");
    res.set("Cache-Control", "no-store");
    res.status(200);
    const reader = up.body.getReader();
    const pump = async () => {
      try {
        while (true) {
          const { done, value } = await reader.read();
          if (done) { res.end(); return; }
          if (!res.write(value)) await new Promise(r => res.once("drain", r));
        }
      } catch (err) { console.error(`[tts] stream error: ${err.message}`); res.destroy(); }
    };
    pump();
  } catch (err) {
    console.error(`[tts] fetch error: ${err.message}`);
    res.status(502).json({ error: "Failed to reach TTS provider" });
  }
});

// ─── Same handler for Tailscale-stripped path ──────────────────────────────
app.get("/tts", async (req, res) => {
  if (!ok(req)) {
    if (!authOk(req.ip)) return res.status(429).json({ error: "Too many auth failures" });
    return res.status(401).json({ error: "Unauthorized" });
  }
  const { voice, text, model, stability, similarity } = req.query;
  if (!voice || !text) return res.status(400).json({ error: "Missing required params: voice, text" });
  if (!TTS_CFG.provider) return res.status(503).json({ error: "TTS not configured" });
  const key = elevenKey();
  if (!key) return res.status(503).json({ error: "TTS API key not found" });
  const ttsModel = model ?? "eleven_turbo_v2";
  console.log(`[tts] streaming voice=${voice} model=${ttsModel} from ${req.ip}`);
  try {
    const up = await fetch(`https://api.elevenlabs.io/v1/text-to-speech/${voice}/stream`, {
      method: "POST",
      headers: { "Content-Type": "application/json", "xi-api-key": key },
      body: JSON.stringify({
        text: decodeURIComponent(text),
        model_id: ttsModel,
        voice_settings: { stability: parseFloat(stability ?? "0.5"), similarity_boost: parseFloat(similarity ?? "0.75") }
      })
    });
    if (up.status === 429) return res.status(429).json({ error: "Rate limited by upstream TTS provider" });
    if (!up.ok) {
      const eb = await up.text().catch(() => "");
      return res.status(up.status).json({ error: `Upstream TTS error: ${up.status}`, detail: eb.slice(0, 200) });
    }
    res.set("Content-Type", "audio/mpeg");
    res.set("Transfer-Encoding", "chunked");
    res.set("Cache-Control", "no-store");
    res.status(200);
    const reader = up.body.getReader();
    const pump = async () => {
      try {
        while (true) {
          const { done, value } = await reader.read();
          if (done) { res.end(); return; }
          if (!res.write(value)) await new Promise(r => res.once("drain", r));
        }
      } catch (err) { console.error(`[tts] stream error: ${err.message}`); res.destroy(); }
    };
    pump();
  } catch (err) {
    console.error(`[tts] fetch error: ${err.message}`);
    res.status(502).json({ error: "Failed to reach TTS provider" });
  }
});

// ─── /agents proxy ────────────────────────────────────────────────────────────
app.get("/agents", async (req, res) => {
  if (!ok(req)) {
    if (!authOk(req.ip)) return res.status(429).json({ error: "Too many auth failures" });
    return res.status(401).json({ error: "Unauthorized" });
  }
  const tok = req.headers.authorization?.match(/^Bearer\s+(.+)$/i)?.[1] ?? req.query.token ?? "";
  try {
    const r = await fetch(`http://${GW_HOST}:${GW_PORT}/rpc`, {
      method: "POST",
      headers: { "Content-Type": "application/json", "Authorization": `Bearer ${GW_TOKEN}` },
      body: JSON.stringify({ method: "agents.list", params: {} })
    });
    if (!r.ok) return res.status(502).json({ error: "Gateway error", status: r.status });
    const data = await r.json();
    if (data.agents?.length) {
      for (const a of data.agents) {
        if (a.identity?.avatar && !a.identity.avatar.startsWith("http"))
          a.identity.avatar = buildUrl(a.identity.avatar, tok);
      }
    }
    res.json(data);
  } catch (err) {
    console.error(`[agents] error: ${err.message}`);
    res.status(502).json({ error: "Failed to reach Gateway" });
  }
});

// 404
app.use((_req, res) => res.status(404).json({ error: "Not found" }));

// Start
app.listen(PORT, "0.0.0.0", () => {
  console.log(`[sidecar] Listening on 0.0.0.0:${PORT}`);
  console.log(`[sidecar] Assets dir: ${ASSETS_DIR}`);
  console.log(`[sidecar] Public URL: ${BASE_URL || "(using Host header)"}`);
  console.log(`[sidecar] Auth: ${PUBLIC_ASSETS ? "SKIP /assets, REQUIRED /stream/tts" : "REQUIRED everywhere"}`);
  console.log(`[sidecar] TTS: ${TTS_CFG.provider ? TTS_CFG.provider + " (key: " + (TTS_CFG.providerApiKeyRef?.kind ?? "none") + ")" : "DISABLED"}`);
});
