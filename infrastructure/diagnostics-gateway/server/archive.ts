import type { Readable } from "node:stream";
import yauzl, { type Entry, type ZipFile } from "yauzl";
import {
  MAX_EXPANDED_BYTES,
  MAX_ZIP_ENTRIES,
} from "./config.js";
import type { ArchiveDetails } from "./types.js";

const REQUIRED_ENTRIES = new Set(["manifest.json", "events.jsonl"]);
const TEXT_ENTRIES = new Set([
  "manifest.json",
  "summary.txt",
  "events.jsonl",
  "crash.txt",
  "workmanager.txt",
]);
const SECRET_PATTERNS = [
  /["']?authorization["']?\s*[:=]\s*["']?bearer\s+(?!\[redacted\])[^"'\s,;]+/i,
  /["']?(?:password|passwd|cookie|set-cookie|access[_-]?token|refresh[_-]?token)["']?\s*[:=]\s*["']?(?!\[redacted\])[^"'\s,;]{6,}/i,
  /(?:[?&]|["'])(?:signature|accesskeyid|x-amz-signature|x-amz-credential|token)=((?!\[redacted\])[^&"'\s]{6,})/i,
  /\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\b/,
];

export class ArchiveValidationError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "ArchiveValidationError";
  }
}

function openZip(path: string): Promise<ZipFile> {
  return new Promise((resolve, reject) => {
    yauzl.open(path, { lazyEntries: true, autoClose: true }, (error, zip) => {
      if (error || !zip) reject(new ArchiveValidationError("Invalid diagnostic archive"));
      else resolve(zip);
    });
  });
}

function isUnsafeEntry(entry: Entry): boolean {
  const parts = entry.fileName.split("/");
  const mode = (entry.externalFileAttributes >>> 16) & 0o170000;
  return (
    entry.fileName.startsWith("/") ||
    entry.fileName.includes("\\") ||
    parts.includes("..") ||
    mode === 0o120000 ||
    (entry.generalPurposeBitFlag & 0x1) !== 0
  );
}

function readEntry(zip: ZipFile, entry: Entry): Promise<Buffer> {
  return new Promise((resolve, reject) => {
    zip.openReadStream(entry, (error, stream) => {
      if (error || !stream) {
        reject(new ArchiveValidationError("Invalid diagnostic archive entry"));
        return;
      }
      const chunks: Buffer[] = [];
      let bytes = 0;
      (stream as Readable).on("data", (chunk: Buffer) => {
        bytes += chunk.length;
        if (bytes > MAX_EXPANDED_BYTES) {
          stream.destroy(new ArchiveValidationError("Diagnostic archive entry is too large"));
          return;
        }
        chunks.push(Buffer.from(chunk));
      });
      stream.once("error", reject);
      stream.once("end", () => resolve(Buffer.concat(chunks)));
    });
  });
}

export async function validateArchive(path: string): Promise<ArchiveDetails> {
  const zip = await openZip(path);
  const names = new Set<string>();
  let expandedBytes = 0;
  let entryCount = 0;
  let manifest: Record<string, unknown> | null = null;

  return new Promise<ArchiveDetails>((resolve, reject) => {
    const fail = (error: unknown) => {
      zip.close();
      reject(
        error instanceof ArchiveValidationError
          ? error
          : new ArchiveValidationError("Invalid diagnostic archive"),
      );
    };

    zip.once("error", fail);
    zip.on("entry", async (entry: Entry) => {
      try {
        entryCount += 1;
        if (entryCount > MAX_ZIP_ENTRIES) {
          throw new ArchiveValidationError("Invalid diagnostic archive entry count");
        }
        if (isUnsafeEntry(entry)) {
          throw new ArchiveValidationError("Unsafe diagnostic archive");
        }
        names.add(entry.fileName);
        expandedBytes += entry.uncompressedSize;
        if (expandedBytes > MAX_EXPANDED_BYTES) {
          throw new ArchiveValidationError("Expanded diagnostic archive is too large");
        }
        if (TEXT_ENTRIES.has(entry.fileName)) {
          const content = await readEntry(zip, entry);
          const text = content.toString("utf8");
          if (SECRET_PATTERNS.some((pattern) => pattern.test(text))) {
            throw new ArchiveValidationError("Diagnostic archive contains an unredacted secret");
          }
          if (entry.fileName === "manifest.json") {
            const decoded = JSON.parse(text) as unknown;
            if (!decoded || typeof decoded !== "object" || Array.isArray(decoded)) {
              throw new ArchiveValidationError("Diagnostic manifest is invalid");
            }
            manifest = decoded as Record<string, unknown>;
          }
        }
        zip.readEntry();
      } catch (error) {
        fail(error);
      }
    });
    zip.once("end", () => {
      if (entryCount === 0 || [...REQUIRED_ENTRIES].some((name) => !names.has(name)) || !manifest) {
        fail(new ArchiveValidationError("Diagnostic archive is missing required files"));
        return;
      }
      resolve({
        expandedBytes,
        entryCount,
        manifestVersion: String(manifest.appVersion ?? "unknown").slice(0, 40),
      });
    });
    zip.readEntry();
  });
}
