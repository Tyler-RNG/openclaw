import type { IncomingMessage } from "node:http";
import { describe, expect, it, vi } from "vitest";
import type { ResolvedGatewayAuth } from "./auth.js";
import { makeMockHttpResponse } from "./test-http-response.js";
import { withTempConfig } from "./test-temp-config.js";
import { handleStreamTtsHttpRequest, isStreamTtsHttpPath } from "./tts-http.js";

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

function makeRequest(params: {
  url: string;
  method?: string;
  authorization?: string;
  host?: string;
  remoteAddress?: string;
}): IncomingMessage {
  const headers: Record<string, string> = {
    host: params.host ?? "127.0.0.1:18789",
  };
  if (params.authorization) {
    headers.authorization = params.authorization;
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

function makeUpstream(params: {
  status?: number;
  ok?: boolean;
  chunks?: readonly Uint8Array[];
  text?: string;
}): Response {
  const status = params.status ?? 200;
  const chunks = params.chunks ?? [new Uint8Array([0x68, 0x69])]; // "hi"
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const chunk of chunks) {
        controller.enqueue(chunk);
      }
      controller.close();
    },
  });
  return {
    status,
    ok: params.ok ?? (status >= 200 && status < 300),
    body: stream,
    text: async () => params.text ?? "",
  } as unknown as Response;
}

describe("isStreamTtsHttpPath", () => {
  it("matches /stream/tts and /tts", () => {
    expect(isStreamTtsHttpPath("/stream/tts")).toBe(true);
    expect(isStreamTtsHttpPath("/tts")).toBe(true);
  });
  it("rejects similar paths", () => {
    expect(isStreamTtsHttpPath("/stream/tts/extra")).toBe(false);
    expect(isStreamTtsHttpPath("/ttsx")).toBe(false);
    expect(isStreamTtsHttpPath("/assets")).toBe(false);
  });
});

describe("handleStreamTtsHttpRequest — gating", () => {
  it("returns false when disabled", async () => {
    const { res } = makeMockHttpResponse();
    const handled = await handleStreamTtsHttpRequest(
      makeRequest({ url: "/stream/tts?voice=v&text=hi" }),
      res,
      {
        auth: AUTH_NONE,
        config: { enabled: false, provider: "elevenlabs" },
      },
    );
    expect(handled).toBe(false);
  });

  it("returns false for non-matching path", async () => {
    const { res } = makeMockHttpResponse();
    const handled = await handleStreamTtsHttpRequest(
      makeRequest({ url: "/assets/x.gif" }),
      res,
      {
        auth: AUTH_NONE,
        config: { enabled: true, provider: "elevenlabs" },
      },
    );
    expect(handled).toBe(false);
  });
});

describe("handleStreamTtsHttpRequest — validation", () => {
  it("rejects non-GET with 405", async () => {
    const { res, setHeader } = makeMockHttpResponse();
    const handled = await handleStreamTtsHttpRequest(
      makeRequest({ url: "/stream/tts?voice=v&text=hi", method: "POST" }),
      res,
      {
        auth: AUTH_NONE,
        config: { enabled: true, provider: "elevenlabs" },
      },
    );
    expect(handled).toBe(true);
    expect(res.statusCode).toBe(405);
    expect(setHeader).toHaveBeenCalledWith("Allow", "GET");
  });

  it("400 when voice is missing", async () => {
    const { res } = makeMockHttpResponse();
    const body = readResponseBody(res);
    await handleStreamTtsHttpRequest(
      makeRequest({ url: "/stream/tts?text=hi" }),
      res,
      {
        auth: AUTH_NONE,
        config: { enabled: true, provider: "elevenlabs" },
      },
    );
    expect(res.statusCode).toBe(400);
    expect((await body).toString()).toContain("voice, text");
  });

  it("400 when text is missing", async () => {
    const { res } = makeMockHttpResponse();
    await handleStreamTtsHttpRequest(
      makeRequest({ url: "/stream/tts?voice=v" }),
      res,
      {
        auth: AUTH_NONE,
        config: { enabled: true, provider: "elevenlabs" },
      },
    );
    expect(res.statusCode).toBe(400);
  });
});

describe("handleStreamTtsHttpRequest — provider / key", () => {
  it("503 when provider is not elevenlabs", async () => {
    const { res } = makeMockHttpResponse();
    await handleStreamTtsHttpRequest(
      makeRequest({ url: "/stream/tts?voice=v&text=hi" }),
      res,
      {
        auth: AUTH_NONE,
        config: { enabled: true },
      },
    );
    expect(res.statusCode).toBe(503);
  });

  it("503 when API key resolves empty", async () => {
    const { res } = makeMockHttpResponse();
    await handleStreamTtsHttpRequest(
      makeRequest({ url: "/stream/tts?voice=v&text=hi" }),
      res,
      {
        auth: AUTH_NONE,
        config: { enabled: true, provider: "elevenlabs" },
        resolveApiKey: async () => undefined,
      },
    );
    expect(res.statusCode).toBe(503);
  });
});

describe("handleStreamTtsHttpRequest — streaming", () => {
  it("streams upstream audio bytes through to the client", async () => {
    const chunks = [new Uint8Array([1, 2, 3]), new Uint8Array([4, 5])];
    const fetchImpl = vi.fn(async () => makeUpstream({ chunks }));
    const { res, setHeader } = makeMockHttpResponse();
    const body = readResponseBody(res);
    const handled = await handleStreamTtsHttpRequest(
      makeRequest({ url: "/stream/tts?voice=v&text=hi" }),
      res,
      {
        auth: AUTH_NONE,
        config: { enabled: true, provider: "elevenlabs" },
        resolveApiKey: async () => "fake-key",
        fetchImpl: fetchImpl as unknown as typeof fetch,
      },
    );
    expect(handled).toBe(true);
    expect(res.statusCode).toBe(200);
    expect(setHeader).toHaveBeenCalledWith("Content-Type", "audio/mpeg");
    expect(setHeader).toHaveBeenCalledWith("Transfer-Encoding", "chunked");
    expect(setHeader).toHaveBeenCalledWith("Cache-Control", "no-store");
    const out = await body;
    expect(Array.from(out)).toEqual([1, 2, 3, 4, 5]);
    expect(fetchImpl).toHaveBeenCalledTimes(1);
    const call = fetchImpl.mock.calls[0] as unknown as [string, RequestInit];
    expect(call[0]).toContain("/v1/text-to-speech/v/stream");
    expect((call[1].headers as Record<string, string>)["xi-api-key"]).toBe("fake-key");
    const requestBody = JSON.parse(String(call[1].body ?? "{}")) as {
      text?: string;
      model_id?: string;
    };
    expect(requestBody.text).toBe("hi");
    expect(requestBody.model_id).toBe("eleven_turbo_v2");
  });

  it("honours the defaultModel config and model query override", async () => {
    const fetchImpl = vi.fn(async () => makeUpstream({}));
    const { res } = makeMockHttpResponse();
    const drain = readResponseBody(res);
    await handleStreamTtsHttpRequest(
      makeRequest({ url: "/stream/tts?voice=v&text=hi&model=custom-m" }),
      res,
      {
        auth: AUTH_NONE,
        config: { enabled: true, provider: "elevenlabs", defaultModel: "eleven_other" },
        resolveApiKey: async () => "k",
        fetchImpl: fetchImpl as unknown as typeof fetch,
      },
    );
    await drain;
    const call = fetchImpl.mock.calls[0] as unknown as [string, RequestInit];
    const body = JSON.parse(String(call[1].body ?? "{}")) as { model_id?: string };
    expect(body.model_id).toBe("custom-m");
  });
});

describe("handleStreamTtsHttpRequest — upstream errors", () => {
  it("propagates 429 as 429", async () => {
    const fetchImpl = vi.fn(async () => makeUpstream({ status: 429, ok: false }));
    const { res } = makeMockHttpResponse();
    await handleStreamTtsHttpRequest(
      makeRequest({ url: "/stream/tts?voice=v&text=hi" }),
      res,
      {
        auth: AUTH_NONE,
        config: { enabled: true, provider: "elevenlabs" },
        resolveApiKey: async () => "k",
        fetchImpl: fetchImpl as unknown as typeof fetch,
      },
    );
    expect(res.statusCode).toBe(429);
  });

  it("propagates a non-OK upstream status", async () => {
    const fetchImpl = vi.fn(async () =>
      makeUpstream({ status: 500, ok: false, text: "eleven broke" }),
    );
    const { res } = makeMockHttpResponse();
    const body = readResponseBody(res);
    await handleStreamTtsHttpRequest(
      makeRequest({ url: "/stream/tts?voice=v&text=hi" }),
      res,
      {
        auth: AUTH_NONE,
        config: { enabled: true, provider: "elevenlabs" },
        resolveApiKey: async () => "k",
        fetchImpl: fetchImpl as unknown as typeof fetch,
      },
    );
    expect(res.statusCode).toBe(500);
    const out = (await body).toString();
    expect(out).toContain("Upstream TTS error");
    expect(out).toContain("eleven broke");
  });

  it("returns 502 when fetch throws", async () => {
    const fetchImpl = vi.fn(async () => {
      throw new Error("dns fail");
    });
    const { res } = makeMockHttpResponse();
    await handleStreamTtsHttpRequest(
      makeRequest({ url: "/stream/tts?voice=v&text=hi" }),
      res,
      {
        auth: AUTH_NONE,
        config: { enabled: true, provider: "elevenlabs" },
        resolveApiKey: async () => "k",
        fetchImpl: fetchImpl as unknown as typeof fetch,
      },
    );
    expect(res.statusCode).toBe(502);
  });
});

describe("handleStreamTtsHttpRequest — auth", () => {
  it("401 when token auth is required and missing", async () => {
    await withTempConfig({
      prefix: "openclaw-tts-auth-",
      cfg: { gateway: { trustedProxies: [] } },
      run: async () => {
        const { res } = makeMockHttpResponse();
        await handleStreamTtsHttpRequest(
          makeRequest({ url: "/stream/tts?voice=v&text=hi" }),
          res,
          {
            auth: AUTH_TOKEN,
            config: { enabled: true, provider: "elevenlabs" },
            resolveApiKey: async () => "k",
            fetchImpl: vi.fn(async () => makeUpstream({})) as unknown as typeof fetch,
          },
        );
        expect(res.statusCode).toBe(401);
      },
    });
  });

  it("accepts a valid Bearer token", async () => {
    await withTempConfig({
      prefix: "openclaw-tts-auth-bearer-",
      cfg: { gateway: { trustedProxies: [] } },
      run: async () => {
        const fetchImpl = vi.fn(async () => makeUpstream({}));
        const { res } = makeMockHttpResponse();
        const drain = readResponseBody(res);
        await handleStreamTtsHttpRequest(
          makeRequest({
            url: "/stream/tts?voice=v&text=hi",
            authorization: "Bearer test-token",
          }),
          res,
          {
            auth: AUTH_TOKEN,
            config: { enabled: true, provider: "elevenlabs" },
            resolveApiKey: async () => "k",
            fetchImpl: fetchImpl as unknown as typeof fetch,
          },
        );
        await drain;
        expect(res.statusCode).toBe(200);
        expect(fetchImpl).toHaveBeenCalled();
      },
    });
  });
});
