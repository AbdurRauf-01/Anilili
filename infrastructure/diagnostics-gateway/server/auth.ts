import { createHmac, randomBytes, timingSafeEqual } from "node:crypto";
import type { NextFunction, Request, Response } from "express";
import type { RuntimeConfig } from "./config.js";

const COOKIE_NAME = "anilili_diagnostics_admin";
const SESSION_SECONDS = 8 * 60 * 60;

function constantTimeEqual(left: string, right: string): boolean {
  const leftBytes = Buffer.from(left);
  const rightBytes = Buffer.from(right);
  return leftBytes.length === rightBytes.length && timingSafeEqual(leftBytes, rightBytes);
}

function signature(payload: string, secret: string): string {
  return createHmac("sha256", secret).update(payload).digest("base64url");
}

function issueSession(secret: string): string {
  const expires = Math.floor(Date.now() / 1000) + SESSION_SECONDS;
  const payload = `${expires}.${randomBytes(12).toString("base64url")}`;
  return `${payload}.${signature(payload, secret)}`;
}

function validSession(value: unknown, secret: string): boolean {
  if (typeof value !== "string") return false;
  const parts = value.split(".");
  if (parts.length !== 3) return false;
  const payload = `${parts[0]}.${parts[1]}`;
  const expires = Number(parts[0]);
  return (
    Number.isFinite(expires) &&
    expires > Math.floor(Date.now() / 1000) &&
    constantTimeEqual(parts[2], signature(payload, secret))
  );
}

export function authenticateAdmin(config: RuntimeConfig, presentedKey: unknown): boolean {
  return typeof presentedKey === "string" && constantTimeEqual(presentedKey, config.adminAccessKey);
}

export function setAdminSession(response: Response, config: RuntimeConfig): void {
  response.cookie(COOKIE_NAME, issueSession(config.adminAccessKey), {
    httpOnly: true,
    secure: config.secureCookies,
    sameSite: "strict",
    maxAge: SESSION_SECONDS * 1000,
    path: "/",
  });
}

export function clearAdminSession(response: Response, config: RuntimeConfig): void {
  response.clearCookie(COOKIE_NAME, {
    httpOnly: true,
    secure: config.secureCookies,
    sameSite: "strict",
    path: "/",
  });
}

export function requireAdmin(config: RuntimeConfig) {
  return (request: Request, response: Response, next: NextFunction): void => {
    if (!validSession(request.cookies?.[COOKIE_NAME], config.adminAccessKey)) {
      response.status(401).json({ error: "Administrator authentication required" });
      return;
    }
    next();
  };
}

export function requireAdminMutation(request: Request, response: Response, next: NextFunction): void {
  if (request.get("X-Anilili-Admin") !== "1") {
    response.status(403).json({ error: "Missing administrator request proof" });
    return;
  }
  next();
}
