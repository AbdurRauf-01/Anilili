import { MAX_EVENTS_SCANNED, MAX_SOURCE_EVENT_BYTES } from "./constants";
import type { SourceAttemptOutcome, SourceHealthRollup, SourceProviderStat } from "./types";

const LOCAL_HEADER_SIGNATURE = 0x04034b50;
const STORED = 0;
const DEFLATED = 8;
const OUTCOMES: readonly SourceAttemptOutcome[] = ["ok", "empty", "timeout", "failed"];

/**
 * Reads one entry out of an already-validated diagnostic ZIP.
 *
 * `inspectDiagnosticZip` walks the central directory and is the security gate; this runs after it
 * and re-reads the local header for a single named entry. It refuses anything it does not
 * understand rather than guessing, and caps the inflated size — a report that lies about its
 * contents should yield nothing, never an unbounded allocation.
 */
export async function readZipEntry(
  bytes: ArrayBuffer,
  wanted: string,
  maxBytes = MAX_SOURCE_EVENT_BYTES,
): Promise<string | null> {
  const view = new DataView(bytes);
  const decoder = new TextDecoder("utf-8", { fatal: false, ignoreBOM: false });
  let cursor = 0;

  while (cursor + 30 <= view.byteLength) {
    if (view.getUint32(cursor, true) !== LOCAL_HEADER_SIGNATURE) break;
    const flags = view.getUint16(cursor + 6, true);
    const method = view.getUint16(cursor + 8, true);
    const compressedSize = view.getUint32(cursor + 18, true);
    const uncompressedSize = view.getUint32(cursor + 22, true);
    const nameLength = view.getUint16(cursor + 26, true);
    const extraLength = view.getUint16(cursor + 28, true);
    const dataStart = cursor + 30 + nameLength + extraLength;
    if (dataStart + compressedSize > view.byteLength) return null;

    const name = decoder.decode(new Uint8Array(bytes, cursor + 30, nameLength));
    // Bit 3 puts the sizes in a trailing descriptor, so the header's are zero and the entry
    // cannot be located by arithmetic. The app's writer never does this.
    const streamed = (flags & 0x8) !== 0;
    if (name === wanted) {
      if (streamed || uncompressedSize > maxBytes) return null;
      const slice = bytes.slice(dataStart, dataStart + compressedSize);
      if (method === STORED) return decoder.decode(slice);
      if (method !== DEFLATED) return null;
      const stream = new Response(slice).body?.pipeThrough(new DecompressionStream("deflate-raw"));
      if (!stream) return null;
      const inflated = await new Response(stream).arrayBuffer();
      if (inflated.byteLength > maxBytes) return null;
      return decoder.decode(inflated);
    }
    if (streamed) return null;
    cursor = dataStart + compressedSize;
  }
  return null;
}

function emptyStat(): SourceProviderStat {
  return { attempts: 0, ok: 0, empty: 0, timeout: 0, failed: 0, chosen: 0, totalMs: 0, medianMs: 0 };
}

/**
 * Folds a bundle's `source/resolve.summary` events into per-provider counters.
 *
 * The app writes one of these per playback attempt, carrying every server it tried with the
 * outcome and duration of each ("kiwi:ok:1428,bonk:timeout:8000"). Rolling them up here means the
 * console can answer "which server actually works for our users, how fast, and when it doesn't,
 * why" without anyone downloading a single archive.
 */
export function summarizeSourceEvents(eventsJsonl: string): SourceHealthRollup | null {
  const providers: Record<string, SourceProviderStat> = {};
  const durations: Record<string, number[]> = {};
  let resolves = 0;
  let resolved = 0;
  let scanned = 0;

  for (const line of eventsJsonl.split("\n")) {
    if (scanned >= MAX_EVENTS_SCANNED) break;
    if (!line.includes("resolve.summary")) continue;
    scanned += 1;
    let event: Record<string, unknown>;
    try {
      event = JSON.parse(line) as Record<string, unknown>;
    } catch {
      continue;
    }
    if (event.name !== "resolve.summary") continue;
    const attributes = event.attributes as Record<string, string> | undefined;
    if (!attributes) continue;

    resolves += 1;
    const chosen = attributes.chosen;
    if (chosen && chosen !== "none") {
      resolved += 1;
      const stat = (providers[chosen] ??= emptyStat());
      stat.chosen += 1;
    }
    for (const entry of (attributes.outcomes ?? "").split(",")) {
      if (!entry) continue;
      const [provider, outcome, durationText] = entry.split(":");
      if (!provider || !OUTCOMES.includes(outcome as SourceAttemptOutcome)) continue;
      const stat = (providers[provider] ??= emptyStat());
      stat.attempts += 1;
      stat[outcome as SourceAttemptOutcome] += 1;
      const durationMs = Number(durationText);
      if (Number.isFinite(durationMs) && durationMs >= 0) {
        stat.totalMs += durationMs;
        (durations[provider] ??= []).push(durationMs);
      }
    }
  }

  if (resolves === 0) return null;
  for (const [provider, samples] of Object.entries(durations)) {
    samples.sort((left, right) => left - right);
    providers[provider].medianMs = Math.round(samples[Math.floor(samples.length / 2)] ?? 0);
  }
  return { resolves, resolved, providers };
}

/** Merges per-report rollups into one account-wide view. */
export function mergeSourceHealth(rollups: readonly SourceHealthRollup[]): SourceHealthRollup {
  const providers: Record<string, SourceProviderStat> = {};
  let resolves = 0;
  let resolved = 0;
  for (const rollup of rollups) {
    resolves += rollup.resolves;
    resolved += rollup.resolved;
    for (const [provider, stat] of Object.entries(rollup.providers)) {
      const target = (providers[provider] ??= emptyStat());
      target.attempts += stat.attempts;
      target.ok += stat.ok;
      target.empty += stat.empty;
      target.timeout += stat.timeout;
      target.failed += stat.failed;
      target.chosen += stat.chosen;
      target.totalMs += stat.totalMs;
      // Medians cannot be averaged honestly across reports; the mean of the attempt durations is
      // the defensible number once the samples themselves are gone.
      target.medianMs = target.attempts > 0 ? Math.round(target.totalMs / target.attempts) : 0;
    }
  }
  return { resolves, resolved, providers };
}
