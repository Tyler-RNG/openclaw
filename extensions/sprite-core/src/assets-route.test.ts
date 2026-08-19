import fs from "node:fs/promises";
import type { IncomingMessage, ServerResponse } from "node:http";
import os from "node:os";
import path from "node:path";
import { PassThrough } from "node:stream";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { handleAssetsRequest } from "./assets-route.js";

function makeReq(url: string, method = "GET"): IncomingMessage {
  return { url, method, headers: {} } as unknown as IncomingMessage;
}

type FakeResponse = PassThrough & {
  statusCode: number;
  headers: Record<string, string | number>;
  body: string;
  setHeader: (name: string, value: string | number) => void;
  headersSent: boolean;
};

function makeRes(): FakeResponse {
  const res = new PassThrough() as unknown as FakeResponse;
  res.statusCode = 200;
  res.headers = {};
  res.body = "";
  res.headersSent = false;
  res.setHeader = (name: string, value: string | number) => {
    res.headers[name] = value;
  };
  res.on("data", (chunk: Buffer) => {
    res.body += chunk.toString();
    res.headersSent = true;
  });
  return res;
}

describe("sprite-core assets-route", () => {
  let tmpDir: string;

  beforeEach(async () => {
    tmpDir = await fs.mkdtemp(path.join(os.tmpdir(), "sprite-core-assets-"));
    await fs.mkdir(path.join(tmpDir, "avatars"), { recursive: true });
    await fs.writeFile(path.join(tmpDir, "avatars", "ok.webp"), Buffer.from([0x52, 0x49]));
    await fs.writeFile(path.join(tmpDir, "secret.txt"), "leak");
  });

  afterEach(async () => {
    await fs.rm(tmpDir, { recursive: true, force: true });
  });

  it("returns 200 for a valid asset path", async () => {
    const res = makeRes();
    const handled = await handleAssetsRequest(
      makeReq("/openclaw-assets/avatars/ok.webp"),
      res as unknown as ServerResponse,
      { config: { enabled: true, assetsDir: tmpDir } },
    );
    expect(handled).toBe(true);
    expect(res.statusCode).toBe(200);
    expect(res.headers["Content-Type"]).toBe("image/webp");
    expect(res.headers["ETag"]).toBeDefined();
  });

  it("rejects path traversal via ../ (URL normalization strips it; handler returns false so gateway 404s)", async () => {
    // `new URL()` normalizes `/openclaw-assets/../secret.txt` to `/secret.txt`,
    // which is outside the plugin's route. The handler returns false and the
    // gateway dispatcher serves its own 404. No secret leaks either way.
    const res = makeRes();
    const handled = await handleAssetsRequest(
      makeReq("/openclaw-assets/../secret.txt"),
      res as unknown as ServerResponse,
      { config: { enabled: true, assetsDir: path.join(tmpDir, "avatars") } },
    );
    expect(handled).toBe(false);
  });

  it("rejects symlinks pointing outside assetsDir", async () => {
    const assetsDir = path.join(tmpDir, "avatars");
    const symlinkInside = path.join(assetsDir, "evil-link.txt");
    try {
      await fs.symlink(path.join(tmpDir, "secret.txt"), symlinkInside);
    } catch {
      return;
    }
    const res = makeRes();
    await handleAssetsRequest(
      makeReq("/openclaw-assets/evil-link.txt"),
      res as unknown as ServerResponse,
      { config: { enabled: true, assetsDir } },
    );
    expect(res.statusCode).toBe(403);
  });

  it("rejects nested subdir traversal that resolves outside assetsDir", async () => {
    await fs.mkdir(path.join(tmpDir, "avatars", "nested"), { recursive: true });
    await fs.writeFile(path.join(tmpDir, "avatars", "nested", "inner.txt"), "inner");
    // The relative path crosses into another dir. URL won't collapse this one
    // because the `..` is inside a segment boundary and stays encoded via the
    // alternate form `%2E%2E`. validateAssetPath's relative-path check catches it.
    const res = makeRes();
    await handleAssetsRequest(
      makeReq("/openclaw-assets/nested/%2E%2E/%2E%2E/secret.txt"),
      res as unknown as ServerResponse,
      { config: { enabled: true, assetsDir: path.join(tmpDir, "avatars") } },
    );
    // Either rejected outright (403/400) or URL-normalized away (handler returns false).
    // Either outcome prevents the read.
    expect(res.statusCode === 403 || res.statusCode === 400 || res.body === "").toBe(true);
  });

  it("rejects dotfile access", async () => {
    await fs.writeFile(path.join(tmpDir, ".hidden"), "nope");
    const res = makeRes();
    await handleAssetsRequest(
      makeReq("/openclaw-assets/.hidden"),
      res as unknown as ServerResponse,
      { config: { enabled: true, assetsDir: tmpDir } },
    );
    expect(res.statusCode).toBe(403);
  });

  it("rejects null bytes in the path", async () => {
    const res = makeRes();
    await handleAssetsRequest(
      makeReq("/openclaw-assets/ok%00.webp"),
      res as unknown as ServerResponse,
      { config: { enabled: true, assetsDir: tmpDir } },
    );
    expect(res.statusCode).toBe(400);
  });

  it("returns 404 for missing files", async () => {
    const res = makeRes();
    await handleAssetsRequest(
      makeReq("/openclaw-assets/does-not-exist.webp"),
      res as unknown as ServerResponse,
      { config: { enabled: true, assetsDir: tmpDir } },
    );
    expect(res.statusCode).toBe(404);
  });

  it("returns 405 for non-GET/HEAD methods", async () => {
    const res = makeRes();
    await handleAssetsRequest(
      makeReq("/openclaw-assets/avatars/ok.webp", "POST"),
      res as unknown as ServerResponse,
      { config: { enabled: true, assetsDir: tmpDir } },
    );
    expect(res.statusCode).toBe(405);
    expect(res.headers["Allow"]).toBe("GET, HEAD");
  });

  it("enforces maxAssetSizeBytes", async () => {
    const res = makeRes();
    await handleAssetsRequest(
      makeReq("/openclaw-assets/avatars/ok.webp"),
      res as unknown as ServerResponse,
      { config: { enabled: true, assetsDir: tmpDir, maxAssetSizeBytes: 1 } },
    );
    expect(res.statusCode).toBe(413);
  });

  it("returns false when plugin is disabled", async () => {
    const res = makeRes();
    const handled = await handleAssetsRequest(
      makeReq("/openclaw-assets/avatars/ok.webp"),
      res as unknown as ServerResponse,
      { config: { enabled: false, assetsDir: tmpDir } },
    );
    expect(handled).toBe(false);
  });

  it("returns false for non-assets paths", async () => {
    const res = makeRes();
    const handled = await handleAssetsRequest(
      makeReq("/something-else"),
      res as unknown as ServerResponse,
      { config: { enabled: true, assetsDir: tmpDir } },
    );
    expect(handled).toBe(false);
  });

  it("returns 304 on ETag match", async () => {
    // Compute the expected ETag directly from the file's stat so we don't have
    // to exercise the stream-pipe path in the first request (fake res isn't a
    // real Writable).
    const stat = await fs.stat(path.join(tmpDir, "avatars", "ok.webp"));
    const expectedEtag = `"${stat.mtimeMs.toString(36)}-${stat.size.toString(36)}"`;

    const res = makeRes();
    const req = makeReq("/openclaw-assets/avatars/ok.webp");
    (req as unknown as { headers: Record<string, string> }).headers = {
      "if-none-match": expectedEtag,
    };
    await handleAssetsRequest(req, res as unknown as ServerResponse, {
      config: { enabled: true, assetsDir: tmpDir },
    });
    expect(res.statusCode).toBe(304);
  });
});
