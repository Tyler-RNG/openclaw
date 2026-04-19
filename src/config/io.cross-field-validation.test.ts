import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { writeConfigFile } from "./io.js";

// Lock-in test: writeConfigFile MUST refuse cross-field invalid combinations
// before they land on disk. This session's first breakage came from an
// external writer committing `gateway.bind: "0.0.0.0"` + `tailscale.mode:
// "serve"` — a combo the validator rejects. openclaw can't stop external
// writes, but every write that DOES go through this codepath should fail
// closed rather than persist an invalid file.
describe("writeConfigFile cross-field validation (transactional guarantee)", () => {
  let tempDir: string;
  let originalHome: string | undefined;
  let originalConfigHome: string | undefined;

  beforeEach(async () => {
    tempDir = await fs.promises.mkdtemp(path.join(os.tmpdir(), "openclaw-writecfg-"));
    originalHome = process.env.HOME;
    originalConfigHome = process.env.OPENCLAW_CONFIG_HOME;
    process.env.HOME = tempDir;
    delete process.env.OPENCLAW_CONFIG_HOME;
  });

  afterEach(async () => {
    if (originalHome === undefined) {
      delete process.env.HOME;
    } else {
      process.env.HOME = originalHome;
    }
    if (originalConfigHome === undefined) {
      delete process.env.OPENCLAW_CONFIG_HOME;
    } else {
      process.env.OPENCLAW_CONFIG_HOME = originalConfigHome;
    }
    await fs.promises.rm(tempDir, { recursive: true, force: true });
  });

  it("rejects gateway.bind=0.0.0.0 when tailscale.mode=serve before writing to disk", async () => {
    const configDir = path.join(tempDir, ".openclaw");
    await fs.promises.mkdir(configDir, { recursive: true });
    const configPath = path.join(configDir, "openclaw.json");

    await expect(
      writeConfigFile({
        gateway: {
          port: 18789,
          mode: "local",
          bind: "0.0.0.0" as unknown as "lan" | "loopback",
          tailscale: { mode: "serve" },
        },
      }),
    ).rejects.toThrow(/tailscale|bind/i);

    const landed = await fs.promises
      .readFile(configPath, "utf8")
      .then((c) => c as string | null)
      .catch(() => null);
    if (landed !== null) {
      expect(landed).not.toContain('"0.0.0.0"');
    }
  });
});
