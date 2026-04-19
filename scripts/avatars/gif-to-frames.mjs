#!/usr/bin/env node
// Split an agent's per-state GIFs (Format 1 — `kind: "states"`) into
// numbered WebP frame sequences (Format 2 — `kind: "sprites"`).
//
// Reads `~/.openclaw/assets/avatars/<agentId>/<state>.gif` for each state
// it finds on disk, runs ffmpeg to extract frames, and writes
// `<agentId>/frames/<state>/NN.webp`. Also prints a starter `kind: "sprites"`
// config block with fps detected from each GIF's frame delays.
//
// Usage:
//   node scripts/avatars/gif-to-frames.mjs <agentId> [--assets-root <dir>]
//     [--dry-run] [--format webp|png] [--fps <override>]
//
// Examples:
//   node scripts/avatars/gif-to-frames.mjs ginger
//   node scripts/avatars/gif-to-frames.mjs ginger --fps 24
//   node scripts/avatars/gif-to-frames.mjs ginger --assets-root /custom/path
//
// See docs/avatars/formats.md for the full format spec.

import { spawnSync } from "node:child_process";
import { promises as fs } from "node:fs";
import { existsSync, readdirSync, statSync } from "node:fs";
import os from "node:os";
import path from "node:path";
import process from "node:process";

const args = process.argv.slice(2);
if (args.length === 0 || args[0] === "--help" || args[0] === "-h") {
  console.log(
    "Usage: gif-to-frames.mjs <agentId> [--assets-root <dir>] [--dry-run] [--format webp|png] [--fps <n>]",
  );
  process.exit(args.length === 0 ? 2 : 0);
}

const agentId = args[0];
const opts = parseOpts(args.slice(1));

const assetsRoot = opts.assetsRoot ?? path.join(os.homedir(), ".openclaw", "assets");
const agentDir = path.join(assetsRoot, "avatars", agentId);
const framesRoot = path.join(agentDir, "frames");
const format = opts.format ?? "webp";
const fpsOverride = opts.fps ?? null;
const dryRun = opts.dryRun;

if (!existsSync(agentDir)) {
  fail(`Agent directory not found: ${agentDir}`);
}

const stateGifs = readdirSync(agentDir)
  .filter((name) => name.endsWith(".gif") && statSync(path.join(agentDir, name)).isFile())
  .map((name) => ({
    state: path.basename(name, ".gif"),
    gif: path.join(agentDir, name),
  }));

if (stateGifs.length === 0) {
  fail(`No .gif files found under ${agentDir}`);
}

console.log(`[gif-to-frames] Agent: ${agentId}`);
console.log(`[gif-to-frames] Source:  ${agentDir}`);
console.log(`[gif-to-frames] Output:  ${framesRoot}/<state>/NN.${format}`);
console.log(`[gif-to-frames] States:  ${stateGifs.map((s) => s.state).join(", ")}`);
if (dryRun) {
  console.log("[gif-to-frames] DRY RUN — no files written");
}

const configStates = {};

for (const { state, gif } of stateGifs) {
  const outDir = path.join(framesRoot, state);
  const detectedFps = fpsOverride ?? detectGifFps(gif);
  const frameCount = probeGifFrameCount(gif);

  console.log(`  - ${state}: ${frameCount} frames @ ${detectedFps} fps → ${outDir}/`);

  if (!dryRun) {
    await fs.mkdir(outDir, { recursive: true });
    const digits = frameCount >= 100 ? 3 : 2;
    const pattern = path.join(outDir, `%0${digits}d.${format}`);
    runFfmpeg([
      "-y",
      "-i",
      gif,
      "-vsync",
      "0",
      "-start_number",
      "0",
      ...(format === "webp" ? ["-c:v", "libwebp", "-lossless", "0", "-quality", "85"] : []),
      pattern,
    ]);
  }

  configStates[state] = {
    count: frameCount,
    fps: detectedFps,
    loop: state === "thinking" ? "once" : "infinite",
    ...(state === "thinking" ? { holdLastFrame: true } : {}),
    description: descriptionFor(state),
  };
}

const defaultState = stateGifs.find((s) => s.state === "neutral")?.state ?? stateGifs[0].state;

const starterConfig = {
  kind: "sprites",
  default: defaultState,
  basePath: path.posix.join("avatars", agentId, "frames"),
  format,
  states: configStates,
  transitions: {
    "*->thinking": "thinking.intro",
    "thinking->*": "thinking.outro",
  },
};

const configOut = path.join(agentDir, `${agentId}.sprites-config.jsonc`);
const configBlock =
  "// Paste this as the `identity.avatar` value for this agent in openclaw.json.\n" +
  "// See docs/avatars/formats.md for the full format spec.\n" +
  '// `thinking` defaults to `loop: "once"` + `holdLastFrame: true` so the wait\n' +
  "// reads naturally; adjust per-state fps/loop as your art directs.\n" +
  JSON.stringify(starterConfig, null, 2) +
  "\n";

if (!dryRun) {
  await fs.writeFile(configOut, configBlock, "utf8");
}

console.log("");
console.log(
  `[gif-to-frames] Wrote starter config: ${configOut}\n` +
    `[gif-to-frames] Paste its contents into openclaw.json at \`identity.avatar\`.`,
);

function parseOpts(rest) {
  const out = { dryRun: false };
  for (let i = 0; i < rest.length; i++) {
    const a = rest[i];
    if (a === "--dry-run") {
      out.dryRun = true;
    } else if (a === "--assets-root") {
      out.assetsRoot = rest[++i];
    } else if (a === "--format") {
      out.format = rest[++i];
    } else if (a === "--fps") {
      out.fps = Number(rest[++i]);
    } else {
      fail(`Unknown arg: ${a}`);
    }
  }
  return out;
}

function runFfmpeg(ffArgs) {
  const r = spawnSync("ffmpeg", ffArgs, { stdio: "inherit" });
  if (r.status !== 0) {
    fail(`ffmpeg exited ${r.status}`);
  }
}

// Probe frame count via ffprobe.
function probeGifFrameCount(gif) {
  const r = spawnSync(
    "ffprobe",
    [
      "-v",
      "error",
      "-count_frames",
      "-select_streams",
      "v:0",
      "-show_entries",
      "stream=nb_read_frames",
      "-of",
      "default=nokey=1:noprint_wrappers=1",
      gif,
    ],
    { encoding: "utf8" },
  );
  if (r.status !== 0) {
    fail(`ffprobe failed for ${gif}`);
  }
  const n = Number.parseInt(r.stdout.trim(), 10);
  if (!Number.isFinite(n) || n <= 0) {
    fail(`Could not determine frame count for ${gif}`);
  }
  return n;
}

// Detect average fps from GIF frame delays (rounded to a friendly integer).
function detectGifFps(gif) {
  const r = spawnSync(
    "ffprobe",
    [
      "-v",
      "error",
      "-select_streams",
      "v:0",
      "-show_entries",
      "stream=avg_frame_rate",
      "-of",
      "default=nokey=1:noprint_wrappers=1",
      gif,
    ],
    { encoding: "utf8" },
  );
  if (r.status !== 0) {
    return 12;
  }
  const raw = r.stdout.trim(); // e.g. "10/1"
  const [num, den] = raw.split("/").map((s) => Number.parseInt(s, 10));
  if (!Number.isFinite(num) || !Number.isFinite(den) || den === 0) {
    return 12;
  }
  return Math.max(1, Math.round(num / den));
}

function descriptionFor(state) {
  // Defaults that match the existing bundled avatar taxonomy. Artist can edit.
  const known = {
    neutral: "resting / listening",
    thinking: "processing",
    happy: "warm",
    sad: "sympathy",
    angry: "frustration",
    curious: "uncertain",
  };
  return known[state] ?? "";
}

function fail(msg) {
  console.error(`[gif-to-frames] ${msg}`);
  process.exit(1);
}
