import {
  clearAdminCookie,
  constantTimeEqual,
  issueAdminCookie,
  loginIsBlocked,
  recordLoginFailure,
  requireAdmin,
  requireAdminMutation,
  reserveUpload,
} from "./auth";
import { inspectDiagnosticZip } from "./archive";
import {
  ADMIN_REPORT_LIMIT,
  MAX_BYTES_PER_DAY,
  MAX_COMPRESSED_BYTES,
  MAX_MULTIPART_BYTES,
  MAX_REPORTS_PER_DAY,
  REPORT_ID_PATTERN,
  RETENTION_DAYS,
  SAFE_FIELD_PATTERN,
  VALID_TRIGGERS,
} from "./constants";
import { HfReportStore, StorageError } from "./hf-store";
import { boundedJson, HttpError, jsonResponse, withSecurityHeaders } from "./http";
import type { DailyUsage, DiagnosticTrigger, ReportMetadata } from "./types";

const STARTED_AT = Date.now();

function utcDay(date = new Date()): string {
  return date.toISOString().slice(0, 10);
}

function metadataField(form: FormData, name: string): string {
  const value = form.get(name);
  if (typeof value !== "string" || !SAFE_FIELD_PATTERN.test(value)) {
    throw new HttpError(400, "Invalid report metadata");
  }
  return value;
}

function reportIndexKey(metadata: ReportMetadata): string {
  const timestamp = String(Date.parse(metadata.receivedUtc)).padStart(13, "0");
  return `report:${timestamp}:${metadata.reportId}`;
}

function dailyKey(day: string): string {
  return `daily:${day}`;
}

async function dailyUsage(env: Env, day = utcDay()): Promise<DailyUsage> {
  return (await env.RATE_LIMIT.get<DailyUsage>(dailyKey(day), "json")) ?? { reports: 0, bytes: 0 };
}

async function updateDailyUsage(env: Env, usage: DailyUsage): Promise<void> {
  await env.RATE_LIMIT.put(dailyKey(utcDay()), JSON.stringify(usage), { expirationTtl: 3 * 86_400 });
}

async function indexReport(env: Env, metadata: ReportMetadata): Promise<void> {
  const key = reportIndexKey(metadata);
  await Promise.all([
    env.RATE_LIMIT.put(key, JSON.stringify(metadata), { expirationTtl: (RETENTION_DAYS + 2) * 86_400 }),
    env.RATE_LIMIT.put(`report-id:${metadata.reportId}`, key, { expirationTtl: (RETENTION_DAYS + 2) * 86_400 }),
  ]);
}

async function removeReportIndex(env: Env, reportId: string): Promise<void> {
  const idKey = `report-id:${reportId}`;
  const indexKey = await env.RATE_LIMIT.get(idKey);
  await Promise.all([
    env.RATE_LIMIT.delete(idKey),
    indexKey ? env.RATE_LIMIT.delete(indexKey) : Promise.resolve(),
  ]);
}

async function listIndexedReports(env: Env, limit: number): Promise<ReportMetadata[]> {
  const listed = await env.RATE_LIMIT.list({ prefix: "report:", limit: 1_000 });
  const selected = listed.keys.slice().reverse().slice(0, limit);
  const reports = await Promise.all(
    selected.map((key) => env.RATE_LIMIT.get<ReportMetadata>(key.name, "json")),
  );
  return reports
    .filter((report): report is ReportMetadata => Boolean(report))
    .sort((left, right) => right.receivedUtc.localeCompare(left.receivedUtc));
}

function validateEnvironment(env: Env): void {
  if (
    env.ADMIN_ACCESS_KEY.length < 24 ||
    env.RATE_LIMIT_SECRET.length < 24 ||
    !env.HF_S3_ACCESS_KEY_ID ||
    !env.HF_S3_SECRET_ACCESS_KEY ||
    !env.HF_NAMESPACE ||
    !env.HF_BUCKET
  ) {
    throw new Error("Required Worker secrets are not configured");
  }
}

function runtimeHeaders(started = STARTED_AT): HeadersInit {
  return {
    "X-Anilili-Cold-Start": String(Date.now() - started < 120_000),
    "X-Anilili-Instance-Age": String(Math.floor((Date.now() - started) / 1_000)),
  };
}

function logEvent(name: string, attributes: Record<string, unknown> = {}): void {
  console.log(JSON.stringify({ timestamp: new Date().toISOString(), name, ...attributes }));
}

async function uploadReport(request: Request, env: Env): Promise<Response> {
  const started = performance.now();
  const contentLength = Number(request.headers.get("Content-Length"));
  if (!Number.isFinite(contentLength) || contentLength <= 0) {
    throw new HttpError(411, "Content-Length is required");
  }
  if (contentLength > MAX_MULTIPART_BYTES) {
    throw new HttpError(413, "Diagnostic report is too large");
  }
  if (!(request.headers.get("Content-Type") ?? "").toLowerCase().startsWith("multipart/form-data;")) {
    throw new HttpError(400, "Diagnostic report must use multipart form data");
  }

  const form = await request.formData();
  const reportId = metadataField(form, "report_id");
  if (!REPORT_ID_PATTERN.test(reportId)) throw new HttpError(400, "Invalid report reference");
  const trigger = metadataField(form, "trigger") as DiagnosticTrigger;
  if (!VALID_TRIGGERS.has(trigger)) throw new HttpError(400, "Invalid diagnostic trigger");
  const appVersion = metadataField(form, "app_version");
  const versionCode = metadataField(form, "version_code");
  const buildSha = metadataField(form, "build_sha");
  const platform = metadataField(form, "platform");
  const reportValue = form.get("report");
  if (!(reportValue instanceof File) || reportValue.size <= 0) {
    throw new HttpError(400, "Diagnostic report is missing");
  }
  if (reportValue.size > MAX_COMPRESSED_BYTES) {
    throw new HttpError(413, "Diagnostic report is too large");
  }
  if (reportValue.type && !new Set(["application/zip", "application/octet-stream"]).has(reportValue.type)) {
    throw new HttpError(400, "Diagnostic report must be a ZIP archive");
  }

  const store = new HfReportStore(env);
  if (await store.hasReport(reportId)) {
    return jsonResponse(
      { status: "accepted", reportId, receivedBytes: reportValue.size },
      200,
      runtimeHeaders(),
    );
  }

  await reserveUpload(request, env);
  const usage = await dailyUsage(env);
  if (usage.reports >= MAX_REPORTS_PER_DAY || usage.bytes + reportValue.size > MAX_BYTES_PER_DAY) {
    throw new HttpError(429, "Daily diagnostic capacity reached; the app will retry later");
  }

  const archive = inspectDiagnosticZip(await reportValue.arrayBuffer(), appVersion);
  const validationMs = performance.now() - started;
  const metadata: ReportMetadata = {
    reportId,
    receivedUtc: new Date().toISOString(),
    receivedBytes: reportValue.size,
    trigger,
    appVersion,
    versionCode,
    buildSha,
    platform,
    privacy: "client-redacted",
    ...archive,
  };
  const storageStarted = performance.now();
  await store.putReport(reportId, reportValue, metadata);
  try {
    await Promise.all([
      indexReport(env, metadata),
      updateDailyUsage(env, {
        reports: usage.reports + 1,
        bytes: usage.bytes + reportValue.size,
      }),
    ]);
  } catch (error) {
    await store.deleteReport(reportId).catch(() => undefined);
    throw error;
  }
  const storageMs = performance.now() - storageStarted;
  logEvent("report.accepted", { reportId, trigger, bytes: reportValue.size });
  return jsonResponse(
    { status: "accepted", reportId, receivedBytes: reportValue.size },
    200,
    {
      ...runtimeHeaders(),
      "Server-Timing": `validate;dur=${validationMs.toFixed(1)}, store;dur=${storageMs.toFixed(1)}`,
    },
  );
}

async function adminLogin(request: Request, env: Env): Promise<Response> {
  if (await loginIsBlocked(request, env)) {
    throw new HttpError(429, "Too many sign-in attempts; try again later");
  }
  const body = await boundedJson(request);
  const accessKey = body && typeof body === "object" && "accessKey" in body
    ? (body as { accessKey?: unknown }).accessKey
    : undefined;
  if (typeof accessKey !== "string" || !(await constantTimeEqual(accessKey, env.ADMIN_ACCESS_KEY))) {
    await recordLoginFailure(request, env);
    throw new HttpError(401, "Incorrect administrator access key");
  }
  return jsonResponse(
    { authenticated: true },
    200,
    { "Set-Cookie": await issueAdminCookie(env) },
  );
}

async function adminReports(request: Request, env: Env): Promise<Response> {
  await requireAdmin(request, env);
  const requested = Number(new URL(request.url).searchParams.get("limit") ?? ADMIN_REPORT_LIMIT);
  const limit = Math.min(ADMIN_REPORT_LIMIT, Math.max(1, Number.isFinite(requested) ? requested : ADMIN_REPORT_LIMIT));
  const [reports, today] = await Promise.all([listIndexedReports(env, limit), dailyUsage(env)]);
  return jsonResponse({ reports, today, retentionDays: RETENTION_DAYS });
}

async function downloadReport(request: Request, env: Env, reportId: string): Promise<Response> {
  await requireAdmin(request, env);
  if (!REPORT_ID_PATTERN.test(reportId)) throw new HttpError(400, "Invalid report reference");
  const stored = await new HfReportStore(env).getReport(reportId);
  if (!stored) throw new HttpError(404, "Report not found");
  const headers = new Headers(stored.headers);
  headers.set("Content-Type", "application/zip");
  headers.set("Content-Disposition", `attachment; filename="${reportId}.zip"`);
  headers.set("Cache-Control", "private, no-store");
  return withSecurityHeaders(new Response(stored.body, { status: 200, headers }));
}

async function deleteReport(request: Request, env: Env, reportId: string): Promise<Response> {
  await requireAdmin(request, env);
  requireAdminMutation(request);
  if (!REPORT_ID_PATTERN.test(reportId)) throw new HttpError(400, "Invalid report reference");
  const store = new HfReportStore(env);
  const existed = await store.hasReport(reportId);
  if (existed) await store.deleteReport(reportId);
  await removeReportIndex(env, reportId);
  return jsonResponse({ deleted: existed }, existed ? 200 : 404);
}

async function cleanupExpired(env: Env): Promise<void> {
  validateEnvironment(env);
  const cutoff = Date.now() - RETENTION_DAYS * 86_400_000;
  const listed = await env.RATE_LIMIT.list({ prefix: "report:", limit: 100 });
  const expired = listed.keys
    .filter((key) => Number(key.name.split(":")[1]) < cutoff)
    .slice(0, 20);
  const store = new HfReportStore(env);
  let deleted = 0;
  for (const key of expired) {
    const metadata = await env.RATE_LIMIT.get<ReportMetadata>(key.name, "json");
    if (!metadata) {
      await env.RATE_LIMIT.delete(key.name);
      continue;
    }
    await store.deleteReport(metadata.reportId);
    await removeReportIndex(env, metadata.reportId);
    deleted += 1;
  }
  logEvent("retention.completed", { deleted });
}

async function route(request: Request, env: Env): Promise<Response> {
  validateEnvironment(env);
  const url = new URL(request.url);
  const key = `${request.method} ${url.pathname}`;

  if (key === "GET /health") {
    await new HfReportStore(env).healthCheck();
    return jsonResponse(
      { status: "ok", storage: "private-hf-bucket", retentionDays: RETENTION_DAYS },
      200,
      runtimeHeaders(),
    );
  }
  if (key === "POST /v1/reports") return uploadReport(request, env);
  if (key === "POST /api/admin/login") return adminLogin(request, env);
  if (key === "GET /api/admin/session") {
    await requireAdmin(request, env);
    return jsonResponse({ authenticated: true });
  }
  if (key === "POST /api/admin/logout") {
    await requireAdmin(request, env);
    requireAdminMutation(request);
    return jsonResponse({ authenticated: false }, 200, { "Set-Cookie": clearAdminCookie() });
  }
  if (key === "GET /api/admin/reports") return adminReports(request, env);

  const reportMatch = url.pathname.match(/^\/api\/admin\/reports\/(ANL-[0-9]{8}-[A-Z0-9]{10})(?:\/(download))?$/);
  if (reportMatch && request.method === "GET" && reportMatch[2] === "download") {
    return downloadReport(request, env, reportMatch[1]);
  }
  if (reportMatch && request.method === "DELETE" && !reportMatch[2]) {
    return deleteReport(request, env, reportMatch[1]);
  }
  if (url.pathname.startsWith("/api/") || url.pathname.startsWith("/v1/") || url.pathname === "/health") {
    throw new HttpError(404, "Not found");
  }
  return env.ASSETS.fetch(request);
}

export default {
  async fetch(request, env, _ctx): Promise<Response> {
    try {
      return await route(request, env);
    } catch (error) {
      if (error instanceof HttpError) return jsonResponse({ error: error.message }, error.status);
      if (error instanceof StorageError) {
        logEvent("storage.failed", { operation: error.operation, status: error.status });
      } else {
        logEvent("request.failed", { errorType: error instanceof Error ? error.name : "UnknownError" });
      }
      return jsonResponse({ error: "Diagnostic storage is temporarily unavailable" }, 503);
    }
  },
  async scheduled(_controller, env, _ctx): Promise<void> {
    try {
      await cleanupExpired(env);
    } catch (error) {
      logEvent("retention.failed", { errorType: error instanceof Error ? error.name : "UnknownError" });
      throw error;
    }
  },
} satisfies ExportedHandler<Env>;
