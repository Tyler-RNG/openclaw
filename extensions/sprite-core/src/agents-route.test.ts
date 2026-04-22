import type { IncomingMessage, ServerResponse } from "node:http";
import { describe, expect, it } from "vitest";
import { handleAgentsRequest } from "./agents-route.js";

function makeReq(url: string, method = "GET"): IncomingMessage {
  return { url, method, headers: {} } as unknown as IncomingMessage;
}

type FakeResponse = {
  statusCode: number;
  headers: Record<string, string | number>;
  body: string;
  setHeader: (name: string, value: string | number) => void;
  end: (chunk?: string) => void;
};

function makeRes(): FakeResponse {
  return {
    statusCode: 200,
    headers: {},
    body: "",
    setHeader(name: string, value: string | number) {
      this.headers[name] = value;
    },
    end(chunk?: string) {
      if (chunk) {
        this.body += chunk;
      }
    },
  };
}

describe("sprite-core agents-route", () => {
  it("returns empty agents map when nothing configured", async () => {
    const res = makeRes();
    const handled = await handleAgentsRequest(
      makeReq("/sprite-core/agents"),
      res as unknown as ServerResponse,
      { agents: undefined, assets: undefined },
    );
    expect(handled).toBe(true);
    expect(res.statusCode).toBe(200);
    expect(JSON.parse(res.body)).toEqual({ agents: {} });
  });

  it("returns configured per-agent avatar and voice", async () => {
    const res = makeRes();
    await handleAgentsRequest(makeReq("/sprite-core/agents"), res as unknown as ServerResponse, {
      agents: {
        ginger: {
          avatar: { kind: "atlas", default: "neutral", manifest: "avatars/ginger/atlas.json" },
          voice: { provider: "elevenlabs", voiceId: "abc" },
        },
      },
      assets: { publicBaseUrl: "https://example.test" },
    });
    const parsed = JSON.parse(res.body) as {
      agents: Record<string, unknown>;
      publicBaseUrl: string;
    };
    expect(parsed.publicBaseUrl).toBe("https://example.test");
    expect(parsed.agents.ginger).toMatchObject({
      avatar: { kind: "atlas" },
      voice: { voiceId: "abc" },
    });
  });

  it("405 on non-GET", async () => {
    const res = makeRes();
    await handleAgentsRequest(
      makeReq("/sprite-core/agents", "POST"),
      res as unknown as ServerResponse,
      { agents: undefined, assets: undefined },
    );
    expect(res.statusCode).toBe(405);
  });

  it("returns false for non-agents paths", async () => {
    const res = makeRes();
    const handled = await handleAgentsRequest(makeReq("/other"), res as unknown as ServerResponse, {
      agents: undefined,
      assets: undefined,
    });
    expect(handled).toBe(false);
  });
});
