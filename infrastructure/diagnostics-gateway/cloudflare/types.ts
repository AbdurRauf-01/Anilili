export type DiagnosticTrigger = "manual" | "crash" | "slow_start" | "shortcut";

export interface ArchiveDetails {
  expandedBytes: number;
  entryCount: number;
  manifestVersion: string;
  validation: "zip-central-directory";
}

export interface ReportMetadata extends ArchiveDetails {
  reportId: string;
  receivedUtc: string;
  receivedBytes: number;
  trigger: DiagnosticTrigger;
  appVersion: string;
  versionCode: string;
  buildSha: string;
  platform: string;
  privacy: "client-redacted";
  description?: string;
  screenshotBytes?: number;
  screenshotContentType?: "image/jpeg" | "image/png" | "image/webp";
  /** Absent on reports uploaded before source telemetry existed, or with no playback in them. */
  sourceHealth?: SourceHealthRollup;
}

export type SourceAttemptOutcome = "ok" | "empty" | "timeout" | "failed";

/** Per-server counters folded out of one report's `source/resolve.summary` events. */
export interface SourceProviderStat {
  attempts: number;
  ok: number;
  empty: number;
  timeout: number;
  failed: number;
  /** Times this server was the one that actually produced the played stream. */
  chosen: number;
  totalMs: number;
  medianMs: number;
}

export interface SourceHealthRollup {
  resolves: number;
  /** Resolves that ended with a playable stream from any server. */
  resolved: number;
  providers: Record<string, SourceProviderStat>;
}

export interface DailyUsage {
  reports: number;
  bytes: number;
}

export interface StoredObject {
  key: string;
  size: number;
  lastModified: string;
}
