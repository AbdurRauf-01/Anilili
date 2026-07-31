import { strToU8, zipSync } from "fflate";
import { describe, expect, it } from "vitest";
import { inspectDiagnosticZip } from "./archive";

function validArchive(entries: Record<string, Uint8Array> = {}): Uint8Array {
  return zipSync({
    "manifest.json": strToU8('{"appVersion":"0.1.51"}'),
    "events.jsonl": strToU8('{"name":"test"}\n'),
    ...entries,
  });
}

function centralDirectoryOffset(bytes: Uint8Array): number {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  for (let offset = bytes.byteLength - 22; offset >= 0; offset -= 1) {
    if (view.getUint32(offset, true) === 0x06054b50) return view.getUint32(offset + 16, true);
  }
  throw new Error("missing EOCD");
}

describe("diagnostic ZIP inspection", () => {
  it("accepts the required redacted report structure without inflating entries", () => {
    const archive = validArchive({ "summary.txt": strToU8("Resolver timing summary") });
    const result = inspectDiagnosticZip(archive.buffer as ArrayBuffer, "0.1.51");

    expect(result.entryCount).toBe(3);
    expect(result.expandedBytes).toBeGreaterThan(0);
    expect(result.validation).toBe("zip-central-directory");
  });

  it("rejects archives missing required entries", () => {
    const archive = zipSync({ "manifest.json": strToU8("{}") });
    expect(() => inspectDiagnosticZip(archive.buffer as ArrayBuffer, "0.1.51")).toThrow(
      "missing required files",
    );
  });

  it("rejects encrypted entries", () => {
    const archive = validArchive();
    const view = new DataView(archive.buffer, archive.byteOffset, archive.byteLength);
    const central = centralDirectoryOffset(archive);
    view.setUint16(central + 8, view.getUint16(central + 8, true) | 0x1, true);
    expect(() => inspectDiagnosticZip(archive.buffer as ArrayBuffer, "0.1.51")).toThrow(
      "Unsafe diagnostic ZIP",
    );
  });

  it("rejects a declared decompression bomb", () => {
    const archive = validArchive();
    const view = new DataView(archive.buffer, archive.byteOffset, archive.byteLength);
    const central = centralDirectoryOffset(archive);
    view.setUint32(central + 24, 50_000_001, true);
    expect(() => inspectDiagnosticZip(archive.buffer as ArrayBuffer, "0.1.51")).toThrow(
      "Expanded diagnostic ZIP is too large",
    );
  });
});
