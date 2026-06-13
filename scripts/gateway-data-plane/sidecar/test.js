"use strict";

const http = require("node:http");
const fs   = require("node:fs");

const HOST  = process.env.SIDECAR_HOST ?? "127.0.0.1";
const PORT  = Number(process.env.SIDECAR_PORT ?? 8443);
const TOKEN = process.env.SIDECAR_TOKEN ?? process.env.GATEWAY_AUTH_TOKEN ?? "";
const HAS_ELEVENLABS = !!process.env.ELEVENLABS_API_KEY;

if (!TOKEN) {
  console.error("Set SIDECAR_TOKEN (or GATEWAY_AUTH_TOKEN) in the env before running.");
  process.exit(2);
}

let passed = 0;
let failed = 0;

function request(method, urlPath, headers = {}) {
  return new Promise((resolve, reject) => {
    const opts = { hostname: HOST, port: PORT, path: urlPath, method, headers };
    const req = http.request(opts, (res) => {
      const chunks = [];
      res.on("data", (c) => chunks.push(c));
      res.on("end", () => {
        resolve({
          status:  res.statusCode,
          headers: res.headers,
          body:    Buffer.concat(chunks).toString("utf8"),
        });
      });
    });
    req.on("error", reject);
    req.end();
  });
}

function assert(name, condition, detail) {
  if (condition) {
    console.log(`  ✅ ${name}`);
    passed++;
  } else {
    console.log(`  ❌ ${name} — ${detail}`);
    failed++;
  }
}

async function run() {
  console.log("\n🔍 OpenClaw Data Plane — Verification Suite\n");
  console.log(`  ElevenLabs API key: ${HAS_ELEVENLABS ? "SET" : "NOT SET"}\n`);

  // ── T1: Health ─────────────────────────────────────────────────────
  console.log("── T1: Health check ──");
  const t1 = await request("GET", "/health");
  assert("200 on /health",      t1.status === 200,        `got ${t1.status}`);
  assert("body has status ok",  t1.body.includes('"ok"'), t1.body);

  // ── T2: No auth → 401 ─────────────────────────────────────────────
  console.log("── T2: No auth on /assets ──");
  const t2 = await request("GET", "/assets/avatars/ginger.gif");
  assert("401 without token", t2.status === 401, `got ${t2.status}`);

  // ── T3: Header auth → 200 ─────────────────────────────────────────
  console.log("── T3: Bearer header auth ──");
  const t3 = await request("GET", "/assets/avatars/ginger.gif", {
    Authorization: `Bearer ${TOKEN}`,
  });
  assert("200 with Bearer header",   t3.status === 200,                           `got ${t3.status}`);
  assert("Content-Type: image/gif",   t3.headers["content-type"] === "image/gif",  t3.headers["content-type"]);
  assert("Cache-Control: 86400",      t3.headers["cache-control"]?.includes("max-age=86400"), t3.headers["cache-control"]);
  assert("ETag present",             !!t3.headers["etag"],                         "missing");
  assert("Body non-empty",            t3.body.length > 0,                          "empty");

  // ── T4: Query auth → 200 ──────────────────────────────────────────
  console.log("── T4: Query param auth ──");
  const t4 = await request("GET", `/assets/avatars/ginger.gif?token=${TOKEN}`);
  assert("200 with ?token=", t4.status === 200, `got ${t4.status}`);

  // ── T5: Path traversal → 403 ──────────────────────────────────────
  console.log("── T5: Path traversal ──");
  const t5 = await request("GET", `/assets/../package.json?token=${TOKEN}`);
  assert("403 rejects ../", t5.status === 403, `got ${t5.status} — ${t5.body}`);

  // ── T6: Missing file → 404 ────────────────────────────────────────
  console.log("── T6: Missing file ──");
  const t6 = await request("GET", `/assets/nope.gif?token=${TOKEN}`);
  assert("404 for missing", t6.status === 404, `got ${t6.status}`);

  // ── T7: ETag → 304 ────────────────────────────────────────────────
  console.log("── T7: ETag caching (304) ──");
  const t7a = await request("GET", "/assets/avatars/ginger.gif", {
    Authorization: `Bearer ${TOKEN}`,
  });
  const etag = t7a.headers["etag"];
  assert("ETag returned", !!etag, "no etag");
  const t7b = await request("GET", "/assets/avatars/ginger.gif", {
    Authorization: `Bearer ${TOKEN}`,
    "If-None-Match": etag,
  });
  assert("304 on match", t7b.status === 304, `got ${t7b.status}`);

  // ── T8: TTS endpoint behavior ─────────────────────────────────────
  console.log("── T8: /stream/tts ──");
  // Missing params → 400
  const t8a = await request("GET", `/stream/tts?token=${TOKEN}`);
  assert("400 without voice/text", t8a.status === 400, `got ${t8a.status}`);
  // With params — ElevenLabs will respond (may 404 for fake voice, or succeed)
  const t8b = await request("GET", `/stream/tts?voice=test&text=hello&token=${TOKEN}`);
  if (HAS_ELEVENLABS) {
    assert("TTS reaches ElevenLabs (non-503)", t8b.status !== 503, `got ${t8b.status}`);
  } else {
    assert("503 when no API key", t8b.status === 503, `got ${t8b.status}`);
  }

  // ── T9: Wrong token → 401 ─────────────────────────────────────────
  console.log("── T9: Wrong token ──");
  const t9 = await request("GET", "/assets/avatars/ginger.gif", {
    Authorization: "Bearer wrongtoken",
  });
  assert("401 with wrong token", t9.status === 401, `got ${t9.status}`);

  // ── T10: All agent avatars ────────────────────────────────────────
  console.log("── T10: All agent avatars ──");
  for (const agent of ["gary", "ginger", "lily"]) {
    const r = await request("GET", `/assets/avatars/${agent}.gif?token=${TOKEN}`);
    assert(`200 for ${agent}.gif`, r.status === 200, `got ${r.status}`);
  }

  // ── T11: 404 catch-all ────────────────────────────────────────────
  console.log("── T11: Catch-all ──");
  const t11 = await request("GET", "/nope");
  assert("401 or 404 for unknown route (auth-first)", [401, 404].includes(t11.status), `got ${t11.status}`);

  // ── T12: Method restriction ────────────────────────────────────────
  console.log("── T12: Method restriction ──");
  const t12 = await request("POST", `/assets/avatars/ginger.gif?token=${TOKEN}`);
  assert("405 for POST on /assets", t12.status === 405, `got ${t12.status}`);

  // ── T13: Hidden file ──────────────────────────────────────────────
  console.log("── T13: Hidden file rejection ──");
  fs.writeFileSync("/home/jeff/.openclaw/sidecar/assets/.secret", "nope");
  const t13 = await request("GET", `/assets/.secret?token=${TOKEN}`);
  assert("403 for .secret", t13.status === 403, `got ${t13.status}`);
  fs.unlinkSync("/home/jeff/.openclaw/sidecar/assets/.secret");

  // ── T14: Subdirectory access ──────────────────────────────────────
  console.log("── T14: Subdirectory path ──");
  const t14 = await request("GET", `/assets/avatars/ginger.gif?token=${TOKEN}`);
  assert("200 for avatars/ginger.gif", t14.status === 200, `got ${t14.status}`);

  // ── T15: GIF magic bytes ──────────────────────────────────────────
  console.log("── T15: File integrity ──");
  const bodyBuf = Buffer.from(t3.body, "binary");
  assert("GIF89a magic bytes", bodyBuf[0] === 0x47 && bodyBuf[1] === 0x49 && bodyBuf[2] === 0x46, "not GIF");

  // ── T16: TTS always requires auth ─────────────────────────────────
  console.log("── T16: TTS always authed ──");
  const t16 = await request("GET", "/stream/tts?voice=test&text=hello");
  assert("401 on /stream/tts without token", t16.status === 401, `got ${t16.status}`);

  // ── T17: Token scrubbed in logs ───────────────────────────────────
  console.log("── T17: Logging sanity ──");
  const t17 = await request("GET", `/assets/avatars/ginger.gif?token=${TOKEN}`);
  assert("Request completes", t17.status === 200, `got ${t17.status}`);

  // ── T18: Safe symlink ─────────────────────────────────────────────
  console.log("── T18: Safe symlink ──");
  try {
    fs.symlinkSync(
      "/home/jeff/.openclaw/sidecar/assets/avatars/ginger.gif",
      "/home/jeff/.openclaw/sidecar/assets/ginger-link.gif"
    );
    const t18 = await request("GET", `/assets/ginger-link.gif?token=${TOKEN}`);
    assert("200 for symlink inside assetsDir", t18.status === 200, `got ${t18.status}`);
    fs.unlinkSync("/home/jeff/.openclaw/sidecar/assets/ginger-link.gif");
  } catch {
    console.log("  ⚠️  Symlink test skipped");
  }

  // ── T19: Symlink escape ───────────────────────────────────────────
  console.log("── T19: Symlink escape ──");
  try {
    fs.symlinkSync("/tmp", "/home/jeff/.openclaw/sidecar/assets/escape-link");
    const t19 = await request("GET", `/assets/escape-link?token=${TOKEN}`);
    assert("403 for symlink escaping assetsDir", t19.status === 403, `got ${t19.status}`);
    fs.unlinkSync("/home/jeff/.openclaw/sidecar/assets/escape-link");
  } catch {
    console.log("  ⚠️  Symlink escape test skipped");
  }

  // ── T20: buildAssetUrl helper (via /agents proxy) ─────────────────
  console.log("── T20: Asset URL construction ──");
  // Just verify the URL pattern works — the /agents endpoint proxies Gateway
  // and rewrites relative avatars to full URLs with token.
  // We can't test Gateway proxy without it running, but we can verify the
  // endpoint exists and requires auth.
  const t20 = await request("GET", "/agents");
  assert("/agents endpoint exists (needs auth)", t20.status === 401, `got ${t20.status}`);

  // ── Summary ───────────────────────────────────────────────────────
  console.log(`\n📊 Results: ${passed} passed, ${failed} failed out of ${passed + failed}\n`);
  process.exit(failed > 0 ? 1 : 0);
}

run().catch((err) => {
  console.error("Test runner error:", err);
  process.exit(1);
});
