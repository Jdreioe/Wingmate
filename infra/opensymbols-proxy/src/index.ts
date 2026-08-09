const OPEN_SYMBOLS_BASE_URL = "https://www.opensymbols.org";
const TOKEN_URL = `${OPEN_SYMBOLS_BASE_URL}/api/v2/token`;
const SEARCH_URL = `${OPEN_SYMBOLS_BASE_URL}/api/v2/symbols`;
const MAX_QUERY_LENGTH = 100;
const MAX_REQUEST_BYTES = 1_024;
const UPSTREAM_TIMEOUT_MS = 8_000;

interface RateLimiter {
  limit(options: { key: string }): Promise<{ success: boolean }>;
}

export interface Env {
  OPENSYMBOLS_SECRET: string;
  SEARCH_RATE_LIMITER: RateLimiter;
}

interface SearchRequest {
  query: string;
  locale: string;
}

interface OpenSymbolsTokenResponse {
  access_token: string;
}

const SYMBOL_FIELDS = [
  "id", "symbol_key", "name", "locale", "license", "author",
  "repo_key", "image_url", "details_url", "extension",
] as const;

let cachedToken: { value: string; expiresAt: number } | undefined;

export function resetTokenCacheForTesting(): void {
  cachedToken = undefined;
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
      "content-security-policy": "default-src 'none'",
      "referrer-policy": "no-referrer",
      "x-content-type-options": "nosniff",
    },
  });
}

function parseRequest(value: unknown): SearchRequest | undefined {
  if (typeof value !== "object" || value === null || Array.isArray(value)) return undefined;
  const record = value as Record<string, unknown>;
  if (typeof record.query !== "string" || typeof record.locale !== "string") return undefined;
  const query = record.query.trim();
  const locale = record.locale.trim().toLowerCase().split(/[-_]/, 1)[0];
  if (query.length === 0 || query.length > MAX_QUERY_LENGTH || !/^[a-z]{2}$/.test(locale)) return undefined;
  return { query, locale };
}

async function readJsonBody(request: Request): Promise<unknown> {
  const declaredLength = Number(request.headers.get("content-length") ?? 0);
  if (declaredLength > MAX_REQUEST_BYTES) throw new Error("Request body is too large");
  if (!request.body) throw new Error("Request body is missing");

  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let length = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    length += value.byteLength;
    if (length > MAX_REQUEST_BYTES) {
      await reader.cancel();
      throw new Error("Request body is too large");
    }
    chunks.push(value);
  }
  const bytes = new Uint8Array(length);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return JSON.parse(new TextDecoder().decode(bytes));
}

async function rateLimitKey(request: Request, secret: string): Promise<string> {
  const address = request.headers.get("cf-connecting-ip") ?? "unknown";
  const key = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const digest = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(address));
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, "0")).join("");
}

async function fetchToken(secret: string, fetcher: typeof fetch): Promise<string> {
  const now = Date.now();
  if (cachedToken && cachedToken.expiresAt > now + 30_000) return cachedToken.value;

  const url = new URL(TOKEN_URL);
  url.searchParams.set("secret", secret);
  const response = await fetcher(url, {
    method: "POST",
    headers: { accept: "application/json" },
    signal: AbortSignal.timeout(UPSTREAM_TIMEOUT_MS),
  });
  if (!response.ok) throw new Error("OpenSymbols token exchange failed");
  const body: unknown = await response.json();
  if (
    typeof body !== "object" ||
    body === null ||
    typeof (body as Record<string, unknown>).access_token !== "string" ||
    (body as Record<string, unknown>).access_token === ""
  ) {
    throw new Error("OpenSymbols token response was invalid");
  }
  const token = (body as unknown as OpenSymbolsTokenResponse).access_token;
  cachedToken = { value: token, expiresAt: now + 4 * 60_000 };
  return token;
}

async function searchOpenSymbols(
  input: SearchRequest,
  secret: string,
  fetcher: typeof fetch,
): Promise<Response> {
  const token = await fetchToken(secret, fetcher);
  const url = new URL(SEARCH_URL);
  url.searchParams.set("q", input.query);
  url.searchParams.set("locale", input.locale);
  url.searchParams.set("safe", "1");
  const performSearch = (accessToken: string) => fetcher(url, {
    headers: { accept: "application/json", authorization: `Bearer ${accessToken}` },
    signal: AbortSignal.timeout(UPSTREAM_TIMEOUT_MS),
  });
  let response = await performSearch(token);
  if (response.status === 401) {
    cachedToken = undefined;
    response = await performSearch(await fetchToken(secret, fetcher));
  }
  if (response.status === 429) return json({ error: "throttled" }, 429);
  if (!response.ok) throw new Error("OpenSymbols search failed");
  const body: unknown = await response.json();
  if (!Array.isArray(body)) throw new Error("OpenSymbols search response was invalid");
  const symbols = body.slice(0, 100).flatMap((item) => {
    if (typeof item !== "object" || item === null || Array.isArray(item)) return [];
    const source = item as Record<string, unknown>;
    if (typeof source.id !== "number" || typeof source.name !== "string") return [];
    const sanitized: Record<string, unknown> = {};
    for (const field of SYMBOL_FIELDS) {
      const value = source[field];
      if (typeof value === "string" || (field === "id" && typeof value === "number")) {
        sanitized[field] = value;
      }
    }
    return [sanitized];
  });
  return json(symbols);
}

export async function handleRequest(
  request: Request,
  env: Env,
  fetcher: typeof fetch = fetch,
): Promise<Response> {
  const url = new URL(request.url);
  if (url.pathname !== "/v1/opensymbols/search") return json({ error: "not_found" }, 404);
  if (request.method !== "POST") return json({ error: "method_not_allowed" }, 405);
  if (request.headers.has("origin")) return json({ error: "browser_requests_not_allowed" }, 403);
  if (!request.headers.get("content-type")?.toLowerCase().startsWith("application/json")) {
    return json({ error: "invalid_content_type" }, 415);
  }
  if (!env.OPENSYMBOLS_SECRET) return json({ error: "service_unavailable" }, 503);

  const key = await rateLimitKey(request, env.OPENSYMBOLS_SECRET);
  const { success } = await env.SEARCH_RATE_LIMITER.limit({ key });
  if (!success) return json({ error: "throttled" }, 429);

  let raw: unknown;
  try {
    raw = await readJsonBody(request);
  } catch {
    return json({ error: "invalid_request" }, 400);
  }
  const input = parseRequest(raw);
  if (!input) return json({ error: "invalid_request" }, 400);

  try {
    return await searchOpenSymbols(input, env.OPENSYMBOLS_SECRET, fetcher);
  } catch {
    return json({ error: "upstream_unavailable" }, 502);
  }
}

export default {
  fetch(request: Request, env: Env): Promise<Response> {
    return handleRequest(request, env);
  },
};
