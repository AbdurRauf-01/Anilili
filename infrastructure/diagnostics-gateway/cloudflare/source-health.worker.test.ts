import { describe, expect, it } from "vitest";
import { mergeSourceHealth, readZipEntry, summarizeSourceEvents } from "./source-health";

/** One line as the app writes it, with only the fields the rollup reads. */
function summaryLine(chosen: string, outcomes: string): string {
  return JSON.stringify({
    name: "resolve.summary",
    category: "source",
    attributes: { chosen, outcomes, episode: "1.0", totalMs: "1200" },
  });
}

describe("summarizeSourceEvents", () => {
  it("counts every attempted server, not just the one that played", () => {
    const events = [
      summaryLine("kiwi", "anikoto:timeout:8000,bonk:empty:412,kiwi:ok:1428"),
      summaryLine("kiwi", "kiwi:ok:900"),
      summaryLine("none", "anikoto:failed:120"),
    ].join("\n");

    const rollup = summarizeSourceEvents(events);
    expect(rollup).not.toBeNull();
    expect(rollup!.resolves).toBe(3);
    // The third resolve produced nothing playable.
    expect(rollup!.resolved).toBe(2);

    expect(rollup!.providers.kiwi).toMatchObject({ attempts: 2, ok: 2, chosen: 2 });
    expect(rollup!.providers.anikoto).toMatchObject({ attempts: 2, timeout: 1, failed: 1, ok: 0 });
    expect(rollup!.providers.bonk).toMatchObject({ attempts: 1, empty: 1, ok: 0 });
  });

  it("reports a median rather than letting one slow attempt set the tone", () => {
    const events = [
      summaryLine("bonk", "bonk:ok:100"),
      summaryLine("bonk", "bonk:ok:200"),
      summaryLine("bonk", "bonk:ok:9000"),
    ].join("\n");

    expect(summarizeSourceEvents(events)!.providers.bonk.medianMs).toBe(200);
  });

  it("returns nothing for a bundle with no playback in it", () => {
    expect(summarizeSourceEvents('{"name":"http.call.started"}\n')).toBeNull();
    expect(summarizeSourceEvents("")).toBeNull();
  });

  it("ignores malformed lines instead of failing the upload", () => {
    const events = [
      "not json at all but mentions resolve.summary",
      summaryLine("bonk", "bonk:ok:100"),
      JSON.stringify({ name: "resolve.summary" }),
      summaryLine("bonk", "garbage,::,bonk:nonsense:5"),
    ].join("\n");

    const rollup = summarizeSourceEvents(events);
    expect(rollup!.providers.bonk).toMatchObject({ attempts: 1, ok: 1 });
  });
});

describe("mergeSourceHealth", () => {
  it("adds counters across reports", () => {
    const first = summarizeSourceEvents(summaryLine("bonk", "bonk:ok:100"))!;
    const second = summarizeSourceEvents(
      [summaryLine("kiwi", "bonk:timeout:8000,kiwi:ok:300")].join("\n"),
    )!;

    const merged = mergeSourceHealth([first, second]);
    expect(merged.resolves).toBe(2);
    expect(merged.providers.bonk).toMatchObject({ attempts: 2, ok: 1, timeout: 1 });
    expect(merged.providers.kiwi).toMatchObject({ attempts: 1, ok: 1, chosen: 1 });
  });

  it("is empty for no input rather than throwing", () => {
    expect(mergeSourceHealth([])).toEqual({ resolves: 0, resolved: 0, providers: {} });
  });
});

describe("readZipEntry", () => {
  /** Minimal single-entry ZIP with a STORED (uncompressed) payload. */
  function storedZip(name: string, content: string): ArrayBuffer {
    const nameBytes = new TextEncoder().encode(name);
    const data = new TextEncoder().encode(content);
    const buffer = new ArrayBuffer(30 + nameBytes.length + data.length);
    const view = new DataView(buffer);
    view.setUint32(0, 0x04034b50, true);
    view.setUint16(8, 0, true); // stored
    view.setUint32(18, data.length, true);
    view.setUint32(22, data.length, true);
    view.setUint16(26, nameBytes.length, true);
    new Uint8Array(buffer).set(nameBytes, 30);
    new Uint8Array(buffer).set(data, 30 + nameBytes.length);
    return buffer;
  }

  it("reads the requested entry", async () => {
    const zip = storedZip("events.jsonl", '{"name":"resolve.summary"}');
    await expect(readZipEntry(zip, "events.jsonl")).resolves.toContain("resolve.summary");
  });

  it("returns null for an entry that is not there", async () => {
    await expect(readZipEntry(storedZip("manifest.json", "{}"), "events.jsonl")).resolves.toBeNull();
  });

  it("refuses an entry larger than the cap instead of allocating it", async () => {
    const zip = storedZip("events.jsonl", "x".repeat(2_000));
    await expect(readZipEntry(zip, "events.jsonl", 100)).resolves.toBeNull();
  });

  it("returns null on a truncated archive", async () => {
    const zip = storedZip("events.jsonl", "hello");
    await expect(readZipEntry(zip.slice(0, 20), "events.jsonl")).resolves.toBeNull();
  });
});
