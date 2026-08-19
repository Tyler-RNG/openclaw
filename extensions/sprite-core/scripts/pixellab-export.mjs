#!/usr/bin/env node
// PixelLab → SpriteCore atlas exporter.
//
// Downloads a finished pixellab.ai character bundle by UUID, extracts the per-
// animation PNG frames, packs them into a SpriteCore-compatible WebP atlas +
// manifest, and writes the result directly to the plugin-expected location:
//
//   <assetsDir>/avatars/<agentId>/
//   ├── <agentId>.atlas.webp
//   └── <agentId>.atlas.json
//
// Originally a Python script (`export_char4.py`) that lived outside the plugin
// tree. Ported here so the plugin owns the create→ship pipeline end-to-end and
// uses the same image stack (sharp) as `pnpm avatar:pack`.
//
// Usage:
//   node extensions/sprite-core/scripts/pixellab-export.mjs --uid <pixellab-uuid> [opts]
//
//   --uid <id>           PixelLab character UUID (required)
//   --name <text>        Override character name (otherwise read from the bundle metadata)
//   --agent-id <id>      Override agent id (otherwise derived from --name)
//   --assets-root <dir>  SpriteCore assets root (default: ~/.openclaw/state/assets/avatars)
//   --workspace <dir>    Scratch dir for the downloaded zip + extraction
//                        (default: <assets-root>/.pixellab-cache)
//   --skip-download      Use an existing zip in the workspace instead of fetching
//   --skip-extract       Use an already-extracted directory
//   --overwrite          Replace existing output without prompting
//   --dry-run            Print the plan without writing anything
//
// Auth: reads PIXELLAB_API_KEY from the environment. If unset, falls back to
// `pass show pixellab/api-key` when `pass` is on PATH (matches the upstream
// Python exporter's behavior). Operators on other secret stores can pipe a
// custom command via --api-key-command. Never commit the key.

import { execFileSync } from "node:child_process";
import { createWriteStream } from "node:fs";
import { mkdir, readFile, rm, stat, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { Readable } from "node:stream";
import { pipeline } from "node:stream/promises";
import { fileURLToPath } from "node:url";
import JSZip from "jszip";
import sharp from "sharp";

const PIXELLAB_API_BASE = "https://api.pixellab.ai/v2";
const ATLAS_COLS = 7; // matches the 1024×1024 / 136-px-frame layout used upstream
const DEFAULT_ATLAS_SIDE = 1024;

function parseArgs(argv) {
  const opts = {
    uid: null,
    name: null,
    agentId: null,
    assetsRoot: null,
    workspace: null,
    apiKeyCommand: null,
    skipDownload: false,
    skipExtract: false,
    overwrite: false,
    dryRun: false,
  };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    switch (arg) {
      case "--uid":
        opts.uid = argv[++i];
        break;
      case "--name":
        opts.name = argv[++i];
        break;
      case "--agent-id":
        opts.agentId = argv[++i];
        break;
      case "--assets-root":
        opts.assetsRoot = argv[++i];
        break;
      case "--workspace":
        opts.workspace = argv[++i];
        break;
      case "--api-key-command":
        opts.apiKeyCommand = argv[++i];
        break;
      case "--skip-download":
        opts.skipDownload = true;
        break;
      case "--skip-extract":
        opts.skipExtract = true;
        break;
      case "--overwrite":
        opts.overwrite = true;
        break;
      case "--dry-run":
        opts.dryRun = true;
        break;
      case "-h":
      case "--help":
        printUsage();
        process.exit(0);
        break;
      default:
        if (arg.startsWith("--")) {
          console.error(`Unknown option: ${arg}`);
          printUsage();
          process.exit(2);
        }
    }
  }
  if (!opts.uid) {
    console.error("Missing required --uid");
    printUsage();
    process.exit(2);
  }
  return opts;
}

function printUsage() {
  const here = path.relative(process.cwd(), fileURLToPath(import.meta.url));
  console.error(`Usage: node ${here} --uid <pixellab-uuid> [options]`);
  console.error("");
  console.error("  --uid <id>           PixelLab character UUID (required)");
  console.error("  --name <text>        Override character name from metadata");
  console.error("  --agent-id <id>      Override derived agent id");
  console.error(
    "  --assets-root <dir>  SpriteCore assets root (default: ~/.openclaw/state/assets/avatars)",
  );
  console.error("  --workspace <dir>    Scratch dir for download + extract");
  console.error("  --api-key-command <cmd>  Shell command whose stdout is the API key");
  console.error("                           (e.g. 'pass show pixellab/api-key'); fallback path");
  console.error("                           when PIXELLAB_API_KEY is unset");
  console.error("  --skip-download      Reuse existing zip in workspace");
  console.error("  --skip-extract       Reuse already-extracted directory");
  console.error("  --overwrite          Replace existing output");
  console.error("  --dry-run            Print the plan without writing");
}

function resolveApiKey(apiKeyCommand) {
  const fromEnv = process.env.PIXELLAB_API_KEY?.trim();
  if (fromEnv) {
    return fromEnv;
  }
  if (apiKeyCommand) {
    try {
      const out = execFileSync("sh", ["-c", apiKeyCommand], {
        encoding: "utf8",
        stdio: ["ignore", "pipe", "inherit"],
      }).trim();
      if (out) {
        return out;
      }
    } catch (err) {
      console.error(`--api-key-command failed: ${err.message}`);
      process.exit(1);
    }
  }
  // Fallback: Unix password-store. Matches the upstream Python exporter's
  // behavior on machines where `pass show pixellab/api-key` is already set up.
  try {
    const out = execFileSync("pass", ["show", "pixellab/api-key"], {
      encoding: "utf8",
      stdio: ["ignore", "pipe", "ignore"],
    }).trim();
    if (out) {
      return out;
    }
  } catch {
    // pass isn't installed or doesn't have that entry — fall through
  }
  console.error("No pixellab API key available.");
  console.error("  Either export PIXELLAB_API_KEY=<key> in the shell that runs this script,");
  console.error("  or pass --api-key-command '<cmd whose stdout is the key>',");
  console.error("  or put the key in `pass` as `pixellab/api-key`.");
  process.exit(1);
  return ""; // unreachable; keeps consistent-return happy
}

function resolveAssetsRoot(overrideArg) {
  if (overrideArg) {
    return path.resolve(overrideArg);
  }
  // Match SpriteCore's default: <state-dir>/assets/avatars under the user's
  // home. Operators with a custom assetsDir should pass --assets-root.
  return path.join(os.homedir(), ".openclaw", "state", "assets", "avatars");
}

function resolveWorkspace(overrideArg, assetsRoot) {
  if (overrideArg) {
    return path.resolve(overrideArg);
  }
  return path.join(assetsRoot, ".pixellab-cache");
}

function deriveAgentId(name) {
  return (
    name
      .toLowerCase()
      .replace(/\s+/g, "_")
      .replace(/-+/g, "_")
      .replace(/[^a-z0-9_]/g, "")
      .replace(/^_+|_+$/g, "")
      .slice(0, 50) || "character"
  );
}

async function pathExists(p) {
  try {
    await stat(p);
    return true;
  } catch {
    return false;
  }
}

/**
 * Fetch per-animation metadata from the pixellab API. Returns a map keyed by
 * the 8-char prefix of the animation_group_id (which matches the suffix on
 * each animation folder inside the zip), or null if the fetch fails. Used to
 * upgrade zip folder slugs like `warm_smile_bright_eyes_joyful-bca42649`
 * into clean canonical names (`happy`) and to surface `display_name` as a
 * description in the generated config snippet.
 */
async function fetchAnimationMetadata({ uid, apiKey }) {
  if (!apiKey || apiKey === "dry-run-noop") {
    return null;
  }
  let res;
  try {
    res = await fetch(`${PIXELLAB_API_BASE}/characters/${encodeURIComponent(uid)}/animations`, {
      headers: { Authorization: `Bearer ${apiKey}` },
    });
  } catch (err) {
    console.warn(`  animation metadata fetch failed (${err.message}) — falling back to slug names`);
    return null;
  }
  if (!res.ok) {
    console.warn(`  animation metadata fetch returned ${res.status} — falling back to slug names`);
    return null;
  }
  let body;
  try {
    body = await res.json();
  } catch {
    return null;
  }
  if (!Array.isArray(body?.animations)) {
    return null;
  }
  const map = new Map();
  for (const a of body.animations) {
    if (!a?.animation_group_id || typeof a.animation_group_id !== "string") {
      continue;
    }
    const prefix = a.animation_group_id.slice(0, 8).toLowerCase();
    map.set(prefix, {
      animationType: typeof a.animation_type === "string" ? a.animation_type : null,
      displayName:
        typeof a.display_name === "string" && a.display_name.trim() ? a.display_name.trim() : null,
      frameCount: a.directions?.[0]?.frame_count ?? 0,
    });
  }
  return map;
}

/**
 * Prettify a zip animation slug for use as a description.
 * `warm_smile_bright_eyes_joyful_expression` → `warm smile bright eyes joyful expression`
 */
function prettifySlug(slug) {
  return slug.replace(/_+/g, " ").trim();
}

async function downloadCharacterZip({ uid, apiKey, zipPath, skipDownload }) {
  if (skipDownload) {
    if (!(await pathExists(zipPath))) {
      console.error(`--skip-download requested but ${zipPath} does not exist`);
      process.exit(1);
    }
    console.log(`  reusing existing zip: ${zipPath}`);
    return;
  }
  console.log(`  fetching pixellab character ${uid}…`);
  const url = `${PIXELLAB_API_BASE}/characters/${encodeURIComponent(uid)}/zip`;
  const res = await fetch(url, {
    headers: { Authorization: `Bearer ${apiKey}` },
  });
  if (!res.ok) {
    const body = await res.text().catch(() => "");
    console.error(`pixellab API error: ${res.status} ${res.statusText}`);
    if (body) {
      console.error(body.slice(0, 500));
    }
    process.exit(1);
  }
  if (!res.body) {
    console.error("pixellab API returned an empty body");
    process.exit(1);
  }
  await pipeline(Readable.fromWeb(res.body), createWriteStream(zipPath));
  const size = (await stat(zipPath)).size;
  if (size === 0) {
    console.error("downloaded zip is empty");
    process.exit(1);
  }
  console.log(`  downloaded ${size.toLocaleString()} bytes`);
}

async function extractZip({ zipPath, extractDir, skipExtract, overwrite }) {
  if (skipExtract) {
    if (!(await pathExists(extractDir))) {
      console.error(`--skip-extract requested but ${extractDir} does not exist`);
      process.exit(1);
    }
    console.log(`  reusing existing extraction: ${extractDir}`);
    return;
  }
  if (await pathExists(extractDir)) {
    if (!overwrite) {
      console.error(`extraction dir already exists: ${extractDir}`);
      console.error("re-run with --overwrite or --skip-extract");
      process.exit(1);
    }
    await rm(extractDir, { recursive: true, force: true });
  }
  await mkdir(extractDir, { recursive: true });
  const zipBytes = await readFile(zipPath);
  const zip = await JSZip.loadAsync(zipBytes);
  const entries = Object.values(zip.files);
  for (const entry of entries) {
    const dest = path.join(extractDir, entry.name);
    if (entry.dir) {
      await mkdir(dest, { recursive: true });
      continue;
    }
    await mkdir(path.dirname(dest), { recursive: true });
    const buf = await entry.async("nodebuffer");
    await writeFile(dest, buf);
  }
  console.log(`  extracted ${entries.length} entries to ${extractDir}`);
}

async function detectCharacterName(extractDir, fallback) {
  const metaPath = path.join(extractDir, "metadata.json");
  if (!(await pathExists(metaPath))) {
    return fallback || "character";
  }
  try {
    const meta = JSON.parse(await readFile(metaPath, "utf8"));
    // PixelLab bundles nest the character under `character.name`. Top-level
    // `name` is a fallback in case the shape ever flattens.
    const candidates = [meta?.character?.name, meta?.name];
    for (const candidate of candidates) {
      if (typeof candidate === "string" && candidate.trim().length > 0) {
        const trimmed = candidate.trim();
        return trimmed.length > 50 ? trimmed.slice(0, 50) : trimmed;
      }
    }
  } catch {
    // fall through to fallback
  }
  return fallback || "character";
}

/**
 * Walk the extracted bundle's animations/ tree, split each folder into
 * `<slug>-<short-hash>`, and (if we have API metadata) upgrade the state
 * name from the verbose slug to the clean canonical `animation_type`.
 * Duplicate canonical names get `_2`, `_3`, … suffixes so they stay
 * distinct in the atlas manifest.
 */
async function detectAnimationDirs(extractDir, apiMetadata) {
  const animationsRoot = path.join(extractDir, "animations");
  if (!(await pathExists(animationsRoot))) {
    return [];
  }
  const { readdir } = await import("node:fs/promises");
  const dirents = await readdir(animationsRoot, { withFileTypes: true });

  const raw = [];
  for (const dirent of dirents) {
    if (!dirent.isDirectory()) {
      continue;
    }
    const folder = dirent.name;
    // `<slug>-<8-char-hash>` — split from the right so slugs with dashes
    // don't clobber the hash.
    const lastDash = folder.lastIndexOf("-");
    const slug = lastDash >= 0 ? folder.slice(0, lastDash) : folder;
    const hashPrefix = lastDash >= 0 ? folder.slice(lastDash + 1).toLowerCase() : "";
    const facingDir = path.join(animationsRoot, folder, "south");
    if (!(await pathExists(facingDir))) {
      continue;
    }

    const apiEntry = apiMetadata?.get(hashPrefix) ?? null;
    const cleanName = apiEntry?.animationType?.trim() || slug;
    const description = apiEntry?.displayName || prettifySlug(slug);
    raw.push({
      dir: facingDir,
      slug,
      cleanName,
      description,
      frameCount: apiEntry?.frameCount ?? 0,
    });
  }

  // Sort deterministically so re-runs produce the same output order.
  raw.sort((a, b) => a.slug.localeCompare(b.slug));

  // Dedupe canonical names — two animations with the same `animation_type`
  // (common when a character has both "held pose" and "transition-to"
  // variants) collide on the SpriteCore state key. Suffix each collision
  // with _2/_3/... so the manifest stays valid. (A smarter follow-up is
  // phased-state pairing; left for v2.)
  const seen = new Map();
  for (const entry of raw) {
    const count = (seen.get(entry.cleanName) ?? 0) + 1;
    seen.set(entry.cleanName, count);
    entry.name = count === 1 ? entry.cleanName : `${entry.cleanName}_${count}`;
  }

  return raw.map((e) => ({
    name: e.name,
    dir: e.dir,
    description: e.description,
  }));
}

async function listFrames(animDir) {
  const { readdir } = await import("node:fs/promises");
  const entries = await readdir(animDir);
  return entries
    .filter((f) => /^frame_.*\.png$/i.test(f))
    .toSorted()
    .map((f) => path.join(animDir, f));
}

async function processAnimation({ name, description, frames, framesOutDir }) {
  if (frames.length === 0) {
    console.log(`  ${name}: no frames, skipping`);
    return null;
  }
  await mkdir(framesOutDir, { recursive: true });
  const frameKeys = [];
  for (let i = 0; i < frames.length; i++) {
    const dest = path.join(framesOutDir, `${String(i).padStart(2, "0")}.webp`);
    try {
      await sharp(frames[i]).webp({ quality: 95 }).toFile(dest);
      frameKeys.push(`${name}/${String(i).padStart(2, "0")}`);
    } catch (err) {
      console.warn(`    failed to convert ${frames[i]}: ${err.message}`);
    }
  }
  if (frameKeys.length === 0) {
    return null;
  }
  // Match the upstream Python heuristic: thinking-style states play once and
  // hold the final frame; everything else loops.
  const isHoldOnce = name === "thinking" || name === "contemplative";
  return {
    name,
    description,
    frameCount: frames.length,
    fps: frames.length > 5 ? 10 : 15,
    loop: isHoldOnce ? "once" : "infinite",
    holdLastFrame: isHoldOnce,
    frameKeys,
  };
}

async function buildAtlas({ framesRoot, atlasOut }) {
  // Walk frames in stable order (sorted animation dirs, sorted webp files)
  // so the manifest's frame rect lookup matches the on-disk layout. Mirrors
  // the Python packer's grid algorithm exactly so existing manifests remain
  // bit-stable when re-run.
  const { readdir } = await import("node:fs/promises");
  const allFrames = [];
  const animDirs = (await readdir(framesRoot)).toSorted();
  for (const animDir of animDirs) {
    const abs = path.join(framesRoot, animDir);
    const st = await stat(abs);
    if (!st.isDirectory()) {
      continue;
    }
    const files = (await readdir(abs)).filter((f) => f.endsWith(".webp")).toSorted();
    for (const f of files) {
      allFrames.push(path.join(abs, f));
    }
  }
  if (allFrames.length === 0) {
    throw new Error("no frames available for atlas packing");
  }

  const firstMeta = await sharp(allFrames[0]).metadata();
  const frameW = firstMeta.width ?? 0;
  const frameH = firstMeta.height ?? 0;
  if (!frameW || !frameH) {
    throw new Error(`could not read frame dimensions from ${allFrames[0]}`);
  }

  const cols = ATLAS_COLS;
  const rows = Math.ceil(allFrames.length / cols);
  const atlasW = DEFAULT_ATLAS_SIDE;
  const atlasH = Math.max(DEFAULT_ATLAS_SIDE, rows * frameH);

  console.log(
    `  packing ${allFrames.length} frames into ${atlasW}x${atlasH} (${cols} cols × ${rows} rows, frame ${frameW}x${frameH})`,
  );

  const composites = await Promise.all(
    allFrames.map(async (frame, i) => ({
      input: await readFile(frame),
      left: (i % cols) * frameW,
      top: Math.floor(i / cols) * frameH,
    })),
  );

  await sharp({
    create: {
      width: atlasW,
      height: atlasH,
      channels: 4,
      background: { r: 0, g: 0, b: 0, alpha: 0 },
    },
  })
    .composite(composites)
    .webp({ quality: 95 })
    .toFile(atlasOut);

  return { frameW, frameH, atlasW, atlasH };
}

function buildManifest({ agentId, animations, frameSize, atlasSize }) {
  // Map each frame key to its rect in the packed atlas. Iteration order
  // mirrors buildAtlas() so rect coords match where sharp wrote the bytes.
  const allFrameKeys = [];
  for (const a of animations) {
    allFrameKeys.push(...a.frameKeys);
  }
  const cols = ATLAS_COLS;
  const frames = {};
  for (let i = 0; i < allFrameKeys.length; i++) {
    frames[allFrameKeys[i]] = {
      x: (i % cols) * frameSize.w,
      y: Math.floor(i / cols) * frameSize.h,
      w: frameSize.w,
      h: frameSize.h,
    };
  }
  const animationsMap = {};
  for (const a of animations) {
    const entry = {
      frames: a.frameKeys,
      fps: a.fps,
      loop: a.loop,
    };
    if (a.holdLastFrame) {
      entry.holdLastFrame = true;
    }
    animationsMap[a.name] = entry;
  }
  return {
    version: 1,
    agent: agentId,
    image: `${agentId}.atlas.webp`,
    size: { w: atlasSize.w, h: atlasSize.h },
    frameSize: { w: frameSize.w, h: frameSize.h },
    frames,
    animations: animationsMap,
    transitions: {
      "*->thinking": "thinking.intro",
      "thinking->*": "thinking.outro",
    },
  };
}

async function main() {
  const opts = parseArgs(process.argv.slice(2));
  const apiKey = opts.dryRun ? "dry-run-noop" : resolveApiKey(opts.apiKeyCommand);
  const assetsRoot = resolveAssetsRoot(opts.assetsRoot);
  const workspace = resolveWorkspace(opts.workspace, assetsRoot);

  const zipPath = path.join(workspace, `${opts.uid}.zip`);
  const extractDir = path.join(workspace, `${opts.uid}_extracted`);

  console.log(`pixellab export ${opts.uid}`);
  console.log(`  assets root: ${assetsRoot}`);
  console.log(`  workspace:   ${workspace}`);
  if (opts.dryRun) {
    console.log("  (dry run — no files will be written)");
  }

  await mkdir(workspace, { recursive: true });

  if (opts.dryRun) {
    console.log("\nplan:");
    console.log(`  1. download zip → ${zipPath}`);
    console.log(`  2. extract     → ${extractDir}`);
    console.log("  3. detect animations under animations/<anim>-<seed>/south/");
    console.log("  4. convert frames PNG → WebP, pack into atlas");
    console.log("  5. write manifest + atlas to <assets-root>/<agentId>/");
    return 0;
  }

  await downloadCharacterZip({ uid: opts.uid, apiKey, zipPath, skipDownload: opts.skipDownload });
  await extractZip({
    zipPath,
    extractDir,
    skipExtract: opts.skipExtract,
    overwrite: opts.overwrite,
  });

  const charName = opts.name ?? (await detectCharacterName(extractDir, "character"));
  const agentId = opts.agentId ?? deriveAgentId(charName);
  console.log(`  character name: ${charName}`);
  console.log(`  agent id:       ${agentId}`);

  // Fetch per-animation metadata from the pixellab API so we can use clean
  // canonical names (`happy`, `sad`, `thinking`) in the SpriteCore manifest
  // instead of the verbose zip folder slugs. Best-effort — falls back to
  // slug names if the fetch fails or the key isn't available.
  console.log("  fetching animation metadata from pixellab…");
  const apiMetadata = await fetchAnimationMetadata({ uid: opts.uid, apiKey });
  if (apiMetadata) {
    console.log(`  got clean names for ${apiMetadata.size} animation group(s)`);
  }

  const animDirs = await detectAnimationDirs(extractDir, apiMetadata);
  if (animDirs.length === 0) {
    console.error("no animation directories found in the extracted bundle");
    return 1;
  }
  console.log(`  animations:     ${animDirs.map((a) => a.name).join(", ")}`);

  const agentAssetsDir = path.join(assetsRoot, agentId);
  const framesRoot = path.join(agentAssetsDir, "frames");
  if (await pathExists(agentAssetsDir)) {
    if (!opts.overwrite) {
      console.error(`agent assets dir already exists: ${agentAssetsDir}`);
      console.error("re-run with --overwrite to replace");
      return 1;
    }
    await rm(agentAssetsDir, { recursive: true, force: true });
  }
  await mkdir(framesRoot, { recursive: true });

  const animations = [];
  for (const { name, dir, description } of animDirs) {
    const frames = await listFrames(dir);
    const result = await processAnimation({
      name,
      description,
      frames,
      framesOutDir: path.join(framesRoot, name),
    });
    if (result) {
      animations.push(result);
    }
  }
  if (animations.length === 0) {
    console.error("no animations produced any usable frames");
    return 1;
  }

  const atlasOut = path.join(agentAssetsDir, `${agentId}.atlas.webp`);
  const { frameW, frameH, atlasW, atlasH } = await buildAtlas({
    framesRoot,
    atlasOut,
  });

  const manifest = buildManifest({
    agentId,
    animations,
    frameSize: { w: frameW, h: frameH },
    atlasSize: { w: atlasW, h: atlasH },
  });
  const manifestOut = path.join(agentAssetsDir, `${agentId}.atlas.json`);
  await writeFile(manifestOut, `${JSON.stringify(manifest, null, 2)}\n`);

  console.log("");
  console.log(`✓ atlas:    ${atlasOut}`);
  console.log(`✓ manifest: ${manifestOut}`);
  console.log("");
  // Pick the most idle-looking default state. Prefer explicit "idle", else
  // the first animation after sorting.
  const defaultState = animations.find((a) => a.name === "idle")?.name ?? animations[0].name;

  console.log('paste into openclaw.json under plugins.entries["sprite-core"].config.agents:');
  console.log(
    JSON.stringify(
      {
        [agentId]: {
          avatar: {
            kind: "atlas",
            default: defaultState,
            manifest: `avatars/${agentId}/${agentId}.atlas.json`,
          },
          prompting: {
            descriptions: Object.fromEntries(
              animations.map((a) => [a.name, a.description || a.name]),
            ),
          },
        },
      },
      null,
      2,
    ),
  );
  return 0;
}

main()
  .then((code) => process.exit(code ?? 0))
  .catch((err) => {
    console.error(err.stack || err.message || String(err));
    process.exit(1);
  });
