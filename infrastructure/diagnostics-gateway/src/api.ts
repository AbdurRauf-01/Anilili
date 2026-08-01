export type Trigger = "manual" | "crash" | "slow_start" | "shortcut";

export interface SourceProviderStat {
  attempts: number;
  ok: number;
  empty: number;
  timeout: number;
  failed: number;
  chosen: number;
  totalMs: number;
  medianMs: number;
}

export interface SourceHealthRollup {
  resolves: number;
  resolved: number;
  providers: Record<string, SourceProviderStat>;
}

export interface ReportMetadata {
  reportId: string;
  receivedUtc: string;
  receivedBytes: number;
  trigger: Trigger;
  appVersion: string;
  versionCode: string;
  buildSha: string;
  platform: string;
  expandedBytes: number;
  entryCount: number;
  manifestVersion: string;
  description?: string;
  screenshotBytes?: number;
  screenshotContentType?: "image/jpeg" | "image/png" | "image/webp";
  sourceHealth?: SourceHealthRollup;
}

export interface ReportResponse {
  reports: ReportMetadata[];
  today: { reports: number; bytes: number };
  retentionDays: number;
}

export interface SourceHealthRow extends SourceProviderStat {
  provider: string;
  successRate: number;
}

export interface SourceHealthResponse {
  reports: number;
  reportsWithPlayback: number;
  resolves: number;
  resolved: number;
  providers: SourceHealthRow[];
}

export const TRIGGER_LABEL: Record<Trigger, string> = {
  crash: "Crash",
  slow_start: "Performance",
  manual: "Manual",
  shortcut: "Shortcut",
};

export async function jsonRequest<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, {
    credentials: "same-origin",
    ...init,
    headers: {
      ...(init?.body ? { "Content-Type": "application/json" } : {}),
      ...init?.headers,
    },
  });
  const body = (await response.json().catch(() => ({}))) as T & { error?: string };
  if (!response.ok) throw new Error(body.error ?? `Request failed (${response.status})`);
  return body;
}

export function formatBytes(bytes: number): string {
  if (bytes < 1_000) return `${bytes} B`;
  if (bytes < 1_000_000) return `${(bytes / 1_000).toFixed(1)} KB`;
  return `${(bytes / 1_000_000).toFixed(1)} MB`;
}

/** Relative age is what triage actually reads — "2h" beats a formatted timestamp when scanning. */
export function formatAge(value: string): string {
  const deltaMs = Date.now() - new Date(value).getTime();
  const minutes = Math.floor(deltaMs / 60_000);
  if (minutes < 1) return "now";
  if (minutes < 60) return `${minutes}m`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h`;
  return `${Math.floor(hours / 24)}d`;
}

export function formatExactTime(value: string): string {
  return new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "short" })
    .format(new Date(value));
}
