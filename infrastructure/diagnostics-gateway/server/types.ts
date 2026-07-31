export type DiagnosticTrigger = "manual" | "crash" | "slow_start" | "shortcut";

export interface ArchiveDetails {
  expandedBytes: number;
  entryCount: number;
  manifestVersion: string;
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
  description?: string;
  screenshotBytes?: number;
  screenshotContentType?: "image/jpeg" | "image/png" | "image/webp";
}

export interface ScreenshotUpload {
  path: string;
  bytes: number;
  contentType: "image/jpeg" | "image/png" | "image/webp";
}

export interface StoredScreenshot {
  bytes: Buffer;
  contentType: string;
}

export interface DailyUsage {
  reports: number;
  bytes: number;
}

export interface CleanupResult {
  deletedReports: number;
  retainedBytes: number;
}

export interface ReportStore {
  healthCheck(): Promise<void>;
  hasReport(reportId: string): Promise<boolean>;
  putReport(
    reportId: string,
    zipPath: string,
    screenshot: ScreenshotUpload | null,
    metadata: ReportMetadata,
  ): Promise<void>;
  listReports(limit: number): Promise<ReportMetadata[]>;
  getReport(reportId: string): Promise<Buffer | null>;
  getScreenshot(reportId: string): Promise<StoredScreenshot | null>;
  deleteReport(reportId: string): Promise<boolean>;
  dailyUsage(day: string): Promise<DailyUsage>;
  cleanup(retentionBefore: Date, maxStoredBytes: number): Promise<CleanupResult>;
}
