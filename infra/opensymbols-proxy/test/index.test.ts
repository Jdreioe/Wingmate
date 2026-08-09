import { beforeEach, describe, expect, it, vi } from "vitest";
import { handleRequest, resetTokenCacheForTesting, type Env } from "../src/index";

function environment(allowed = true): Env {
  return {
    OPENSYMBOLS_SECRET: "test-only-secret",
    SEARCH_RATE_LIMITER: { limit: vi.fn(async () => ({ success: allowed })) },
  };
}

function request(body: unknown, headers: Record<string, string> = {}): Request {
  return new Request("https://proxy.example/v1/opensymbols/search", {
    method: "POST",
    headers: { "content-type": "application/json", "cf-connecting-ip": "192.0.2.1", ...headers },
    body: JSON.stringify(body),
  });
}

describe("OpenSymbols proxy", () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    resetTokenCacheForTesting();
  });

  it("rejects browser origins before contacting OpenSymbols", async () => {
    const fetcher = vi.fn<typeof fetch>();
    const response = await handleRequest(request({ query: "hello", locale: "en" }, { origin: "https://evil.example" }), environment(), fetcher);
    expect(response.status).toBe(403);
    expect(fetcher).not.toHaveBeenCalled();
  });

  it("validates query length and locale", async () => {
    const fetcher = vi.fn<typeof fetch>();
    const response = await handleRequest(request({ query: "x".repeat(101), locale: "english" }), environment(), fetcher);
    expect(response.status).toBe(400);
    expect(fetcher).not.toHaveBeenCalled();
  });

  it("rejects oversized bodies before parsing them", async () => {
    const fetcher = vi.fn<typeof fetch>();
    const response = await handleRequest(request({ query: "x".repeat(2_000), locale: "en" }), environment(), fetcher);
    expect(response.status).toBe(400);
    expect(fetcher).not.toHaveBeenCalled();
  });

  it("returns 429 when the caller is rate limited", async () => {
    const fetcher = vi.fn<typeof fetch>();
    const response = await handleRequest(request({ query: "hello", locale: "en" }), environment(false), fetcher);
    expect(response.status).toBe(429);
    expect(fetcher).not.toHaveBeenCalled();
  });

  it("keeps the shared secret server-side and forces safe search", async () => {
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(new Response(JSON.stringify({ access_token: "short-token" }), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify([{ id: 1, name: "Hello" }]), { status: 200 }));
    const response = await handleRequest(request({ query: "hello", locale: "en-US" }), environment(), fetcher);
    expect(response.status).toBe(200);
    expect(fetcher).toHaveBeenCalledTimes(2);
    const tokenUrl = String(fetcher.mock.calls[0][0]);
    const searchUrl = String(fetcher.mock.calls[1][0]);
    expect(tokenUrl).toContain("secret=test-only-secret");
    expect(searchUrl).toContain("safe=1");
    expect(searchUrl).not.toContain("test-only-secret");
    expect(await response.text()).not.toContain("short-token");
  });

  it("does not relay unexpected upstream fields", async () => {
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(new Response(JSON.stringify({ access_token: "token" }), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify([{ id: 1, name: "Hello", private: "value" }]), { status: 200 }));
    const response = await handleRequest(request({ query: "hello", locale: "en" }), environment(), fetcher);
    expect(await response.json()).toEqual([{ id: 1, name: "Hello" }]);
  });
});
