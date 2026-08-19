import type { IncomingMessage, ServerResponse } from "node:http";
import { describe, expect, it } from "vitest";
import { handleTtsRequest } from "./tts-route.js";

function makeReq(url: string, method = "GET"): IncomingMessage {
  return { url, method, headers: {} } as unknown as IncomingMessage;
}

type FakeResponse = {
  statusCode: number;
  headers: Record<string, string | number>;
  body: string;
  ended: boolean;
  setHeader: (name: string, value: string | number) => void;
  end: (chunk?: string) => void;
  write: (chunk: unknown) => boolean;
  once: (event: string, listener: () => void) => void;
  destroy: () => void;
  get headersSent(): boolean;
};

function makeRes(): FakeResponse {
  return {
    statusCode: 200,
    headers: {},
    body: "",
    ended: false,
    setHeader(name: string, value: string | number) {
      this.headers[name] = value;
    },
    end(chunk?: string) {
      if (chunk) {
        this.body += chunk;
      }
      this.ended = true;
    },
    write(_chunk: unknown) {
      return true;
    },
    once() {},
    destroy() {},
    get headersSent() {
      return this.ended;
    },
  };
}

describe("sprite-core tts-route", () => {
  it("returns false when disabled", async () => {
    const res = makeRes();
    const handled = await handleTtsRequest(
      makeReq("/stream/tts?voice=v&text=hi"),
      res as unknown as ServerResponse,
      { config: { enabled: false } },
    );
    expect(handled).toBe(false);
  });

  it("returns false for non-tts paths", async () => {
    const res = makeRes();
    const handled = await handleTtsRequest(makeReq("/other"), res as unknown as ServerResponse, {
      config: { enabled: true, provider: "elevenlabs" },
    });
    expect(handled).toBe(false);
  });

  it("405 on non-GET", async () => {
    const res = makeRes();
    await handleTtsRequest(
      makeReq("/stream/tts?voice=v&text=hi", "POST"),
      res as unknown as ServerResponse,
      { config: { enabled: true, provider: "elevenlabs" } },
    );
    expect(res.statusCode).toBe(405);
  });

  it("400 when voice or text missing", async () => {
    const res = makeRes();
    await handleTtsRequest(makeReq("/stream/tts?voice=&text="), res as unknown as ServerResponse, {
      config: { enabled: true, provider: "elevenlabs" },
    });
    expect(res.statusCode).toBe(400);
  });

  it("503 when API key is not available", async () => {
    const res = makeRes();
    await handleTtsRequest(
      makeReq("/stream/tts?voice=v&text=hello"),
      res as unknown as ServerResponse,
      {
        config: { enabled: true, provider: "elevenlabs" },
        resolveApiKey: async () => undefined,
      },
    );
    expect(res.statusCode).toBe(503);
  });

  it("proxies upstream error status", async () => {
    const res = makeRes();
    const fetchImpl = async () =>
      new Response("upstream boom", { status: 500 }) as unknown as Response;
    await handleTtsRequest(
      makeReq("/stream/tts?voice=v&text=hello"),
      res as unknown as ServerResponse,
      {
        config: { enabled: true, provider: "elevenlabs", apiKey: "k" },
        resolveApiKey: async () => "k",
        fetchImpl: fetchImpl as unknown as typeof fetch,
      },
    );
    expect(res.statusCode).toBe(500);
  });

  it("returns 429 when upstream rate limits", async () => {
    const res = makeRes();
    const fetchImpl = async () => new Response("", { status: 429 }) as unknown as Response;
    await handleTtsRequest(
      makeReq("/stream/tts?voice=v&text=hello"),
      res as unknown as ServerResponse,
      {
        config: { enabled: true, provider: "elevenlabs" },
        resolveApiKey: async () => "k",
        fetchImpl: fetchImpl as unknown as typeof fetch,
      },
    );
    expect(res.statusCode).toBe(429);
  });

  it("accepts /tts as alias for /stream/tts", async () => {
    const res = makeRes();
    const fetchImpl = async () => new Response("ok", { status: 200 }) as unknown as Response;
    await handleTtsRequest(makeReq("/tts?voice=v&text=hi"), res as unknown as ServerResponse, {
      config: { enabled: true, provider: "elevenlabs" },
      resolveApiKey: async () => "k",
      fetchImpl: fetchImpl as unknown as typeof fetch,
    });
    expect(res.statusCode).toBe(200);
  });

  it("passes style + speaker_boost query params through to voice_settings", async () => {
    const res = makeRes();
    let capturedBody: unknown;
    const fetchImpl = async (_url: unknown, init: unknown) => {
      const request = init as { body?: string };
      capturedBody = request.body ? JSON.parse(request.body) : undefined;
      return new Response("ok", { status: 200 }) as unknown as Response;
    };
    await handleTtsRequest(
      makeReq("/stream/tts?voice=v&text=hi&style=0.6&speaker_boost=true&stability=0.8"),
      res as unknown as ServerResponse,
      {
        config: { enabled: true, provider: "elevenlabs" },
        resolveApiKey: async () => "k",
        fetchImpl: fetchImpl as unknown as typeof fetch,
      },
    );
    expect(res.statusCode).toBe(200);
    const body = capturedBody as { voice_settings?: Record<string, unknown> };
    expect(body.voice_settings).toMatchObject({
      stability: 0.8,
      style: 0.6,
      use_speaker_boost: true,
    });
  });

  it("omits style + speaker_boost when not provided", async () => {
    const res = makeRes();
    let capturedBody: unknown;
    const fetchImpl = async (_url: unknown, init: unknown) => {
      const request = init as { body?: string };
      capturedBody = request.body ? JSON.parse(request.body) : undefined;
      return new Response("ok", { status: 200 }) as unknown as Response;
    };
    await handleTtsRequest(
      makeReq("/stream/tts?voice=v&text=hi"),
      res as unknown as ServerResponse,
      {
        config: { enabled: true, provider: "elevenlabs" },
        resolveApiKey: async () => "k",
        fetchImpl: fetchImpl as unknown as typeof fetch,
      },
    );
    const body = capturedBody as { voice_settings?: Record<string, unknown> };
    expect(body.voice_settings).not.toHaveProperty("style");
    expect(body.voice_settings).not.toHaveProperty("use_speaker_boost");
  });

  it("ignores malformed speaker_boost values", async () => {
    const res = makeRes();
    let capturedBody: unknown;
    const fetchImpl = async (_url: unknown, init: unknown) => {
      const request = init as { body?: string };
      capturedBody = request.body ? JSON.parse(request.body) : undefined;
      return new Response("ok", { status: 200 }) as unknown as Response;
    };
    await handleTtsRequest(
      makeReq("/stream/tts?voice=v&text=hi&speaker_boost=maybe"),
      res as unknown as ServerResponse,
      {
        config: { enabled: true, provider: "elevenlabs" },
        resolveApiKey: async () => "k",
        fetchImpl: fetchImpl as unknown as typeof fetch,
      },
    );
    const body = capturedBody as { voice_settings?: Record<string, unknown> };
    expect(body.voice_settings).not.toHaveProperty("use_speaker_boost");
  });
});
