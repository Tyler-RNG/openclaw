import fs from "node:fs/promises";
import type { IncomingMessage } from "node:http";
import os from "node:os";
import path from "node:path";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import type { ResolvedGatewayAuth } from "./auth.js";
import { handleAssetsHttpRequest, isAssetsHttpPath } from "./assets-http.js";
import { makeMockHttpResponse } from "./test-http-response.js";
import { withTempConfig } from "./test-temp-config.js";

const AUTH_NONE: ResolvedGatewayAuth = {
  mode: "none",
  token: undefined,
  password: undefined,
  allowTailscale: false,
};

const AUTH_TOKEN: ResolvedGatewayAuth = {
  mode: "token",
  token: "test-token",
  password: undefined,
  allowTailscale: false,
};

type FixtureDirs = { root: string; assetsDir: string };

async function makeAssetsFixture(prefix: string): Promise<FixtureDirs> {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), prefix));
  const assetsDir = path.join(root, "assets");
  await fs.mkdir(assetsDir, { recursive: true });
  await fs.writeFile(path.join(assetsDir, "hello.gif"), "GIF89a fake bytes");
  await fs.mkdir(path.join(assetsDir, "sub"), { recursive: true });
  await fs.writeFile(path.join(assetsDir, "sub", "inner.png"), "PNG fake");
  await fs.writeFile(path.join(assetsDir, ".hidden"), "secret");
  return { root, assetsDir };
}

function makeRequest(params: {
  url: string;
  method?: string;
  authorization?: string;
  host?: string;
  remoteAddress?: string;
  headers?: Record<string, string>;
  ifNoneMatch?: string;
}): IncomingMessage {
  const headers: Record<string, string> = {
    host: params.host ?? "127.0.0.1:18789",
    ...(params.headers ?? {}),
  };
  if (params.authorization) {
    headers.authorization = params.authorization;
  }
  if (params.ifNoneMatch) {
    headers["if-none-match"] = params.ifNoneMatch;
  }
  return {
    url: params.url,
    method: params.method ?? "GET",
    headers,
    socket: { remoteAddress: params.remoteAddress ?? "127.0.0.1" },
  } as unknown as IncomingMessage;
}

async function readResponseBody(res: ReturnType<typeof makeMockHttpResponse>["res"]) {
  const chunks: Buffer[] = [];
  for await (const chunk of res as unknown as AsyncIterable<Buffer | string>) {
    chunks.push(typeof chunk === "string" ? Buffer.from(chunk) : chunk);
  }
  return Buffer.concat(chunks);
}

describe("isAssetsHttpPath", () => {
  it("matches /assets and /assets/...", () => {
    expect(isAssetsHttpPath("/assets")).toBe(true);
    expect(isAssetsHttpPath("/assets/avatars/x.gif")).toBe(true);
  });
  it("does not match other paths", () => {
    expect(isAssetsHttpPath("/v1/models")).toBe(false);
    expect(isAssetsHttpPath("/stream/tts")).toBe(false);
    expect(isAssetsHttpPath("/assetsX")).toBe(false);
  });
});

describe("handleAssetsHttpRequest — gating", () => {
  let fx: FixtureDirs;
  beforeAll(async () => {
    fx = await makeAssetsFixture("openclaw-assets-gating-");
  });
  afterAll(async () => {
    await fs.rm(fx.root, { recursive: true, force: true });
  });

  it("returns false when the config is not enabled", async () => {
    const { res } = makeMockHttpResponse();
    const handled = await handleAssetsHttpRequest(
      makeRequest({ url: "/assets/hello.gif" }),
      res,
      {
        auth: AUTH_NONE,
        config: { enabled: false, assetsDir: fx.assetsDir, publicAssets: true },
      },
    );
    expect(handled).toBe(false);
  });

  it("returns false when the pathname doesn't match", async () => {
    const { res } = makeMockHttpResponse();
    const handled = await handleAssetsHttpRequest(
      makeRequest({ url: "/v1/models" }),
      res,
      {
        auth: AUTH_NONE,
        config: { enabled: true, assetsDir: fx.assetsDir, publicAssets: true },
      },
    );
    expect(handled).toBe(false);
  });
});

describe("handleAssetsHttpRequest — serving", () => {
  let fx: FixtureDirs;
  beforeAll(async () => {
    fx = await makeAssetsFixture("openclaw-assets-serving-");
  });
  afterAll(async () => {
    await fs.rm(fx.root, { recursive: true, force: true });
  });

  it("serves a valid file with correct Content-Type and body", async () => {
    const { res, setHeader } = makeMockHttpResponse();
    const bodyPromise = readResponseBody(res);
    const handled = await handleAssetsHttpRequest(
      makeRequest({ url: "/assets/hello.gif" }),
      res,
      {
        auth: AUTH_NONE,
        config: { enabled: true, assetsDir: fx.assetsDir, publicAssets: true },
      },
    );
    expect(handled).toBe(true);
    expect(res.statusCode).toBe(200);
    expect(setHeader).toHaveBeenCalledWith("Content-Type", "image/gif");
    const body = await bodyPromise;
    expect(body.toString()).toBe("GIF89a fake bytes");
  });

  it("serves nested files", async () => {
    const { res } = makeMockHttpResponse();
    const bodyPromise = readResponseBody(res);
    await handleAssetsHttpRequest(
      makeRequest({ url: "/assets/sub/inner.png" }),
      res,
      {
        auth: AUTH_NONE,
        config: { enabled: true, assetsDir: fx.assetsDir, publicAssets: true },
      },
    );
    expect(res.statusCode).toBe(200);
    expect((await bodyPromise).toString()).toBe("PNG fake");
  });

  it("sets ETag and honours If-None-Match with 304", async () => {
    const first = makeMockHttpResponse();
    await handleAssetsHttpRequest(
      makeRequest({ url: "/assets/hello.gif" }),
      first.res,
      {
        auth: AUTH_NONE,
        config: { enabled: true, assetsDir: fx.assetsDir, publicAssets: true },
      },
    );
    const etagCall = first.setHeader.mock.calls.find((c) => c[0] === "ETag");
    expect(etagCall).toBeDefined();
    const etag = String(etagCall?.[1] ?? "");
    expect(etag.length).toBeGreaterThan(2);

    const second = makeMockHttpResponse();
    const handled = await handleAssetsHttpRequest(
      makeRequest({ url: "/assets/hello.gif", ifNoneMatch: etag }),
      second.res,
      {
        auth: AUTH_NONE,
        config: { enabled: true, assetsDir: fx.assetsDir, publicAssets: true },
      },
    );
    expect(handled).toBe(true);
    expect(second.res.statusCode).toBe(304);
  });

  it("handles HEAD without body", async () => {
    const { res, setHeader, end } = makeMockHttpResponse();
    await handleAssetsHttpRequest(
      makeRequest({ url: "/assets/hello.gif", method: "HEAD" }),
      res,
      {
        auth: AUTH_NONE,
        config: { enabled: true, assetsDir: fx.assetsDir, publicAssets: true },
      },
    );
    expect(res.statusCode).toBe(200);
    const ct = setHeader.mock.calls.find((c) => c[0] === "Content-Type");
    expect(ct?.[1]).toBe("image/gif");
    expect(end).toHaveBeenCalledTimes(1);
    expect(end.mock.calls[0]?.[0]).toBeUndefined();
  });

  it("rejects non-GET/HEAD with 405", async () => {
    const { res, setHeader } = makeMockHttpResponse();
    const handled = await handleAssetsHttpRequest(
      makeRequest({ url: "/assets/hello.gif", method: "POST" }),
      res,
      {
        auth: AUTH_NONE,
        config: { enabled: true, assetsDir: fx.assetsDir, publicAssets: true },
      },
    );
    expect(handled).toBe(true);
    expect(res.statusCode).toBe(405);
    expect(setHeader).toHaveBeenCalledWith("Allow", "GET, HEAD");
  });

  it("returns 400 when path is missing", async () => {
    const { res } = makeMockHttpResponse();
    const bodyPromise = readResponseBody(res);
    const handled = await handleAssetsHttpRequest(
      makeRequest({ url: "/assets" }),
      res,
      {
        auth: AUTH_NONE,
        config: { enabled: true, assetsDir: fx.assetsDir, publicAssets: true },
      },
    );
    expect(handled).toBe(true);
    expect(res.statusCode).toBe(400);
    expect((await bodyPromise).toString()).toContain("Missing asset path");
  });
});

describe("handleAssetsHttpRequest — security guards", () => {
  let fx: FixtureDirs;
  beforeAll(async () => {
    fx = await makeAssetsFixture("openclaw-assets-security-");
  });
  afterAll(async () => {
    await fs.rm(fx.root, { recursive: true, force: true });
  });

  // Literal "../" and even "%2e%2e" are normalized out by the WHATWG URL parser
  // before the handler sees them, so the meaningful attack vector here is a
  // percent-encoded separator that survives normalization.
  it("rejects percent-encoded separator traversal", async () => {
    const { res } = makeMockHttpResponse();
    await handleAssetsHttpRequest(
      makeRequest({ url: "/assets/a%2f..%2fpackage.json" }),
      res,
      {
        auth: AUTH_NONE,
        config: { enabled: true, assetsDir: fx.assetsDir, publicAssets: true },
      },
    );
    // After decoding, the resolved path escapes assetsDir → 403 or 404
    // (depending on what lives at the target). Either is a rejection.
    expect([403, 404]).toContain(res.statusCode);
  });

  it("rejects hidden files with 403", async () => {
    const { res } = makeMockHttpResponse();
    await handleAssetsHttpRequest(
      makeRequest({ url: "/assets/.hidden" }),
      res,
      {
        auth: AUTH_NONE,
        config: { enabled: true, assetsDir: fx.assetsDir, publicAssets: true },
      },
    );
    expect(res.statusCode).toBe(403);
  });

  it("returns 404 for missing file", async () => {
    const { res } = makeMockHttpResponse();
    await handleAssetsHttpRequest(
      makeRequest({ url: "/assets/nope.gif" }),
      res,
      {
        auth: AUTH_NONE,
        config: { enabled: true, assetsDir: fx.assetsDir, publicAssets: true },
      },
    );
    expect(res.statusCode).toBe(404);
  });

  it("returns 413 when file exceeds maxAssetSizeBytes", async () => {
    const big = path.join(fx.assetsDir, "big.gif");
    await fs.writeFile(big, Buffer.alloc(2048));
    try {
      const { res } = makeMockHttpResponse();
      await handleAssetsHttpRequest(
        makeRequest({ url: "/assets/big.gif" }),
        res,
        {
          auth: AUTH_NONE,
          config: {
            enabled: true,
            assetsDir: fx.assetsDir,
            publicAssets: true,
            maxAssetSizeBytes: 1024,
          },
        },
      );
      expect(res.statusCode).toBe(413);
    } finally {
      await fs.rm(big, { force: true });
    }
  });

  it("rejects NUL bytes in path", async () => {
    const { res } = makeMockHttpResponse();
    await handleAssetsHttpRequest(
      makeRequest({ url: "/assets/%00evil.gif" }),
      res,
      {
        auth: AUTH_NONE,
        config: { enabled: true, assetsDir: fx.assetsDir, publicAssets: true },
      },
    );
    expect(res.statusCode).toBe(400);
  });
});

describe("handleAssetsHttpRequest — auth", () => {
  let fx: FixtureDirs;
  beforeAll(async () => {
    fx = await makeAssetsFixture("openclaw-assets-auth-");
  });
  afterAll(async () => {
    await fs.rm(fx.root, { recursive: true, force: true });
  });

  it("returns 401 when token auth is required and missing", async () => {
    await withTempConfig({
      prefix: "openclaw-assets-auth-missing-",
      cfg: { gateway: { trustedProxies: [] } },
      run: async () => {
        const { res } = makeMockHttpResponse();
        await handleAssetsHttpRequest(
          makeRequest({ url: "/assets/hello.gif" }),
          res,
          {
            auth: AUTH_TOKEN,
            config: { enabled: true, assetsDir: fx.assetsDir, publicAssets: false },
          },
        );
        expect(res.statusCode).toBe(401);
      },
    });
  });

  it("accepts a valid Bearer token", async () => {
    await withTempConfig({
      prefix: "openclaw-assets-auth-bearer-",
      cfg: { gateway: { trustedProxies: [] } },
      run: async () => {
        const { res } = makeMockHttpResponse();
        await handleAssetsHttpRequest(
          makeRequest({
            url: "/assets/hello.gif",
            authorization: "Bearer test-token",
          }),
          res,
          {
            auth: AUTH_TOKEN,
            config: { enabled: true, assetsDir: fx.assetsDir, publicAssets: false },
          },
        );
        expect(res.statusCode).toBe(200);
      },
    });
  });

  it("accepts ?token= query param", async () => {
    await withTempConfig({
      prefix: "openclaw-assets-auth-query-",
      cfg: { gateway: { trustedProxies: [] } },
      run: async () => {
        const { res } = makeMockHttpResponse();
        await handleAssetsHttpRequest(
          makeRequest({ url: "/assets/hello.gif?token=test-token" }),
          res,
          {
            auth: AUTH_TOKEN,
            config: { enabled: true, assetsDir: fx.assetsDir, publicAssets: false },
          },
        );
        expect(res.statusCode).toBe(200);
      },
    });
  });

  it("rejects a wrong token with 401", async () => {
    await withTempConfig({
      prefix: "openclaw-assets-auth-wrong-",
      cfg: { gateway: { trustedProxies: [] } },
      run: async () => {
        const { res } = makeMockHttpResponse();
        await handleAssetsHttpRequest(
          makeRequest({ url: "/assets/hello.gif?token=wrong" }),
          res,
          {
            auth: AUTH_TOKEN,
            config: { enabled: true, assetsDir: fx.assetsDir, publicAssets: false },
          },
        );
        expect(res.statusCode).toBe(401);
      },
    });
  });
});
