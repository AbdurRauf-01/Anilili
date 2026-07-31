import { mkdtemp, readFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { zipSync, strToU8 } from "fflate";
import request from "supertest";
import { beforeEach, describe, expect, it } from "vitest";
import { createApp } from "./app.js";
import type {
  CleanupResult,
  DailyUsage,
  ReportMetadata,
  ReportStore,
} from "./types.js";

class MemoryReportStore implements ReportStore {
  readonly reports = new Map<string, { zip: Buffer; metadata: ReportMetadata }>();

  async healthCheck(): Promise<void> {}

  async hasReport(reportId: string): Promise<boolean> {
    return this.reports.has(reportId);
  }

  async putReport(reportId: string, zipPath: string, metadata: ReportMetadata): Promise<void> {
    this.reports.set(reportId, { zip: await readFile(zipPath), metadata });
  }

  async listReports(limit: number): Promise<ReportMetadata[]> {
    return [...this.reports.values()]
      .map(({ metadata }) => metadata)
      .sort((left, right) => right.receivedUtc.localeCompare(left.receivedUtc))
      .slice(0, limit);
  }

  async getReport(reportId: string): Promise<Buffer | null> {
    return this.reports.get(reportId)?.zip ?? null;
  }

  async deleteReport(reportId: string): Promise<boolean> {
    return this.reports.delete(reportId);
  }

  async dailyUsage(day: string): Promise<DailyUsage> {
    const reports = [...this.reports.values()].filter(({ metadata }) => metadata.receivedUtc.startsWith(day));
    return {
      reports: reports.length,
      bytes: reports.reduce((total, report) => total + report.metadata.receivedBytes, 0),
    };
  }

  async cleanup(): Promise<CleanupResult> {
    return {
      deletedReports: 0,
      retainedBytes: [...this.reports.values()].reduce((total, report) => total + report.zip.length, 0),
    };
  }
}

function diagnosticZip(events = '{"category":"app","name":"process.start"}\n'): Buffer {
  return Buffer.from(
    zipSync({
      "manifest.json": strToU8(JSON.stringify({ appVersion: "0.1.51", versionCode: 52 })),
      "events.jsonl": strToU8(events),
      "summary.txt": strToU8("Anilili diagnostic report"),
    }),
  );
}

function reportRequest(app: ReturnType<typeof createApp>, reportId: string, archive = diagnosticZip()) {
  return request(app)
    .post("/v1/reports")
    .field("report_id", reportId)
    .field("trigger", "manual")
    .field("app_version", "0.1.51")
    .field("version_code", "52")
    .field("build_sha", "abcdef123456")
    .field("platform", "android")
    .attach("report", archive, { filename: `${reportId}.zip`, contentType: "application/zip" });
}

describe("diagnostic gateway", () => {
  let store: MemoryReportStore;
  let app: ReturnType<typeof createApp>;

  beforeEach(async () => {
    store = new MemoryReportStore();
    app = createApp({
      store,
      config: {
        adminAccessKey: "admin-access-key-with-more-than-24-characters",
        rateLimitSecret: "rate-limit-secret-with-more-than-24-characters",
        tempDirectory: await mkdtemp(join(tmpdir(), "anilili-diagnostics-")),
        secureCookies: false,
      },
    });
  });

  it("reports private storage health", async () => {
    const response = await request(app).get("/health");
    expect(response.status).toBe(200);
    expect(response.body).toMatchObject({ status: "ok", storage: "private-hf-bucket" });
  });

  it("accepts the Android multipart contract and stores one report", async () => {
    const response = await reportRequest(app, "ANL-20260730-ABC123DE45");
    expect(response.status).toBe(200);
    expect(response.body).toMatchObject({
      status: "accepted",
      reportId: "ANL-20260730-ABC123DE45",
    });
    expect(response.headers["server-timing"]).toContain("validate;dur=");
    expect(store.reports.get("ANL-20260730-ABC123DE45")?.metadata).toMatchObject({
      appVersion: "0.1.51",
      entryCount: 3,
      trigger: "manual",
    });
  });

  it("treats a repeated report ID as idempotent", async () => {
    expect((await reportRequest(app, "ANL-20260730-ABC123DE45")).status).toBe(200);
    expect((await reportRequest(app, "ANL-20260730-ABC123DE45")).status).toBe(200);
    expect(store.reports.size).toBe(1);
  });

  it("rejects an archive containing an unredacted cookie", async () => {
    const response = await reportRequest(
      app,
      "ANL-20260730-COOKI00001",
      diagnosticZip('{"cookie":"session-secret-value"}\n'),
    );
    expect(response.status).toBe(400);
    expect(response.body.error).toContain("unredacted secret");
    expect(store.reports.size).toBe(0);
  });

  it("requires an authenticated administrator for report access", async () => {
    expect((await request(app).get("/api/admin/reports")).status).toBe(401);
    expect((await request(app).get("/api/admin/reports/ANL-20260730-ABC123DE45/download")).status).toBe(401);
  });

  it("allows an administrator to list, download, and delete a report", async () => {
    await reportRequest(app, "ANL-20260730-ABC123DE45");
    const agent = request.agent(app);
    const login = await agent
      .post("/api/admin/login")
      .send({ accessKey: "admin-access-key-with-more-than-24-characters" });
    expect(login.status).toBe(200);

    const listing = await agent.get("/api/admin/reports");
    expect(listing.status).toBe(200);
    expect(listing.body.reports[0].reportId).toBe("ANL-20260730-ABC123DE45");

    const download = await agent.get("/api/admin/reports/ANL-20260730-ABC123DE45/download");
    expect(download.status).toBe(200);
    expect(download.headers["content-type"]).toContain("application/zip");

    const deleted = await agent
      .delete("/api/admin/reports/ANL-20260730-ABC123DE45")
      .set("X-Anilili-Admin", "1");
    expect(deleted.status).toBe(200);
    expect(store.reports.size).toBe(0);
  });

  it("throttles repeated uploads from one address", async () => {
    for (let index = 0; index < 8; index += 1) {
      const suffix = `RATE${String(index).padStart(6, "0")}`;
      expect((await reportRequest(app, `ANL-20260730-${suffix}`)).status).toBe(200);
    }
    const rejected = await reportRequest(app, "ANL-20260730-RATE999999");
    expect(rejected.status).toBe(429);
  });
});
