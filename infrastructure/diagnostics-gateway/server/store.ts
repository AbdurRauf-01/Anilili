import { createReadStream } from "node:fs";
import { copyFile, mkdir, readFile, readdir, stat, unlink, writeFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import {
  DeleteObjectCommand,
  GetObjectCommand,
  HeadObjectCommand,
  ListObjectsV2Command,
  PutObjectCommand,
  S3Client,
  type _Object,
} from "@aws-sdk/client-s3";
import type {
  CleanupResult,
  DailyUsage,
  ReportMetadata,
  ReportStore,
  ScreenshotUpload,
  StoredScreenshot,
} from "./types.js";

interface S3StoreConfig {
  namespace: string;
  bucket: string;
  accessKeyId: string;
  secretAccessKey: string;
}

function reportPrefix(reportId: string): string {
  const day = reportId.slice(4, 12);
  return `reports/${day.slice(0, 4)}/${day.slice(4, 6)}/${day.slice(6, 8)}/${reportId}`;
}

function dayPrefix(day: string): string {
  const digits = day.replaceAll("-", "");
  return `reports/${digits.slice(0, 4)}/${digits.slice(4, 6)}/${digits.slice(6, 8)}/`;
}

async function bodyToBuffer(body: unknown): Promise<Buffer> {
  if (!body || typeof body !== "object" || !("transformToByteArray" in body)) {
    throw new Error("Storage returned an unreadable object body");
  }
  const bytes = await (body as { transformToByteArray(): Promise<Uint8Array> }).transformToByteArray();
  return Buffer.from(bytes);
}

export class S3ReportStore implements ReportStore {
  private readonly client: S3Client;
  private readonly bucket: string;

  constructor(config: S3StoreConfig) {
    this.bucket = config.bucket;
    this.client = new S3Client({
      endpoint: `https://s3.hf.co/${config.namespace}`,
      region: "us-east-1",
      forcePathStyle: true,
      requestChecksumCalculation: "WHEN_REQUIRED",
      responseChecksumValidation: "WHEN_REQUIRED",
      credentials: {
        accessKeyId: config.accessKeyId,
        secretAccessKey: config.secretAccessKey,
      },
    });
  }

  async healthCheck(): Promise<void> {
    await this.client.send(new ListObjectsV2Command({ Bucket: this.bucket, MaxKeys: 1 }));
  }

  async hasReport(reportId: string): Promise<boolean> {
    try {
      await this.client.send(
        new HeadObjectCommand({ Bucket: this.bucket, Key: `${reportPrefix(reportId)}.zip` }),
      );
      return true;
    } catch (error) {
      const status = (error as { $metadata?: { httpStatusCode?: number } }).$metadata?.httpStatusCode;
      if (status === 404) return false;
      throw error;
    }
  }

  async putReport(
    reportId: string,
    zipPath: string,
    screenshot: ScreenshotUpload | null,
    metadata: ReportMetadata,
  ): Promise<void> {
    const prefix = reportPrefix(reportId);
    const uploaded: string[] = [];
    await this.client.send(
      new PutObjectCommand({
        Bucket: this.bucket,
        Key: `${prefix}.zip`,
        Body: createReadStream(zipPath),
        ContentType: "application/zip",
      }),
    );
    uploaded.push(`${prefix}.zip`);
    try {
      if (screenshot) {
        await this.client.send(
          new PutObjectCommand({
            Bucket: this.bucket,
            Key: `${prefix}.screenshot`,
            Body: createReadStream(screenshot.path),
            ContentType: screenshot.contentType,
          }),
        );
        uploaded.push(`${prefix}.screenshot`);
      }
      await this.client.send(
        new PutObjectCommand({
          Bucket: this.bucket,
          Key: `${prefix}.json`,
          Body: JSON.stringify(metadata),
          ContentType: "application/json",
        }),
      );
    } catch (error) {
      await Promise.all(uploaded.map((key) =>
        this.client.send(new DeleteObjectCommand({ Bucket: this.bucket, Key: key })).catch(() => undefined),
      ));
      throw error;
    }
  }

  async listReports(limit: number): Promise<ReportMetadata[]> {
    const objects = await this.listAll("reports/");
    const keys = objects
      .map((object) => object.Key)
      .filter((key): key is string => Boolean(key?.endsWith(".json")))
      .sort()
      .reverse()
      .slice(0, limit);
    const reports = await Promise.all(
      keys.map(async (key) => {
        const response = await this.client.send(new GetObjectCommand({ Bucket: this.bucket, Key: key }));
        return JSON.parse((await bodyToBuffer(response.Body)).toString("utf8")) as ReportMetadata;
      }),
    );
    return reports.sort((left, right) => right.receivedUtc.localeCompare(left.receivedUtc));
  }

  async getReport(reportId: string): Promise<Buffer | null> {
    try {
      const response = await this.client.send(
        new GetObjectCommand({ Bucket: this.bucket, Key: `${reportPrefix(reportId)}.zip` }),
      );
      return bodyToBuffer(response.Body);
    } catch (error) {
      const status = (error as { $metadata?: { httpStatusCode?: number } }).$metadata?.httpStatusCode;
      if (status === 404) return null;
      throw error;
    }
  }

  async getScreenshot(reportId: string): Promise<StoredScreenshot | null> {
    try {
      const response = await this.client.send(
        new GetObjectCommand({ Bucket: this.bucket, Key: `${reportPrefix(reportId)}.screenshot` }),
      );
      return {
        bytes: await bodyToBuffer(response.Body),
        contentType: response.ContentType ?? "application/octet-stream",
      };
    } catch (error) {
      const status = (error as { $metadata?: { httpStatusCode?: number } }).$metadata?.httpStatusCode;
      if (status === 404) return null;
      throw error;
    }
  }

  async deleteReport(reportId: string): Promise<boolean> {
    const existed = await this.hasReport(reportId);
    const prefix = reportPrefix(reportId);
    await Promise.all([
      this.client.send(new DeleteObjectCommand({ Bucket: this.bucket, Key: `${prefix}.zip` })),
      this.client.send(new DeleteObjectCommand({ Bucket: this.bucket, Key: `${prefix}.json` })),
      this.client.send(new DeleteObjectCommand({ Bucket: this.bucket, Key: `${prefix}.screenshot` })),
    ]);
    return existed;
  }

  async dailyUsage(day: string): Promise<DailyUsage> {
    const objects = await this.listAll(dayPrefix(day));
    const reports = objects.filter((object) => object.Key?.endsWith(".zip"));
    return {
      reports: reports.length,
      bytes: objects
        .filter((object) => object.Key?.endsWith(".zip") || object.Key?.endsWith(".screenshot"))
        .reduce((total, object) => total + (object.Size ?? 0), 0),
    };
  }

  async cleanup(retentionBefore: Date, maxStoredBytes: number): Promise<CleanupResult> {
    const zipObjects = (await this.listAll("reports/"))
      .filter((object) => object.Key?.endsWith(".zip"))
      .sort((left, right) => (left.LastModified?.getTime() ?? 0) - (right.LastModified?.getTime() ?? 0));
    let retainedBytes = zipObjects.reduce((total, object) => total + (object.Size ?? 0), 0);
    let deletedReports = 0;
    for (const object of zipObjects) {
      const expired = (object.LastModified?.getTime() ?? 0) < retentionBefore.getTime();
      if (!expired && retainedBytes <= maxStoredBytes) break;
      if (!object.Key) continue;
      const reportId = object.Key.split("/").at(-1)?.replace(/\.zip$/, "");
      if (!reportId) continue;
      await this.deleteReport(reportId);
      retainedBytes -= object.Size ?? 0;
      deletedReports += 1;
    }
    return { deletedReports, retainedBytes };
  }

  private async listAll(prefix: string): Promise<_Object[]> {
    const objects: _Object[] = [];
    let continuationToken: string | undefined;
    do {
      const response = await this.client.send(
        new ListObjectsV2Command({
          Bucket: this.bucket,
          Prefix: prefix,
          ContinuationToken: continuationToken,
        }),
      );
      objects.push(...(response.Contents ?? []));
      continuationToken = response.IsTruncated ? response.NextContinuationToken : undefined;
    } while (continuationToken);
    return objects;
  }
}

/** Local-only storage for development and visual testing; production always uses the HF bucket. */
export class FileSystemReportStore implements ReportStore {
  constructor(private readonly root: string) {}

  async healthCheck(): Promise<void> {
    await mkdir(join(this.root, "reports"), { recursive: true });
  }

  async hasReport(reportId: string): Promise<boolean> {
    return stat(this.pathFor(reportId, "zip")).then(() => true, () => false);
  }

  async putReport(
    reportId: string,
    zipPath: string,
    screenshot: ScreenshotUpload | null,
    metadata: ReportMetadata,
  ): Promise<void> {
    const destination = this.pathFor(reportId, "zip");
    await mkdir(dirname(destination), { recursive: true });
    await copyFile(zipPath, destination);
    try {
      if (screenshot) await copyFile(screenshot.path, this.pathFor(reportId, "screenshot"));
      await writeFile(this.pathFor(reportId, "json"), JSON.stringify(metadata), "utf8");
    } catch (error) {
      await unlink(destination).catch(() => undefined);
      await unlink(this.pathFor(reportId, "screenshot")).catch(() => undefined);
      throw error;
    }
  }

  async listReports(limit: number): Promise<ReportMetadata[]> {
    await this.healthCheck();
    const entries = (await readdir(join(this.root, "reports"), { recursive: true })) as string[];
    const reports = await Promise.all(
      entries
        .filter((entry) => entry.endsWith(".json"))
        .map(async (entry) => JSON.parse(await readFile(join(this.root, "reports", entry), "utf8")) as ReportMetadata),
    );
    return reports
      .sort((left, right) => right.receivedUtc.localeCompare(left.receivedUtc))
      .slice(0, limit);
  }

  async getReport(reportId: string): Promise<Buffer | null> {
    return readFile(this.pathFor(reportId, "zip")).catch((error: NodeJS.ErrnoException) => {
      if (error.code === "ENOENT") return null;
      throw error;
    });
  }

  async getScreenshot(reportId: string): Promise<StoredScreenshot | null> {
    const metadata = (await this.listReports(Number.MAX_SAFE_INTEGER))
      .find((report) => report.reportId === reportId);
    if (!metadata?.screenshotContentType) return null;
    return readFile(this.pathFor(reportId, "screenshot"))
      .then((bytes) => ({ bytes, contentType: metadata.screenshotContentType! }))
      .catch((error: NodeJS.ErrnoException) => {
        if (error.code === "ENOENT") return null;
        throw error;
      });
  }

  async deleteReport(reportId: string): Promise<boolean> {
    const existed = await this.hasReport(reportId);
    await Promise.all([
      unlink(this.pathFor(reportId, "zip")).catch(() => undefined),
      unlink(this.pathFor(reportId, "json")).catch(() => undefined),
      unlink(this.pathFor(reportId, "screenshot")).catch(() => undefined),
    ]);
    return existed;
  }

  async dailyUsage(day: string): Promise<DailyUsage> {
    const reports = (await this.listReports(Number.MAX_SAFE_INTEGER)).filter((report) =>
      report.receivedUtc.startsWith(day),
    );
    return {
      reports: reports.length,
      bytes: reports.reduce(
        (total, report) => total + report.receivedBytes + (report.screenshotBytes ?? 0),
        0,
      ),
    };
  }

  async cleanup(retentionBefore: Date, maxStoredBytes: number): Promise<CleanupResult> {
    const reports = (await this.listReports(Number.MAX_SAFE_INTEGER)).sort((left, right) =>
      left.receivedUtc.localeCompare(right.receivedUtc),
    );
    let retainedBytes = reports.reduce(
      (total, report) => total + report.receivedBytes + (report.screenshotBytes ?? 0),
      0,
    );
    let deletedReports = 0;
    for (const report of reports) {
      const expired = new Date(report.receivedUtc) < retentionBefore;
      if (!expired && retainedBytes <= maxStoredBytes) break;
      await this.deleteReport(report.reportId);
      retainedBytes -= report.receivedBytes + (report.screenshotBytes ?? 0);
      deletedReports += 1;
    }
    return { deletedReports, retainedBytes };
  }

  private pathFor(reportId: string, extension: "zip" | "json" | "screenshot"): string {
    return join(this.root, `${reportPrefix(reportId)}.${extension}`);
  }
}

export function createStoreFromEnvironment(): ReportStore {
  if (process.env.DIAGNOSTICS_LOCAL_DATA_DIR) {
    if (process.env.NODE_ENV === "production") {
      throw new Error("DIAGNOSTICS_LOCAL_DATA_DIR is forbidden in production");
    }
    return new FileSystemReportStore(process.env.DIAGNOSTICS_LOCAL_DATA_DIR);
  }
  const namespace = process.env.HF_NAMESPACE ?? "anilili";
  const bucket = process.env.HF_BUCKET ?? "anilili-diagnostics";
  const accessKeyId = process.env.HF_S3_ACCESS_KEY_ID ?? "";
  const secretAccessKey = process.env.HF_S3_SECRET_ACCESS_KEY ?? "";
  if (!accessKeyId || !secretAccessKey) {
    throw new Error("HF_S3_ACCESS_KEY_ID and HF_S3_SECRET_ACCESS_KEY are required");
  }
  return new S3ReportStore({ namespace, bucket, accessKeyId, secretAccessKey });
}
