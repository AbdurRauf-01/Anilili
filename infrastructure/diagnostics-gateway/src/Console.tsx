import { useCallback, useEffect, useMemo, useState } from "react";
import {
  ReportMetadata,
  ReportResponse,
  SourceHealthResponse,
  TRIGGER_LABEL,
  Trigger,
  formatAge,
  formatBytes,
  formatExactTime,
  jsonRequest,
} from "./api";
import {
  ReportStatus,
  STATUS_LABEL,
  TriageMap,
  entryFor,
  loadTriage,
  nextStatus,
  pruneTriage,
  saveTriage,
} from "./triage";
import { SignalIcon } from "./SignalIcon";

type SortKey = "newest" | "oldest" | "largest";

interface Facet {
  key: string;
  label: string;
  count: number;
}

/** Counts every value of one field across the set, so facet counts reflect reality not guesses. */
function facetsOf<T extends string>(
  reports: readonly ReportMetadata[],
  pick: (report: ReportMetadata) => T,
  label: (value: T) => string,
): Facet[] {
  const tally = new Map<T, number>();
  for (const report of reports) {
    const value = pick(report);
    tally.set(value, (tally.get(value) ?? 0) + 1);
  }
  return [...tally.entries()]
    .sort((left, right) => right[1] - left[1])
    .map(([value, count]) => ({ key: value, label: label(value), count }));
}

function FacetGroup({
  title,
  facets,
  active,
  total,
  onSelect,
}: {
  title: string;
  facets: Facet[];
  active: string;
  total: number;
  onSelect: (key: string) => void;
}) {
  if (facets.length === 0) return null;
  return (
    <div className="facet-group">
      <h3>{title}</h3>
      <button
        className={`facet ${active === "all" ? "facet--on" : ""}`}
        onClick={() => onSelect("all")}
      >
        <span className="facet__label">All</span>
        <span className="facet__count">{total}</span>
      </button>
      {facets.map((facet) => (
        <button
          key={facet.key}
          className={`facet ${active === facet.key ? "facet--on" : ""}`}
          onClick={() => onSelect(facet.key)}
        >
          <span className="facet__label">{facet.label}</span>
          <span className="facet__count">{facet.count}</span>
        </button>
      ))}
    </div>
  );
}

function SourceHealthPanel({ onClose }: { onClose: () => void }) {
  const [data, setData] = useState<SourceHealthResponse | null>(null);
  const [error, setError] = useState("");

  useEffect(() => {
    jsonRequest<SourceHealthResponse>("/api/admin/source-health")
      .then(setData)
      .catch((requestError: unknown) =>
        setError(requestError instanceof Error ? requestError.message : "Couldn't load"),
      );
  }, []);

  return (
    <section className="health" aria-label="Server health">
      <div className="health__head">
        <div>
          <p className="eyebrow">Aggregated across retained reports</p>
          <h2>Server health</h2>
        </div>
        <button className="text-button" onClick={onClose}>Close</button>
      </div>
      {error && <p className="banner-error" role="alert">{error}</p>}
      {!data && !error && <p className="muted-line">Reading reports…</p>}
      {data && data.providers.length === 0 && (
        <p className="muted-line">
          No playback telemetry yet. Reports uploaded from 0.1.56 onward carry it.
        </p>
      )}
      {data && data.providers.length > 0 && (
        <>
          <p className="muted-line">
            {data.resolves} resolves across {data.reportsWithPlayback} of {data.reports} reports ·{" "}
            {data.resolves > 0 ? Math.round((data.resolved / data.resolves) * 100) : 0}% found a stream
          </p>
          <table className="health-table">
            <thead>
              <tr>
                <th>Server</th><th>Tried</th><th>Worked</th><th>Median</th>
                <th>Empty</th><th>Timeout</th><th>Error</th><th>Played</th>
              </tr>
            </thead>
            <tbody>
              {data.providers.map((row) => {
                const rate = Math.round(row.successRate * 100);
                const tone = rate >= 80 ? "good" : rate >= 40 ? "warn" : "bad";
                return (
                  <tr key={row.provider}>
                    <td className="health-table__name">{row.provider}</td>
                    <td>{row.attempts}</td>
                    <td>
                      <span className={`rate rate--${tone}`}>{rate}%</span>
                    </td>
                    <td>{row.medianMs}ms</td>
                    <td>{row.empty || "—"}</td>
                    <td>{row.timeout || "—"}</td>
                    <td>{row.failed || "—"}</td>
                    <td>{row.chosen || "—"}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </>
      )}
    </section>
  );
}

function DetailPane({
  report,
  status,
  downloadedAt,
  onStatus,
  onDownload,
  onDelete,
  onClose,
}: {
  report: ReportMetadata;
  status: ReportStatus;
  downloadedAt?: number;
  onStatus: (status: ReportStatus) => void;
  onDownload: () => void;
  onDelete: () => void;
  onClose: () => void;
}) {
  const health = report.sourceHealth;
  const providers = health ? Object.entries(health.providers) : [];
  return (
    <aside className="detail" aria-label={`Report ${report.reportId}`}>
      <div className="detail__head">
        <div>
          <span className={`signal signal--${report.trigger}`}>{TRIGGER_LABEL[report.trigger]}</span>
          <h2>{report.reportId}</h2>
          <p className="muted-line">{formatExactTime(report.receivedUtc)}</p>
        </div>
        <button className="text-button" onClick={onClose} aria-label="Close detail">✕</button>
      </div>

      <div className="detail__status">
        {(["new", "investigating", "resolved", "ignored"] as ReportStatus[]).map((option) => (
          <button
            key={option}
            className={`chip chip--${option} ${status === option ? "chip--on" : ""}`}
            onClick={() => onStatus(option)}
          >
            {STATUS_LABEL[option]}
          </button>
        ))}
      </div>

      <dl className="detail__facts">
        <div><dt>Build</dt><dd>{report.appVersion} ({report.versionCode})</dd></div>
        <div><dt>Commit</dt><dd>{report.buildSha}</dd></div>
        <div><dt>Platform</dt><dd>{report.platform}</dd></div>
        <div><dt>Archive</dt><dd>{formatBytes(report.receivedBytes)} · {report.entryCount} files</dd></div>
        <div><dt>Expanded</dt><dd>{formatBytes(report.expandedBytes)}</dd></div>
        <div>
          <dt>Downloaded</dt>
          <dd>{downloadedAt ? formatExactTime(new Date(downloadedAt).toISOString()) : "Never"}</dd>
        </div>
      </dl>

      {report.description && (
        <div className="detail__block">
          <h3>What the user said</h3>
          <p className="detail__quote">{report.description}</p>
        </div>
      )}

      {report.screenshotBytes && (
        <div className="detail__block">
          <h3>Screenshot</h3>
          <a
            className="shot"
            href={`/api/admin/reports/${report.reportId}/screenshot`}
            target="_blank"
            rel="noreferrer"
          >
            <img src={`/api/admin/reports/${report.reportId}/screenshot`} alt="" loading="lazy" />
          </a>
          <small className="muted-line">
            {formatBytes(report.screenshotBytes)} · {report.screenshotContentType}
          </small>
        </div>
      )}

      {providers.length > 0 && health && (
        <div className="detail__block">
          <h3>Servers in this session</h3>
          <p className="muted-line">
            {health.resolved}/{health.resolves} resolves found a stream
          </p>
          <ul className="mini-health">
            {providers
              .sort((left, right) => right[1].attempts - left[1].attempts)
              .map(([name, stat]) => {
                const rate = stat.attempts > 0 ? Math.round((stat.ok / stat.attempts) * 100) : 0;
                const tone = rate >= 80 ? "good" : rate >= 40 ? "warn" : "bad";
                return (
                  <li key={name}>
                    <span>{name}</span>
                    <span className={`rate rate--${tone}`}>{rate}%</span>
                    <span className="muted-line">{stat.medianMs}ms · {stat.attempts} tried</span>
                  </li>
                );
              })}
          </ul>
        </div>
      )}

      <div className="detail__actions">
        <a className="button button--primary" href={`/api/admin/reports/${report.reportId}/download`} onClick={onDownload}>
          Download archive
        </a>
        <button className="button button--danger" onClick={onDelete}>Delete</button>
      </div>
    </aside>
  );
}

export function Console({ onSignedOut }: { onSignedOut: () => void }) {
  const [payload, setPayload] = useState<ReportResponse | null>(null);
  const [triage, setTriage] = useState<TriageMap>(() => loadTriage());
  const [query, setQuery] = useState("");
  const [signal, setSignal] = useState("all");
  const [statusFilter, setStatusFilter] = useState("all");
  const [version, setVersion] = useState("all");
  const [sort, setSort] = useState<SortKey>("newest");
  const [hideDownloaded, setHideDownloaded] = useState(false);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [checked, setChecked] = useState<Set<string>>(new Set());
  const [showHealth, setShowHealth] = useState(false);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(true);

  const refresh = useCallback(async () => {
    setBusy(true);
    setError("");
    try {
      const next = await jsonRequest<ReportResponse>("/api/admin/reports?limit=40");
      setPayload(next);
      setTriage((current) => {
        const pruned = pruneTriage(current, next.reports.map((report) => report.reportId));
        saveTriage(pruned);
        return pruned;
      });
    } catch (requestError) {
      const message = requestError instanceof Error ? requestError.message : "Couldn't load reports";
      if (message.includes("authentication")) onSignedOut();
      else setError(message);
    } finally {
      setBusy(false);
    }
  }, [onSignedOut]);

  useEffect(() => { void refresh(); }, [refresh]);

  const updateTriage = useCallback((reportId: string, patch: Partial<ReturnType<typeof entryFor>>) => {
    setTriage((current) => {
      const next = { ...current, [reportId]: { ...entryFor(current, reportId), ...patch } };
      saveTriage(next);
      return next;
    });
  }, []);

  const all = payload?.reports ?? [];

  const visible = useMemo(() => {
    const needle = query.trim().toLowerCase();
    const filtered = all.filter((report) => {
      const entry = entryFor(triage, report.reportId);
      if (signal !== "all" && report.trigger !== signal) return false;
      if (statusFilter !== "all" && entry.status !== statusFilter) return false;
      if (version !== "all" && report.appVersion !== version) return false;
      if (hideDownloaded && entry.downloadedAt) return false;
      if (!needle) return true;
      return `${report.reportId} ${report.appVersion} ${report.versionCode} ${report.buildSha} ` +
        `${report.platform} ${report.description ?? ""}`.toLowerCase().includes(needle);
    });
    return filtered.sort((left, right) => {
      if (sort === "largest") return right.receivedBytes - left.receivedBytes;
      const delta = new Date(right.receivedUtc).getTime() - new Date(left.receivedUtc).getTime();
      return sort === "oldest" ? -delta : delta;
    });
  }, [all, triage, query, signal, statusFilter, version, hideDownloaded, sort]);

  const selected = visible.find((report) => report.reportId === selectedId)
    ?? all.find((report) => report.reportId === selectedId)
    ?? null;

  const untouched = all.filter((report) => !entryFor(triage, report.reportId).downloadedAt).length;
  const crashes = all.filter((report) => report.trigger === "crash").length;

  async function remove(ids: string[]) {
    const label = ids.length === 1 ? ids[0] : `${ids.length} reports`;
    if (!window.confirm(`Permanently delete ${label}? This cannot be undone.`)) return;
    try {
      for (const id of ids) {
        await jsonRequest(`/api/admin/reports/${id}`, {
          method: "DELETE",
          headers: { "X-Anilili-Admin": "1" },
        });
      }
      setChecked(new Set());
      if (selectedId && ids.includes(selectedId)) setSelectedId(null);
      await refresh();
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Delete failed");
    }
  }

  function toggleChecked(reportId: string) {
    setChecked((current) => {
      const next = new Set(current);
      if (next.has(reportId)) next.delete(reportId); else next.add(reportId);
      return next;
    });
  }

  async function signOut() {
    await jsonRequest("/api/admin/logout", {
      method: "POST",
      headers: { "X-Anilili-Admin": "1" },
    }).catch(() => undefined);
    onSignedOut();
  }

  return (
    <div className="console">
      <header className="bar">
        <div className="console-mark">
          <span className="console-mark__icon"><SignalIcon /></span>
          <span>ANILILI / OPS</span>
        </div>
        <div className="bar__readouts">
          <span><b>{all.length}</b> stored</span>
          <span className={crashes ? "is-alert" : ""}><b>{crashes}</b> crashes</span>
          <span className={untouched ? "is-new" : ""}><b>{untouched}</b> unopened</span>
          <span><b>{payload?.today.reports ?? 0}</b> today</span>
          <span className="bar__retention">{payload?.retentionDays ?? 30}d retention</span>
        </div>
        <div className="bar__tools">
          <button className="text-button" onClick={() => setShowHealth((on) => !on)}>
            {showHealth ? "Reports" : "Server health"}
          </button>
          <button className="text-button" onClick={() => void refresh()} disabled={busy}>
            <span className={busy ? "spin" : ""}>↻</span> Refresh
          </button>
          <button className="text-button" onClick={signOut}>Sign out</button>
        </div>
      </header>

      {showHealth ? (
        <SourceHealthPanel onClose={() => setShowHealth(false)} />
      ) : (
        <div className="workspace">
          <nav className="rail" aria-label="Filters">
            <FacetGroup
              title="Signal"
              total={all.length}
              active={signal}
              onSelect={setSignal}
              facets={facetsOf(all, (report) => report.trigger, (value) => TRIGGER_LABEL[value as Trigger])}
            />
            <FacetGroup
              title="Status"
              total={all.length}
              active={statusFilter}
              onSelect={setStatusFilter}
              facets={facetsOf(
                all,
                (report) => entryFor(triage, report.reportId).status,
                (value) => STATUS_LABEL[value as ReportStatus],
              )}
            />
            <FacetGroup
              title="Version"
              total={all.length}
              active={version}
              onSelect={setVersion}
              facets={facetsOf(all, (report) => report.appVersion, (value) => value)}
            />
            <label className="toggle">
              <input
                type="checkbox"
                checked={hideDownloaded}
                onChange={(event) => setHideDownloaded(event.target.checked)}
              />
              <span>Hide opened</span>
            </label>
          </nav>

          <section className="list" aria-label="Diagnostic reports">
            <div className="list__tools">
              <input
                className="search"
                value={query}
                onChange={(event) => setQuery(event.target.value)}
                placeholder="Search id, version, build, user text…"
                aria-label="Search reports"
              />
              <select
                className="sort"
                value={sort}
                onChange={(event) => setSort(event.target.value as SortKey)}
                aria-label="Sort"
              >
                <option value="newest">Newest</option>
                <option value="oldest">Oldest</option>
                <option value="largest">Largest</option>
              </select>
              <span className="list__count">{visible.length} shown</span>
            </div>

            {checked.size > 0 && (
              <div className="bulk" role="toolbar" aria-label="Bulk actions">
                <span><b>{checked.size}</b> selected</span>
                <button onClick={() => {
                  checked.forEach((id) => updateTriage(id, { status: "resolved" }));
                  setChecked(new Set());
                }}>Mark resolved</button>
                <button onClick={() => {
                  checked.forEach((id) => updateTriage(id, { status: "ignored" }));
                  setChecked(new Set());
                }}>Ignore</button>
                <button className="is-danger" onClick={() => void remove([...checked])}>Delete</button>
                <button onClick={() => setChecked(new Set())}>Clear</button>
              </div>
            )}

            {error && <p className="banner-error" role="alert">{error}</p>}
            {!busy && visible.length === 0 && (
              <div className="empty">
                <span>NO SIGNAL</span>
                <p>Nothing matches these filters.</p>
              </div>
            )}

            <ul className="rows">
              {visible.map((report) => {
                const entry = entryFor(triage, report.reportId);
                const opened = Boolean(entry.downloadedAt);
                return (
                  <li
                    key={report.reportId}
                    className={[
                      "row",
                      `row--${entry.status}`,
                      opened ? "row--opened" : "row--fresh",
                      selectedId === report.reportId ? "row--active" : "",
                    ].join(" ")}
                  >
                    <input
                      type="checkbox"
                      className="row__check"
                      checked={checked.has(report.reportId)}
                      onChange={() => toggleChecked(report.reportId)}
                      aria-label={`Select ${report.reportId}`}
                    />
                    <button className="row__main" onClick={() => setSelectedId(report.reportId)}>
                      <span className={`dot dot--${report.trigger}`} aria-hidden="true" />
                      <span className="row__id">{report.reportId.replace("ANL-", "")}</span>
                      <span className={`signal signal--${report.trigger}`}>
                        {TRIGGER_LABEL[report.trigger]}
                      </span>
                      <span className="row__ver">{report.appVersion}</span>
                      <span className="row__age" title={formatExactTime(report.receivedUtc)}>
                        {formatAge(report.receivedUtc)}
                      </span>
                      <span className="row__size">{formatBytes(report.receivedBytes)}</span>
                      <span className="row__marks">
                        {report.description && <i title="User description">✎</i>}
                        {report.screenshotBytes && <i title="Screenshot">◲</i>}
                        {entry.status !== "new" && (
                          <i className={`tick tick--${entry.status}`}>{STATUS_LABEL[entry.status]}</i>
                        )}
                      </span>
                    </button>
                    <a
                      className="row__dl"
                      href={`/api/admin/reports/${report.reportId}/download`}
                      onClick={() => updateTriage(report.reportId, {
                        downloadedAt: entry.downloadedAt ?? Date.now(),
                      })}
                      title={opened ? "Downloaded already" : "Download archive"}
                    >
                      {opened ? "↓ again" : "↓"}
                    </a>
                    <button
                      className="row__cycle"
                      onClick={() => updateTriage(report.reportId, { status: nextStatus(entry.status) })}
                      title={`Status: ${STATUS_LABEL[entry.status]} — click to advance`}
                    >
                      {STATUS_LABEL[entry.status][0]}
                    </button>
                  </li>
                );
              })}
            </ul>
          </section>

          {selected && (
            <DetailPane
              report={selected}
              status={entryFor(triage, selected.reportId).status}
              downloadedAt={entryFor(triage, selected.reportId).downloadedAt}
              onStatus={(status) => updateTriage(selected.reportId, { status })}
              onDownload={() => updateTriage(selected.reportId, {
                downloadedAt: entryFor(triage, selected.reportId).downloadedAt ?? Date.now(),
              })}
              onDelete={() => void remove([selected.reportId])}
              onClose={() => setSelectedId(null)}
            />
          )}
        </div>
      )}
    </div>
  );
}
