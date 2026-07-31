import { createHmac } from "node:crypto";
import { MAX_REPORTS_PER_IP_HOUR } from "./config.js";

export class UploadRateLimitError extends Error {
  constructor() {
    super("Too many reports from this device; try again later");
    this.name = "UploadRateLimitError";
  }
}

/** Per-instance protection; the bucket-backed daily quota provides the cross-instance ceiling. */
export class UploadRateLimiter {
  private readonly recent = new Map<string, number[]>();

  constructor(private readonly secret: string) {}

  reserve(address: string, now = Date.now()): void {
    const key = createHmac("sha256", this.secret).update(address).digest("hex");
    const cutoff = now - 60 * 60 * 1000;
    const active = (this.recent.get(key) ?? []).filter((timestamp) => timestamp >= cutoff);
    if (active.length >= MAX_REPORTS_PER_IP_HOUR) throw new UploadRateLimitError();
    active.push(now);
    this.recent.set(key, active);
    if (this.recent.size > 2_000) {
      for (const [client, timestamps] of this.recent) {
        if (timestamps.every((timestamp) => timestamp < cutoff)) this.recent.delete(client);
      }
    }
  }
}

export class AdminLoginLimiter {
  private readonly recent = new Map<string, number[]>();

  allow(address: string, now = Date.now()): boolean {
    const cutoff = now - 15 * 60 * 1000;
    const active = (this.recent.get(address) ?? []).filter((timestamp) => timestamp >= cutoff);
    if (active.length >= 10) return false;
    active.push(now);
    this.recent.set(address, active);
    return true;
  }
}
