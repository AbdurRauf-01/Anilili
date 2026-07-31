import { env } from "cloudflare:workers";
import { createExecutionContext, waitOnExecutionContext } from "cloudflare:test";
import { strToU8, zipSync } from "fflate";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import worker from "./index";

const IncomingRequest = Request<unknown, IncomingRequestCfProperties>;

async function clearKv(): Promise<void> {
  let cursor: string | undefined;
  do {
    const page = await env.RATE_LIMIT.list({ cursor });
    await Promise.all(page.keys.map((key) => env.RATE_LIMIT.delete(key.name)));
    cursor = page.list_complete ? undefined : page.cursor;
  } while (cursor);
}

function reportZip(): Uint8Array {
  return zipSync({
    "manifest.json": strToU8('{"appVersion":"0.1.51"}'),
    "events.jsonl": strToU8('{"name":"playback.buffer","attributes":{"host":"cdn.example"}}\n'),
    "summary.txt": strToU8("redacted diagnostic summary"),
  });
}

function uploadRequest(reportId = "ANL-20260730-ABC123DE45"): Request {
  const zip = reportZip();
  const form = new FormData();
  form.set("report_id", reportId);
  form.set("trigger", "crash");
  form.set("app_version", "0.1.51");
  form.set("version_code", "52");
  form.set("build_sha", "abc1234");
  form.set("platform", "android");
  form.set("report", new File([zip], `${reportId}.zip`, { type: "application/zip" }));
  return new IncomingRequest("https://diagnostics.example/v1/reports", {
    method: "POST",
    headers: {
      "Content-Length": String(zip.byteLength + 4_096),
      "CF-Connecting-IP": "203.0.113.10",
    },
    body: form,
  });
}

async function dispatch(request: Request): Promise<Response> {
  const context = createExecutionContext();
  const response = await worker.fetch(request as never, env, context);
  await waitOnExecutionContext(context);
  return response;
}

beforeEach(async () => {
  await clearKv();
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("Cloudflare diagnostics gateway", () => {
  it("checks the private Hugging Face bucket in health responses", async () => {
    const outbound = vi.fn(async () => new Response(
      '<?xml version="1.0"?><ListBucketResult><Name>anilili-diagnostics</Name></ListBucketResult>',
      { status: 200, headers: { "Content-Type": "application/xml" } },
    ));
    vi.stubGlobal("fetch", outbound);

    const response = await dispatch(new IncomingRequest("https://diagnostics.example/health"));
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({
      status: "ok",
      storage: "private-hf-bucket",
      retentionDays: 30,
    });
    expect(outbound).toHaveBeenCalledTimes(1);
  });

  it("accepts an Android-compatible multipart report and indexes it", async () => {
    const methods: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const request = input instanceof Request ? input : new Request(input, init);
      methods.push(request.method);
      if (request.method === "GET") {
        return new Response("<ListBucketResult></ListBucketResult>", {
          status: 200,
          headers: { "Content-Type": "application/xml" },
        });
      }
      return new Response(null, { status: 200 });
    }));

    const response = await dispatch(uploadRequest());
    expect(response.status).toBe(200);
    expect(await response.json()).toMatchObject({
      status: "accepted",
      reportId: "ANL-20260730-ABC123DE45",
    });
    expect(methods).toEqual(["GET", "PUT", "PUT"]);
    const indexed = await env.RATE_LIMIT.list({ prefix: "report:" });
    expect(indexed.keys).toHaveLength(1);
  });

  it("rejects malformed uploads before storing them", async () => {
    const request = uploadRequest("not-a-report-id");
    const response = await dispatch(request);
    expect(response.status).toBe(400);
    expect(await response.json()).toEqual({ error: "Invalid report reference" });
  });

  it("requires the configured administrator key and issues an HttpOnly cookie", async () => {
    const wrong = await dispatch(new IncomingRequest("https://diagnostics.example/api/admin/login", {
      method: "POST",
      headers: { "Content-Type": "application/json", "CF-Connecting-IP": "203.0.113.11" },
      body: JSON.stringify({ accessKey: "incorrect-key-that-is-long-enough" }),
    }));
    expect(wrong.status).toBe(401);

    const correct = await dispatch(new IncomingRequest("https://diagnostics.example/api/admin/login", {
      method: "POST",
      headers: { "Content-Type": "application/json", "CF-Connecting-IP": "203.0.113.11" },
      body: JSON.stringify({ accessKey: env.ADMIN_ACCESS_KEY }),
    }));
    expect(correct.status).toBe(200);
    expect(correct.headers.get("Set-Cookie")).toContain("HttpOnly");
    expect(correct.headers.get("Set-Cookie")).toContain("SameSite=Strict");
  });

  it("does not expose admin routes without a signed session", async () => {
    const response = await dispatch(
      new IncomingRequest("https://diagnostics.example/api/admin/reports"),
    );
    expect(response.status).toBe(401);
  });

  it("bounds login bodies even when Content-Length is omitted", async () => {
    const response = await dispatch(new IncomingRequest("https://diagnostics.example/api/admin/login", {
      method: "POST",
      headers: { "Content-Type": "application/json", "CF-Connecting-IP": "203.0.113.12" },
      body: JSON.stringify({ accessKey: "x".repeat(5_000) }),
    }));
    expect(response.status).toBe(413);
    expect(await response.json()).toEqual({ error: "Request body is too large" });
  });
});
