import {
  LOGIN_WINDOW_SECONDS,
  MAX_LOGIN_FAILURES,
  MAX_REPORTS_PER_IP_HOUR,
  SESSION_SECONDS,
} from "./constants";
import { HttpError } from "./http";

const COOKIE_NAME = "anilili_diagnostics_admin";
const encoder = new TextEncoder();

function base64Url(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/, "");
}

async function digest(value: string): Promise<Uint8Array> {
  return new Uint8Array(await crypto.subtle.digest("SHA-256", encoder.encode(value)));
}

export async function constantTimeEqual(left: string, right: string): Promise<boolean> {
  const [leftHash, rightHash] = await Promise.all([digest(left), digest(right)]);
  let difference = left.length === right.length ? 0 : 1;
  for (let index = 0; index < leftHash.length; index += 1) {
    difference |= leftHash[index] ^ rightHash[index];
  }
  return difference === 0;
}

async function hmac(value: string, secret: string): Promise<string> {
  const key = await crypto.subtle.importKey(
    "raw",
    encoder.encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  return base64Url(new Uint8Array(await crypto.subtle.sign("HMAC", key, encoder.encode(value))));
}

function cookieValue(request: Request): string | null {
  const cookie = request.headers.get("Cookie") ?? "";
  for (const part of cookie.split(";")) {
    const [name, ...value] = part.trim().split("=");
    if (name === COOKIE_NAME) return value.join("=");
  }
  return null;
}

export async function issueAdminCookie(env: Env): Promise<string> {
  const expires = Math.floor(Date.now() / 1_000) + SESSION_SECONDS;
  const nonce = base64Url(crypto.getRandomValues(new Uint8Array(18)));
  const payload = `${expires}.${nonce}`;
  const signature = await hmac(payload, env.RATE_LIMIT_SECRET);
  return `${COOKIE_NAME}=${payload}.${signature}; Max-Age=${SESSION_SECONDS}; Path=/; HttpOnly; Secure; SameSite=Strict`;
}

export function clearAdminCookie(): string {
  return `${COOKIE_NAME}=; Max-Age=0; Path=/; HttpOnly; Secure; SameSite=Strict`;
}

export async function hasValidAdminSession(request: Request, env: Env): Promise<boolean> {
  const value = cookieValue(request);
  if (!value) return false;
  const parts = value.split(".");
  if (parts.length !== 3) return false;
  const expires = Number(parts[0]);
  if (!Number.isInteger(expires) || expires <= Math.floor(Date.now() / 1_000)) return false;
  const expected = await hmac(`${parts[0]}.${parts[1]}`, env.RATE_LIMIT_SECRET);
  return constantTimeEqual(parts[2], expected);
}

export async function requireAdmin(request: Request, env: Env): Promise<void> {
  if (!(await hasValidAdminSession(request, env))) {
    throw new HttpError(401, "Administrator authentication required");
  }
}

export function requireAdminMutation(request: Request): void {
  if (request.headers.get("X-Anilili-Admin") !== "1") {
    throw new HttpError(403, "Missing administrator request proof");
  }
}

async function privateRateKey(env: Env, scope: string, identity: string, window: string): Promise<string> {
  const opaque = await hmac(`${scope}:${identity}:${window}`, env.RATE_LIMIT_SECRET);
  return `limit:${scope}:${opaque.slice(0, 32)}`;
}

async function incrementCounter(
  namespace: KVNamespace,
  key: string,
  expirationTtl: number,
): Promise<number> {
  const current = Number(await namespace.get(key)) || 0;
  const next = current + 1;
  await namespace.put(key, String(next), { expirationTtl });
  return next;
}

export function clientAddress(request: Request): string {
  return request.headers.get("CF-Connecting-IP") ?? "unknown";
}

export async function reserveUpload(request: Request, env: Env): Promise<void> {
  const hour = new Date().toISOString().slice(0, 13);
  const key = await privateRateKey(env, "upload", clientAddress(request), hour);
  if ((await incrementCounter(env.RATE_LIMIT, key, 3_700)) > MAX_REPORTS_PER_IP_HOUR) {
    throw new HttpError(429, "Too many diagnostic reports; the app will retry later");
  }
}

export async function loginIsBlocked(request: Request, env: Env): Promise<boolean> {
  const window = String(Math.floor(Date.now() / (LOGIN_WINDOW_SECONDS * 1_000)));
  const key = await privateRateKey(env, "login", clientAddress(request), window);
  return (Number(await env.RATE_LIMIT.get(key)) || 0) >= MAX_LOGIN_FAILURES;
}

export async function recordLoginFailure(request: Request, env: Env): Promise<void> {
  const window = String(Math.floor(Date.now() / (LOGIN_WINDOW_SECONDS * 1_000)));
  const key = await privateRateKey(env, "login", clientAddress(request), window);
  await incrementCounter(env.RATE_LIMIT, key, LOGIN_WINDOW_SECONDS + 60);
}
