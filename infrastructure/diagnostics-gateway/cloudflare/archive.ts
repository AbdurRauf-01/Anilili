import { MAX_EXPANDED_BYTES, MAX_ZIP_ENTRIES } from "./constants";
import { HttpError } from "./http";
import type { ArchiveDetails } from "./types";

const CENTRAL_DIRECTORY_SIGNATURE = 0x02014b50;
const END_OF_CENTRAL_DIRECTORY_SIGNATURE = 0x06054b50;
const END_OF_CENTRAL_DIRECTORY_BYTES = 22;
const MAX_ZIP_COMMENT_BYTES = 65_535;
const REQUIRED_ENTRIES = new Set(["manifest.json", "events.jsonl"]);

function unsafeName(name: string): boolean {
  const parts = name.split("/");
  return name.startsWith("/") || name.includes("\\") || parts.includes("..");
}

function findEndOfCentralDirectory(view: DataView): number {
  const minimum = Math.max(0, view.byteLength - END_OF_CENTRAL_DIRECTORY_BYTES - MAX_ZIP_COMMENT_BYTES);
  for (let offset = view.byteLength - END_OF_CENTRAL_DIRECTORY_BYTES; offset >= minimum; offset -= 1) {
    if (view.getUint32(offset, true) === END_OF_CENTRAL_DIRECTORY_SIGNATURE) return offset;
  }
  throw new HttpError(400, "Invalid diagnostic ZIP archive");
}

export function inspectDiagnosticZip(bytes: ArrayBuffer, appVersion: string): ArchiveDetails {
  if (bytes.byteLength < END_OF_CENTRAL_DIRECTORY_BYTES) {
    throw new HttpError(400, "Invalid diagnostic ZIP archive");
  }
  const view = new DataView(bytes);
  const eocd = findEndOfCentralDirectory(view);
  const diskNumber = view.getUint16(eocd + 4, true);
  const centralDisk = view.getUint16(eocd + 6, true);
  const diskEntries = view.getUint16(eocd + 8, true);
  const entryCount = view.getUint16(eocd + 10, true);
  const centralSize = view.getUint32(eocd + 12, true);
  const centralOffset = view.getUint32(eocd + 16, true);
  const commentLength = view.getUint16(eocd + 20, true);

  if (
    diskNumber !== 0 ||
    centralDisk !== 0 ||
    diskEntries !== entryCount ||
    entryCount === 0 ||
    entryCount > MAX_ZIP_ENTRIES ||
    eocd + END_OF_CENTRAL_DIRECTORY_BYTES + commentLength !== view.byteLength ||
    centralOffset + centralSize > eocd
  ) {
    throw new HttpError(400, "Invalid diagnostic ZIP structure");
  }

  const decoder = new TextDecoder("utf-8", { fatal: true, ignoreBOM: false });
  const names = new Set<string>();
  let expandedBytes = 0;
  let cursor = centralOffset;
  const centralEnd = centralOffset + centralSize;

  for (let index = 0; index < entryCount; index += 1) {
    if (cursor + 46 > centralEnd || view.getUint32(cursor, true) !== CENTRAL_DIRECTORY_SIGNATURE) {
      throw new HttpError(400, "Invalid diagnostic ZIP directory");
    }
    const flags = view.getUint16(cursor + 8, true);
    const uncompressedSize = view.getUint32(cursor + 24, true);
    const nameLength = view.getUint16(cursor + 28, true);
    const extraLength = view.getUint16(cursor + 30, true);
    const entryCommentLength = view.getUint16(cursor + 32, true);
    const externalAttributes = view.getUint32(cursor + 38, true);
    const recordLength = 46 + nameLength + extraLength + entryCommentLength;
    if (nameLength === 0 || cursor + recordLength > centralEnd || (flags & 0x1) !== 0) {
      throw new HttpError(400, "Unsafe diagnostic ZIP archive");
    }

    let name: string;
    try {
      name = decoder.decode(new Uint8Array(bytes, cursor + 46, nameLength));
    } catch {
      throw new HttpError(400, "Invalid diagnostic ZIP filename");
    }
    const unixFileType = (externalAttributes >>> 16) & 0o170000;
    if (unsafeName(name) || unixFileType === 0o120000 || names.has(name)) {
      throw new HttpError(400, "Unsafe diagnostic ZIP archive");
    }
    names.add(name);
    expandedBytes += uncompressedSize;
    if (expandedBytes > MAX_EXPANDED_BYTES) {
      throw new HttpError(400, "Expanded diagnostic ZIP is too large");
    }
    cursor += recordLength;
  }

  if (cursor !== centralEnd || [...REQUIRED_ENTRIES].some((name) => !names.has(name))) {
    throw new HttpError(400, "Diagnostic ZIP is missing required files");
  }

  return {
    expandedBytes,
    entryCount,
    manifestVersion: appVersion.slice(0, 40),
    validation: "zip-central-directory",
  };
}
