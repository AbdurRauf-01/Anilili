import type { DiagnosticTrigger } from "./types";

export const MAX_COMPRESSED_BYTES = 25_000_000;
export const MAX_MULTIPART_BYTES = MAX_COMPRESSED_BYTES + 64_000;
export const MAX_EXPANDED_BYTES = 50_000_000;
export const MAX_ZIP_ENTRIES = 64;
export const MAX_REPORTS_PER_IP_HOUR = 8;
export const MAX_REPORTS_PER_DAY = 200;
export const MAX_BYTES_PER_DAY = 2_000_000_000;
export const RETENTION_DAYS = 30;
export const ADMIN_REPORT_LIMIT = 40;
export const SESSION_SECONDS = 8 * 60 * 60;
export const LOGIN_WINDOW_SECONDS = 15 * 60;
export const MAX_LOGIN_FAILURES = 8;

export const REPORT_ID_PATTERN = /^ANL-[0-9]{8}-[A-Z0-9]{10}$/;
export const SAFE_FIELD_PATTERN = /^[A-Za-z0-9._-]{1,80}$/;
export const VALID_TRIGGERS = new Set<DiagnosticTrigger>([
  "manual",
  "crash",
  "slow_start",
  "shortcut",
]);
