import { AwsClient } from "aws4fetch";
import type { ReportMetadata, StoredObject } from "./types";

function encodePath(value: string): string {
  return value.split("/").map(encodeURIComponent).join("/");
}

function decodeXml(value: string): string {
  return value
    .replaceAll("&lt;", "<")
    .replaceAll("&gt;", ">")
    .replaceAll("&quot;", '"')
    .replaceAll("&#39;", "'")
    .replaceAll("&amp;", "&");
}

function xmlTag(xml: string, name: string): string | null {
  const match = xml.match(new RegExp(`<${name}>([\\s\\S]*?)</${name}>`));
  return match ? decodeXml(match[1]) : null;
}

export class StorageError extends Error {
  constructor(
    readonly operation: string,
    readonly status: number,
  ) {
    super(`Hugging Face storage ${operation} failed (${status})`);
    this.name = "StorageError";
  }
}

function ensureStorageResponse(response: Response, operation: string): Response {
  if (response.ok) return response;
  void response.body?.cancel();
  throw new StorageError(operation, response.status);
}

export function reportPrefix(reportId: string): string {
  const day = reportId.slice(4, 12);
  return `reports/${day.slice(0, 4)}/${day.slice(4, 6)}/${day.slice(6, 8)}/${reportId}`;
}

export class HfReportStore {
  private readonly client: AwsClient;
  private readonly baseUrl: string;

  constructor(private readonly env: Env) {
    this.baseUrl = `https://s3.hf.co/${encodeURIComponent(env.HF_NAMESPACE)}/${encodeURIComponent(env.HF_BUCKET)}`;
    this.client = new AwsClient({
      accessKeyId: env.HF_S3_ACCESS_KEY_ID,
      secretAccessKey: env.HF_S3_SECRET_ACCESS_KEY,
      service: "s3",
      region: "us-east-1",
      retries: 2,
      initRetryMs: 80,
    });
  }

  private objectUrl(key: string): string {
    return `${this.baseUrl}/${encodePath(key)}`;
  }

  private async signedFetch(url: string, init: RequestInit = {}): Promise<Response> {
    return this.client.fetch(url, {
      ...init,
      aws: { service: "s3", region: "us-east-1", allHeaders: true },
    });
  }

  async healthCheck(): Promise<void> {
    await this.listObjects("", 1);
  }

  async hasReport(reportId: string): Promise<boolean> {
    const key = `${reportPrefix(reportId)}.zip`;
    const matches = await this.listObjects(key, 1);
    return matches.some((object) => object.key === key);
  }

  async putReport(reportId: string, report: File, metadata: ReportMetadata): Promise<void> {
    const prefix = reportPrefix(reportId);
    const zipResponse = await this.signedFetch(this.objectUrl(`${prefix}.zip`), {
      method: "PUT",
      headers: { "Content-Type": "application/zip" },
      body: report,
    });
    ensureStorageResponse(zipResponse, "upload");
    try {
      const metadataResponse = await this.signedFetch(this.objectUrl(`${prefix}.json`), {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(metadata),
      });
      ensureStorageResponse(metadataResponse, "metadata upload");
    } catch (error) {
      await this.deleteObject(`${prefix}.zip`).catch(() => undefined);
      throw error;
    }
  }

  async getReport(reportId: string): Promise<Response | null> {
    const response = await this.signedFetch(this.objectUrl(`${reportPrefix(reportId)}.zip`));
    if (response.status === 404) {
      void response.body?.cancel();
      return null;
    }
    return ensureStorageResponse(response, "download");
  }

  async deleteReport(reportId: string): Promise<void> {
    const prefix = reportPrefix(reportId);
    await Promise.all([this.deleteObject(`${prefix}.zip`), this.deleteObject(`${prefix}.json`)]);
  }

  async listObjects(prefix: string, maximum = 1_000): Promise<StoredObject[]> {
    const url = new URL(this.baseUrl);
    url.searchParams.set("list-type", "2");
    url.searchParams.set("prefix", prefix);
    url.searchParams.set("max-keys", String(Math.min(1_000, Math.max(1, maximum))));
    const response = ensureStorageResponse(await this.signedFetch(url.toString()), "list");
    const xml = await response.text();
    const blocks = xml.match(/<Contents>[\s\S]*?<\/Contents>/g) ?? [];
    return blocks.flatMap((block): StoredObject[] => {
      const key = xmlTag(block, "Key");
      if (!key) return [];
      return [{
        key,
        size: Number(xmlTag(block, "Size") ?? 0),
        lastModified: xmlTag(block, "LastModified") ?? "",
      }];
    });
  }

  private async deleteObject(key: string): Promise<void> {
    const response = await this.signedFetch(this.objectUrl(key), { method: "DELETE" });
    if (response.status !== 404) ensureStorageResponse(response, "delete");
  }
}
