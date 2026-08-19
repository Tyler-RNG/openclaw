#!/usr/bin/env node
// Create a 4-direction character on pixellab.ai.
//
// Port of pixellab_create_character_v2.py. Hits POST /v2/create-character-with-4-directions,
// polls the background job until the character is ready, then prints the new
// character_id plus the four rotation URLs so the operator can eyeball the
// look before running the animation pipeline.
//
// Flow (full):
//   1. pixellab-create.mjs --name <n> --description <d>   ← this script
//   2. operator inspects the rotation URLs (or the pixellab.ai UI)
//   3. if approved, run the animation generator (user-supplied; TBD)
//   4. pixellab-export.mjs --uid <character-id> --overwrite
//
// Usage:
//   node extensions/sprite-core/scripts/pixellab-create.mjs \
//     --name "elf" --description "a magical elf with pointed ears"
//
//   --name <n>          Character name (required; will be folded into the description because
//                        the pixellab API does not accept a separate name field)
//   --description <d>   Character description / prompt (required)
//   --width <n>         Pixel width (default: 96)
//   --height <n>        Pixel height (default: 96)
//   --timeout-ms <n>    Max time to wait for the background job (default: 300000 ms)
//   --api-key-command <cmd>  Shell command whose stdout is the API key
//   --dry-run           Print the request without sending it
//   --json              Emit just the resulting character_id + rotation_urls as JSON
//                        (useful when chaining into other scripts)
//
// Auth: PIXELLAB_API_KEY env → --api-key-command <cmd> → `pass show pixellab/api-key`.

import { execFileSync } from "node:child_process";
import path from "node:path";
import { fileURLToPath } from "node:url";

const PIXELLAB_API_BASE = "https://api.pixellab.ai/v2";
const DEFAULT_TIMEOUT_MS = 300_000;
const POLL_INTERVAL_MS = 3_000;

function parseArgs(argv) {
  const opts = {
    name: null,
    description: null,
    width: 96,
    height: 96,
    timeoutMs: DEFAULT_TIMEOUT_MS,
    apiKeyCommand: null,
    dryRun: false,
    json: false,
  };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    switch (arg) {
      case "-n":
      case "--name":
        opts.name = argv[++i];
        break;
      case "-d":
      case "--description":
        opts.description = argv[++i];
        break;
      case "--width":
        opts.width = parseInt(argv[++i], 10);
        break;
      case "--height":
        opts.height = parseInt(argv[++i], 10);
        break;
      case "--timeout-ms":
        opts.timeoutMs = parseInt(argv[++i], 10);
        break;
      case "--api-key-command":
        opts.apiKeyCommand = argv[++i];
        break;
      case "--dry-run":
        opts.dryRun = true;
        break;
      case "--json":
        opts.json = true;
        break;
      case "-h":
      case "--help":
        printUsage();
        process.exit(0);
        break;
      default:
        if (arg.startsWith("-")) {
          console.error(`Unknown option: ${arg}`);
          printUsage();
          process.exit(2);
        }
    }
  }
  if (!opts.name || !opts.description) {
    console.error("Both --name and --description are required");
    printUsage();
    process.exit(2);
  }
  if (!Number.isFinite(opts.width) || opts.width <= 0) {
    opts.width = 96;
  }
  if (!Number.isFinite(opts.height) || opts.height <= 0) {
    opts.height = 96;
  }
  return opts;
}

function printUsage() {
  const here = path.relative(process.cwd(), fileURLToPath(import.meta.url));
  console.error(`Usage: node ${here} --name <n> --description <d> [options]`);
  console.error("");
  console.error("  -n, --name <n>        Character name (required)");
  console.error("  -d, --description <d> Character description / prompt (required)");
  console.error("  --width <n>           Pixel width (default: 96)");
  console.error("  --height <n>          Pixel height (default: 96)");
  console.error("  --timeout-ms <n>      Max background-job wait (default: 300000)");
  console.error("  --api-key-command <cmd>  Shell command that prints the API key on stdout");
  console.error("  --dry-run             Print the request without sending");
  console.error("  --json                Emit character_id + rotations as JSON only");
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
  try {
    const out = execFileSync("pass", ["show", "pixellab/api-key"], {
      encoding: "utf8",
      stdio: ["ignore", "pipe", "ignore"],
    }).trim();
    if (out) {
      return out;
    }
  } catch {
    // fall through
  }
  console.error("No pixellab API key available.");
  console.error("  Set PIXELLAB_API_KEY, pass --api-key-command, or put the key in `pass`.");
  process.exit(1);
  return ""; // unreachable; keeps consistent-return happy
}

async function createCharacter({ apiKey, fullDescription, width, height }) {
  const res = await fetch(`${PIXELLAB_API_BASE}/create-character-with-4-directions`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${apiKey}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      description: fullDescription,
      image_size: { width, height },
    }),
  });
  if (!res.ok) {
    const body = await res.text().catch(() => "");
    throw new Error(
      `pixellab create failed: HTTP ${res.status} ${res.statusText}\n${body.slice(0, 500)}`,
    );
  }
  return res.json();
}

async function pollJob({ apiKey, jobId, timeoutMs }) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const res = await fetch(`${PIXELLAB_API_BASE}/background-jobs/${encodeURIComponent(jobId)}`, {
      headers: { Authorization: `Bearer ${apiKey}` },
    });
    if (res.ok) {
      const body = await res.json();
      const status = body?.status;
      if (status === "completed") {
        return body;
      }
      if (status === "failed") {
        throw new Error(`pixellab job ${jobId} failed: ${JSON.stringify(body).slice(0, 500)}`);
      }
    }
    await new Promise((r) => setTimeout(r, POLL_INTERVAL_MS));
  }
  throw new Error(`pixellab job ${jobId} did not complete within ${timeoutMs}ms`);
}

async function fetchCharacterDetail({ apiKey, charId }) {
  const res = await fetch(`${PIXELLAB_API_BASE}/characters/${encodeURIComponent(charId)}`, {
    headers: { Authorization: `Bearer ${apiKey}` },
  });
  if (!res.ok) {
    return null;
  }
  return res.json().catch(() => null);
}

function log(opts, ...args) {
  if (opts.json) {
    return;
  } // keep stdout clean for JSON output
  console.log(...args);
}

async function main() {
  const opts = parseArgs(process.argv.slice(2));

  // The pixellab API does not accept a separate `name` field — it stores
  // whatever you send as `description` in BOTH the `name` and `prompt` slots.
  // Fold the name into the description so the generated character at least
  // surfaces "<name>: <desc>" in the UI.
  const fullDescription = `${opts.name}: ${opts.description}`;

  log(opts, `pixellab create: ${opts.name}`);
  log(opts, `  description:  ${fullDescription}`);
  log(opts, `  size:         ${opts.width}×${opts.height}`);

  if (opts.dryRun) {
    const payload = {
      endpoint: `${PIXELLAB_API_BASE}/create-character-with-4-directions`,
      body: {
        description: fullDescription,
        image_size: { width: opts.width, height: opts.height },
      },
    };
    console.log(JSON.stringify(payload, null, 2));
    return 0;
  }

  const apiKey = resolveApiKey(opts.apiKeyCommand);

  log(opts, "  queueing character creation…");
  const created = await createCharacter({
    apiKey,
    fullDescription,
    width: opts.width,
    height: opts.height,
  });
  const charId = created.character_id;
  const jobId = created.background_job_id;
  if (!charId || !jobId) {
    console.error("pixellab response missing character_id or background_job_id");
    console.error(JSON.stringify(created, null, 2));
    return 1;
  }
  log(opts, `  character id: ${charId}`);
  log(opts, `  job id:       ${jobId}`);

  log(opts, `  waiting up to ${Math.round(opts.timeoutMs / 1000)}s for the job to finish…`);
  await pollJob({ apiKey, jobId, timeoutMs: opts.timeoutMs });

  // Pull detail + rotation URLs so the operator can eyeball the 4 sides
  // before moving on to the animation step.
  const detail = await fetchCharacterDetail({ apiKey, charId });
  const rotations = detail?.rotation_urls ?? {};

  if (opts.json) {
    console.log(
      JSON.stringify(
        { character_id: charId, name: detail?.name ?? fullDescription, rotations },
        null,
        2,
      ),
    );
    return 0;
  }

  console.log("");
  console.log(`✓ character created`);
  console.log(`  id:           ${charId}`);
  if (Object.keys(rotations).length > 0) {
    console.log("  rotations:");
    for (const [dir, url] of Object.entries(rotations)) {
      if (typeof url === "string" && url) {
        console.log(`    ${dir.padEnd(11)} ${url}`);
      }
    }
  }
  console.log("");
  console.log("next steps:");
  console.log("  1. review the rotations above (open them in a browser)");
  console.log("  2. if you're happy with the look, add animations");
  console.log("     (use the create-animations pipeline — separate script)");
  console.log("  3. export for SpriteCore:");
  console.log(
    `     node extensions/sprite-core/scripts/pixellab-export.mjs --uid ${charId} --overwrite`,
  );
  return 0;
}

main()
  .then((code) => process.exit(code ?? 0))
  .catch((err) => {
    console.error(err.stack || err.message || String(err));
    process.exit(1);
  });
