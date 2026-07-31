import { performance } from "node:perf_hooks";
import { unlink } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import cookieParser from "cookie-parser";
import express, { type NextFunction, type Request, type Response } from "express";
import helmet from "helmet";
import multer from "multer";
import {
  authenticateAdmin,
  clearAdminSession,
  requireAdmin,
  requireAdminMutation,
  setAdminSession,
} from "./auth.js";
import { ArchiveValidationError, validateArchive } from "./archive.js";
import {
  MAX_BYTES_PER_DAY,
  MAX_COMPRESSED_BYTES,
  MAX_REPORTS_PER_DAY,
  MAX_STORED_BYTES,
  RETENTION_DAYS,
  type RuntimeConfig,
} from "./config.js";
import { AdminLoginLimiter, UploadRateLimiter, UploadRateLimitError } from "./rate-limit.js";
import type { DiagnosticTrigger, ReportMetadata, ReportStore } from "./types.js";

const STARTED_AT = Date.now();
const REPORT_ID_PATTERN = /^ANL-[0-9]{8}-[A-Z0-9]{10}$/;
const SAFE_FIELD_PATTERN = /^[A-Za-z0-9._-]{1,80}$/;
const VALID_TRIGGERS = new Set<DiagnosticTrigger>(["manual", "crash", "slow_start", "shortcut"]);

interface AppDependencies {
  store: ReportStore;
  config: RuntimeConfig;
}

function utcDay(date = new Date()): string {
  return date.toISOString().slice(0, 10);
}

function reportIdIsValid(value: string): boolean {
  return REPORT_ID_PATTERN.test(value);
}

function metadataField(body: Record<string, unknown>, name: string): string {
  const value = body[name];
  if (typeof value !== "string" || !SAFE_FIELD_PATTERN.test(value)) {
    throw new ClientInputError("Invalid report metadata");
  }
  return value;
}

class ClientInputError extends Error {}
class DailyCapacityError extends Error {}

async function removeTemporary(path?: string): Promise<void> {
  if (path) await unlink(path).catch(() => undefined);
}

function logEvent(name: string, attributes: Record<string, unknown> = {}): void {
  console.log(JSON.stringify({ timestamp: new Date().toISOString(), name, ...attributes }));
}

export function createApp({ store, config }: AppDependencies) {
  const app = express();
  const uploadRateLimiter = new UploadRateLimiter(config.rateLimitSecret);
  const loginLimiter = new AdminLoginLimiter();
  const upload = multer({
    dest: config.tempDirectory,
    limits: {
      fileSize: MAX_COMPRESSED_BYTES,
      files: 1,
      fields: 8,
      parts: 9,
      fieldSize: 1_024,
    },
  });

  app.set("trust proxy", 1);
  app.disable("x-powered-by");
  app.use(
    helmet({
      crossOriginResourcePolicy: { policy: "same-origin" },
      contentSecurityPolicy: {
        directives: {
          "default-src": ["'self'"],
          "script-src": ["'self'"],
          "style-src": ["'self'"],
          "img-src": ["'self'", "data:"],
          "connect-src": ["'self'"],
        },
      },
    }),
  );
  app.use(cookieParser());
  app.use(express.json({ limit: "8kb" }));

  app.get("/health", async (_request, response, next) => {
    try {
      await store.healthCheck();
      response.set({
        "Cache-Control": "no-store",
        "X-Anilili-Instance-Age": String(Math.floor((Date.now() - STARTED_AT) / 1000)),
        "X-Anilili-Cold-Start": String(Date.now() - STARTED_AT < 120_000),
      });
      response.json({ status: "ok", storage: "private-hf-bucket", retentionDays: RETENTION_DAYS });
    } catch (error) {
      next(error);
    }
  });

  app.post("/v1/reports", upload.single("report"), async (request, response, next) => {
    const started = performance.now();
    const tempPath = request.file?.path;
    try {
      if (!request.file || !tempPath) throw new ClientInputError("Diagnostic report is missing");
      if (!new Set(["application/zip", "application/octet-stream"]).has(request.file.mimetype)) {
        throw new ClientInputError("Diagnostic report must be a ZIP archive");
      }
      const reportId = String(request.body.report_id ?? "");
      if (!reportIdIsValid(reportId)) throw new ClientInputError("Invalid report reference");
      const trigger = metadataField(request.body, "trigger") as DiagnosticTrigger;
      if (!VALID_TRIGGERS.has(trigger)) throw new ClientInputError("Invalid diagnostic trigger");
      const appVersion = metadataField(request.body, "app_version");
      const versionCode = metadataField(request.body, "version_code");
      const buildSha = metadataField(request.body, "build_sha");
      const platform = metadataField(request.body, "platform");

      if (await store.hasReport(reportId)) {
        await removeTemporary(tempPath);
        response.json({ status: "accepted", reportId, receivedBytes: request.file.size });
        return;
      }

      uploadRateLimiter.reserve(request.ip ?? request.socket.remoteAddress ?? "unknown");
      const archiveDetails = await validateArchive(tempPath);
      const validationMs = performance.now() - started;
      const usage = await store.dailyUsage(utcDay());
      if (
        usage.reports >= MAX_REPORTS_PER_DAY ||
        usage.bytes + request.file.size > MAX_BYTES_PER_DAY
      ) {
        throw new DailyCapacityError("Daily diagnostic capacity reached; the app will retry");
      }

      const metadata: ReportMetadata = {
        reportId,
        receivedUtc: new Date().toISOString(),
        receivedBytes: request.file.size,
        trigger,
        appVersion,
        versionCode,
        buildSha,
        platform,
        ...archiveDetails,
      };
      const storeStarted = performance.now();
      await store.putReport(reportId, tempPath, metadata);
      await removeTemporary(tempPath);
      const retentionBefore = new Date(Date.now() - RETENTION_DAYS * 24 * 60 * 60 * 1000);
      await store.cleanup(retentionBefore, MAX_STORED_BYTES);
      const storeMs = performance.now() - storeStarted;
      logEvent("report.accepted", { reportId, trigger, bytes: request.file.size });
      response.set({
        "Cache-Control": "no-store",
        "Server-Timing": `validate;dur=${validationMs.toFixed(1)}, store;dur=${storeMs.toFixed(1)}`,
        "X-Anilili-Instance-Age": String(Math.floor((Date.now() - STARTED_AT) / 1000)),
        "X-Anilili-Cold-Start": String(Date.now() - STARTED_AT < 120_000),
      });
      response.json({ status: "accepted", reportId, receivedBytes: request.file.size });
    } catch (error) {
      await removeTemporary(tempPath);
      next(error);
    }
  });

  app.post("/api/admin/login", (request, response) => {
    const address = request.ip ?? request.socket.remoteAddress ?? "unknown";
    if (!loginLimiter.allow(address)) {
      response.status(429).json({ error: "Too many sign-in attempts; try again later" });
      return;
    }
    if (!authenticateAdmin(config, request.body?.accessKey)) {
      response.status(401).json({ error: "Incorrect administrator access key" });
      return;
    }
    setAdminSession(response, config);
    response.set("Cache-Control", "no-store").json({ authenticated: true });
  });

  app.post("/api/admin/logout", requireAdmin(config), requireAdminMutation, (_request, response) => {
    clearAdminSession(response, config);
    response.json({ authenticated: false });
  });

  app.get("/api/admin/session", requireAdmin(config), (_request, response) => {
    response.set("Cache-Control", "no-store").json({ authenticated: true });
  });

  app.get("/api/admin/reports", requireAdmin(config), async (request, response, next) => {
    try {
      const requested = Number(request.query.limit ?? 100);
      const limit = Math.min(250, Math.max(1, Number.isFinite(requested) ? requested : 100));
      const reports = await store.listReports(limit);
      const today = await store.dailyUsage(utcDay());
      response.set("Cache-Control", "no-store").json({ reports, today, retentionDays: RETENTION_DAYS });
    } catch (error) {
      next(error);
    }
  });

  app.get("/api/admin/reports/:reportId/download", requireAdmin(config), async (request, response, next) => {
    try {
      const reportId = String(request.params.reportId);
      if (!reportIdIsValid(reportId)) throw new ClientInputError("Invalid report reference");
      const report = await store.getReport(reportId);
      if (!report) {
        response.status(404).json({ error: "Report not found" });
        return;
      }
      response.set({
        "Content-Type": "application/zip",
        "Content-Disposition": `attachment; filename="${reportId}.zip"`,
        "Cache-Control": "private, no-store",
      });
      response.send(report);
    } catch (error) {
      next(error);
    }
  });

  app.delete(
    "/api/admin/reports/:reportId",
    requireAdmin(config),
    requireAdminMutation,
    async (request, response, next) => {
      try {
        const reportId = String(request.params.reportId);
        if (!reportIdIsValid(reportId)) throw new ClientInputError("Invalid report reference");
        const deleted = await store.deleteReport(reportId);
        response.status(deleted ? 200 : 404).json({ deleted });
      } catch (error) {
        next(error);
      }
    },
  );

  const clientRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../client");
  app.use(express.static(clientRoot, { index: false, maxAge: "1h" }));
  app.get("/{*path}", (_request, response) => response.sendFile(resolve(clientRoot, "index.html")));

  app.use((error: unknown, _request: Request, response: Response, _next: NextFunction) => {
    if (error instanceof multer.MulterError && error.code === "LIMIT_FILE_SIZE") {
      response.status(413).json({ error: "Diagnostic report is too large" });
      return;
    }
    if (error instanceof ClientInputError || error instanceof ArchiveValidationError) {
      response.status(400).json({ error: error.message });
      return;
    }
    if (error instanceof UploadRateLimitError || error instanceof DailyCapacityError) {
      response.status(429).json({ error: error.message });
      return;
    }
    logEvent("request.failed", { errorType: error instanceof Error ? error.name : "UnknownError" });
    response.status(503).json({ error: "Diagnostic storage is temporarily unavailable" });
  });

  return app;
}
