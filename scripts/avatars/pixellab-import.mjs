#!/usr/bin/env node
// Compatibility shim. The real pixellab.ai → SpriteCore exporter lives in the
// plugin tree at extensions/sprite-core/scripts/pixellab-export.mjs. Forward
// arguments straight through so existing call sites keep working.

import { spawn } from "node:child_process";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const target = path.resolve(here, "../../extensions/sprite-core/scripts/pixellab-export.mjs");

const child = spawn(process.execPath, [target, ...process.argv.slice(2)], {
  stdio: "inherit",
  env: process.env,
});
child.on("exit", (code) => process.exit(code ?? 0));
child.on("error", (err) => {
  console.error(err.message);
  process.exit(1);
});
