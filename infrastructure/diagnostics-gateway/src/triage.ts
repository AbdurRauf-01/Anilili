/**
 * Per-report triage state.
 *
 * Kept in localStorage rather than on the server: it is a maintainer's private working set
 * ("have I looked at this one yet"), not something the reporting device knows or should carry,
 * and keeping it client-side means no new authenticated write path into the archive. The trade
 * is that it does not follow you between browsers — acceptable for a single-maintainer console,
 * and the export/import below exists so it can be moved deliberately when that matters.
 */

export type ReportStatus = "new" | "investigating" | "resolved" | "ignored";

export interface TriageEntry {
  status: ReportStatus;
  /** Epoch ms of the first download, or undefined if never fetched. */
  downloadedAt?: number;
  note?: string;
}

export type TriageMap = Record<string, TriageEntry>;

const STORAGE_KEY = "anilili.triage.v1";

export const STATUS_LABEL: Record<ReportStatus, string> = {
  new: "New",
  investigating: "Investigating",
  resolved: "Resolved",
  ignored: "Ignored",
};

/** The order the status control cycles through when clicked. */
export const STATUS_ORDER: ReportStatus[] = ["new", "investigating", "resolved", "ignored"];

export function loadTriage(): TriageMap {
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    if (!raw) return {};
    const parsed = JSON.parse(raw) as unknown;
    if (!parsed || typeof parsed !== "object") return {};
    return parsed as TriageMap;
  } catch {
    // A corrupt or unavailable store must not take the console down; triage is an overlay.
    return {};
  }
}

export function saveTriage(map: TriageMap): void {
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(map));
  } catch {
    /* private browsing or quota — the console still works, it just forgets. */
  }
}

export function entryFor(map: TriageMap, reportId: string): TriageEntry {
  return map[reportId] ?? { status: "new" };
}

export function nextStatus(current: ReportStatus): ReportStatus {
  return STATUS_ORDER[(STATUS_ORDER.indexOf(current) + 1) % STATUS_ORDER.length];
}

/**
 * Drops entries for reports the archive no longer holds.
 *
 * Reports age out after 30 days; without this the map would grow forever and keep resurrecting
 * state for ids that will never be seen again.
 */
export function pruneTriage(map: TriageMap, knownIds: readonly string[]): TriageMap {
  const known = new Set(knownIds);
  const pruned: TriageMap = {};
  for (const [id, entry] of Object.entries(map)) {
    if (known.has(id)) pruned[id] = entry;
  }
  return pruned;
}
