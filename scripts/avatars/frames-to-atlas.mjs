#!/usr/bin/env node
// Pack an agent's sprite-frame tree (Format 2 — `kind: "sprites"`) into a
// single atlas image + sibling JSON manifest (Format 3 — `kind: "atlas"`).
//
// Reads `~/.openclaw/assets/avatars/<agentId>/frames/<state>[/<phase>]/NN.<format>`
// and writes:
//   - `~/.openclaw/assets/avatars/<agentId>/<agentId>.atlas.webp`
//   - `~/.openclaw/assets/avatars/<agentId>/<agentId>.atlas.json`
//
// Requires uniform per-frame dimensions across an agent (the first frame we
// read sets the frame size; any frame with different dimensions is rejected
// so the atlas grid stays clean).
//
// Usage:
//   node scripts/avatars/frames-to-atlas.mjs <agentId> [--assets-root <dir>]
//     [--dry-run] [--cols <n>] [--quality <1-100>] [--input-format webp|png|jpg]
//
// Examples:
//   node scripts/avatars/frames-to-atlas.mjs ginger
//   node scripts/avatars/frames-to-atlas.mjs ginger --cols 6 --quality 85
//
// After packing, switch the agent's `identity.avatar` in openclaw.json to:
//   { "kind": "atlas", "default": "neutral",
//     "manifest": "avatars/<agentId>/<agentId>.atlas.json" }
//
// See docs/avatars/formats.md for the full format spec + manifest schema.

import { promises as fs } from "node:fs";
import { existsSync, readdirSync, statSync } from "node:fs";
import os from "node:os";
import path from "node:path";
import process from "node:process";
import sharp from "sharp";

const args = process.argv.slice(2);
if (args.length === 0 || args[0] === "--help" || args[0] === "-h") {
  console.log(
    "Usage: frames-to-atlas.mjs <agentId> [--assets-root <dir>] [--dry-run] [--cols <n>] [--quality <1-100>] [--input-format webp|png|jpg]",
  );
  process.exit(args.length === 0 ? 2 : 0);
}

const agentId = args[0];
const opts = parseOpts(args.slice(1));

const assetsRoot = opts.assetsRoot ?? path.join(os.homedir(), ".openclaw", "assets");
const agentDir = path.join(assetsRoot, "avatars", agentId);
const framesRoot = path.join(agentDir, "frames");
const quality = opts.quality ?? 85;
const inputFormat = opts.inputFormat ?? detectInputFormat(framesRoot) ?? "webp";
const forceCols = opts.cols ?? null;
const dryRun = opts.dryRun;

if (!existsSync(framesRoot)) {
  fail(
    `Frames directory not found: ${framesRoot}\nRun gif-to-frames first, or author your sprite tree there.`,
  );
}

console.log(`[pack] Agent: ${agentId}`);
console.log(`[pack] Source: ${framesRoot}`);
console.log(`[pack] Input format: .${inputFormat}`);

// Walk frames tree. Two supported shapes per state:
//   <state>/NN.<fmt>                 (single-sequence)
//   <state>/<phase>/NN.<fmt>         (phased; phase ∈ intro|loop|outro)
const collected = []; // { key, file }
for (const stateEntry of readdirSync(framesRoot)) {
  const statePath = path.join(framesRoot, stateEntry);
  if (!statSync(statePath).isDirectory()) {
    continue;
  }

  const phased = ["intro", "loop", "outro"].some((p) => existsSync(path.join(statePath, p)));
  if (phased) {
    for (const phase of ["intro", "loop", "outro"]) {
      const phaseDir = path.join(statePath, phase);
      if (!existsSync(phaseDir)) {
        continue;
      }
      for (const frame of listFrameFiles(phaseDir, inputFormat)) {
        collected.push({
          key: `${stateEntry}.${phase}/${frame.stem}`,
          file: frame.file,
        });
      }
    }
  } else {
    for (const frame of listFrameFiles(statePath, inputFormat)) {
      collected.push({
        key: `${stateEntry}/${frame.stem}`,
        file: frame.file,
      });
    }
  }
}

if (collected.length === 0) {
  fail(`No .${inputFormat} frames found under ${framesRoot}`);
}

// Dedupe: identical-bytes frames share a slot in the atlas so e.g. the last
// thinking.outro frame and the first neutral frame (often the same idle pose)
// don't cost pixels twice.
const byHash = new Map();
const frames = []; // unique frames only — what we actually draw into the atlas
for (const f of collected) {
  const bytes = await fs.readFile(f.file);
  const hash = await sha256(bytes);
  let slot = byHash.get(hash);
  if (!slot) {
    slot = { hash, bytes, keys: [] };
    byHash.set(hash, slot);
    frames.push(slot);
  }
  slot.keys.push(f.key);
}

// All frames must share dimensions for a uniform grid.
const meta0 = await sharp(frames[0].bytes).metadata();
const frameW = meta0.width ?? 0;
const frameH = meta0.height ?? 0;
if (!frameW || !frameH) {
  fail("First frame has no dimensions");
}
for (const frame of frames) {
  const m = await sharp(frame.bytes).metadata();
  if (m.width !== frameW || m.height !== frameH) {
    fail(
      `Frame dimensions differ: expected ${frameW}×${frameH}, got ${m.width}×${m.height} for one of: ${frame.keys.join(", ")}`,
    );
  }
}

const uniqueCount = frames.length;
const totalRefCount = collected.length;
const cols = forceCols ?? Math.ceil(Math.sqrt(uniqueCount));
const rows = Math.ceil(uniqueCount / cols);
const atlasW = cols * frameW;
const atlasH = rows * frameH;

console.log(
  `[pack] ${totalRefCount} frame references → ${uniqueCount} unique slots, ${cols}×${rows} grid = ${atlasW}×${atlasH}px`,
);

// Assign grid positions to unique frames + build frame rect map keyed by
// every referencing key (dedup winners carry multiple keys to the same rect).
const frameRects = {};
const composites = [];
for (let i = 0; i < frames.length; i++) {
  const col = i % cols;
  const row = Math.floor(i / cols);
  const x = col * frameW;
  const y = row * frameH;
  composites.push({ input: frames[i].bytes, left: x, top: y });
  for (const key of frames[i].keys) {
    frameRects[key] = { x, y, w: frameW, h: frameH };
  }
}

// Reconstruct animation definitions. If a sibling sprites-config (produced
// by gif-to-frames) is present we reuse its fps/loop/phases; otherwise we
// emit sensible defaults and leave the artist to hand-edit the manifest.
const spritesConfig = await readSpritesConfig(agentDir, agentId);
const animations = buildAnimations({ collected, spritesConfig });
const transitions = spritesConfig?.transitions ?? {};

const manifest = {
  version: 1,
  agent: agentId,
  image: `${agentId}.atlas.webp`,
  size: { w: atlasW, h: atlasH },
  frameSize: { w: frameW, h: frameH },
  frames: frameRects,
  animations,
  ...(Object.keys(transitions).length > 0 ? { transitions } : {}),
};

const atlasOut = path.join(agentDir, `${agentId}.atlas.webp`);
const manifestOut = path.join(agentDir, `${agentId}.atlas.json`);

if (!dryRun) {
  // Composite into a transparent canvas, encode WebP with alpha.
  await sharp({
    create: {
      width: atlasW,
      height: atlasH,
      channels: 4,
      background: { r: 0, g: 0, b: 0, alpha: 0 },
    },
  })
    .composite(composites)
    .webp({ quality, alphaQuality: quality, effort: 4 })
    .toFile(atlasOut);

  await fs.writeFile(manifestOut, JSON.stringify(manifest, null, 2) + "\n", "utf8");
}

const atlasBytes = dryRun ? 0 : (await fs.stat(atlasOut)).size;
const manifestBytes = dryRun ? 0 : (await fs.stat(manifestOut)).size;

console.log("");
console.log(
  `[pack] Atlas:    ${atlasOut} ${dryRun ? "(dry-run)" : `(${prettyBytes(atlasBytes)})`}`,
);
console.log(
  `[pack] Manifest: ${manifestOut} ${dryRun ? "(dry-run)" : `(${prettyBytes(manifestBytes)})`}`,
);
console.log(
  `[pack] Switch \`identity.avatar\` in openclaw.json to:\n` +
    `       { "kind": "atlas", "default": "${spritesConfig?.default ?? "neutral"}",\n` +
    `         "manifest": "avatars/${agentId}/${agentId}.atlas.json" }\n`,
);

// ─── helpers ────────────────────────────────────────────────────────────────

function parseOpts(rest) {
  const out = { dryRun: false };
  for (let i = 0; i < rest.length; i++) {
    const a = rest[i];
    if (a === "--dry-run") {
      out.dryRun = true;
    } else if (a === "--assets-root") {
      out.assetsRoot = rest[++i];
    } else if (a === "--cols") {
      out.cols = Number.parseInt(rest[++i], 10);
    } else if (a === "--quality") {
      out.quality = Number.parseInt(rest[++i], 10);
    } else if (a === "--input-format") {
      out.inputFormat = rest[++i];
    } else {
      fail(`Unknown arg: ${a}`);
    }
  }
  return out;
}

function listFrameFiles(dir, ext) {
  return readdirSync(dir)
    .filter((name) => name.endsWith(`.${ext}`))
    .map((name) => ({
      file: path.join(dir, name),
      stem: path.basename(name, `.${ext}`),
    }))
    .toSorted((a, b) => a.stem.localeCompare(b.stem, undefined, { numeric: true }));
}

function detectInputFormat(framesRoot) {
  if (!existsSync(framesRoot)) {
    return null;
  }
  const exts = new Set();
  for (const state of readdirSync(framesRoot)) {
    const statePath = path.join(framesRoot, state);
    if (!statSync(statePath).isDirectory()) {
      continue;
    }
    for (const f of walkExts(statePath)) {
      exts.add(f);
    }
  }
  if (exts.has("webp")) {
    return "webp";
  }
  if (exts.has("png")) {
    return "png";
  }
  if (exts.has("jpg") || exts.has("jpeg")) {
    return "jpg";
  }
  return null;
}

function* walkExts(dir) {
  for (const name of readdirSync(dir)) {
    const p = path.join(dir, name);
    if (statSync(p).isDirectory()) {
      yield* walkExts(p);
    } else {
      const ext = path.extname(name).slice(1).toLowerCase();
      if (ext) {
        yield ext;
      }
    }
  }
}

async function readSpritesConfig(agentDir, agentId) {
  const candidate = path.join(agentDir, `${agentId}.sprites-config.jsonc`);
  if (!existsSync(candidate)) {
    return null;
  }
  try {
    const raw = await fs.readFile(candidate, "utf8");
    // Strip simple `// ...` line comments so JSON.parse accepts jsonc.
    const stripped = raw.replace(/^\s*\/\/.*$/gm, "");
    return JSON.parse(stripped);
  } catch {
    return null;
  }
}

function buildAnimations({ collected, spritesConfig }) {
  // Group collected keys back into states + phases.
  const byState = new Map();
  for (const { key } of collected) {
    const slash = key.lastIndexOf("/");
    const nameAndPhase = key.slice(0, slash);
    const dot = nameAndPhase.indexOf(".");
    const state = dot >= 0 ? nameAndPhase.slice(0, dot) : nameAndPhase;
    const phase = dot >= 0 ? nameAndPhase.slice(dot + 1) : null;
    let entry = byState.get(state);
    if (!entry) {
      entry = { single: [], intro: [], loop: [], outro: [] };
      byState.set(state, entry);
    }
    if (phase === "intro") {
      entry.intro.push(key);
    } else if (phase === "outro") {
      entry.outro.push(key);
    } else if (phase === "loop") {
      entry.loop.push(key);
    } else {
      entry.single.push(key);
    }
  }

  const animations = {};
  for (const [state, { single, intro, loop, outro }] of byState.entries()) {
    const cfg = spritesConfig?.states?.[state];
    const phased = intro.length > 0 || outro.length > 0;
    if (phased) {
      animations[state] = {
        intro: buildPhase(intro, cfg?.intro),
        loop: buildPhase(loop, cfg?.loop ?? cfg),
        outro: buildPhase(outro, cfg?.outro),
      };
    } else {
      animations[state] = buildPhase(single, cfg);
    }
  }
  return animations;
}

function buildPhase(frameKeys, cfgHint) {
  if (!frameKeys || frameKeys.length === 0) {
    return undefined;
  }
  return {
    frames: frameKeys,
    fps: cfgHint?.fps ?? 12,
    loop: cfgHint?.loop ?? "infinite",
    ...(cfgHint?.holdLastFrame ? { holdLastFrame: true } : {}),
    ...(cfgHint?.iterations ? { iterations: cfgHint.iterations } : {}),
  };
}

async function sha256(bytes) {
  const { createHash } = await import("node:crypto");
  return createHash("sha256").update(bytes).digest("hex");
}

function prettyBytes(n) {
  if (n < 1024) {
    return `${n} B`;
  }
  if (n < 1024 * 1024) {
    return `${(n / 1024).toFixed(1)} KB`;
  }
  return `${(n / (1024 * 1024)).toFixed(2)} MB`;
}

function fail(msg) {
  console.error(`[pack] ${msg}`);
  process.exit(1);
}
