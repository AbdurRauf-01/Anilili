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
