import { FormEvent, useCallback, useEffect, useMemo, useState } from "react";

interface ReportMetadata {
  reportId: string;
  receivedUtc: string;
  receivedBytes: number;
  trigger: "manual" | "crash" | "slow_start" | "shortcut";
  appVersion: string;
  versionCode: string;
  buildSha: string;
  platform: string;
  expandedBytes: number;
  entryCount: number;
  manifestVersion: string;
}

interface ReportResponse {
  reports: ReportMetadata[];
  today: { reports: number; bytes: number };
  retentionDays: number;
}

function formatBytes(bytes: number): string {
  if (bytes < 1_000) return `${bytes} B`;
  if (bytes < 1_000_000) return `${(bytes / 1_000).toFixed(1)} KB`;
  return `${(bytes / 1_000_000).toFixed(1)} MB`;
}

function formatDate(value: string): string {
  return new Intl.DateTimeFormat(undefined, {
    dateStyle: "medium",
    timeStyle: "short",
  }).format(new Date(value));
}

function triggerLabel(trigger: ReportMetadata["trigger"]): string {
  return {
    manual: "Manual",
    crash: "Crash",
    slow_start: "Performance",
    shortcut: "TV shortcut",
  }[trigger];
}

async function jsonRequest<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, {
    credentials: "same-origin",
    ...init,
    headers: {
      ...(init?.body ? { "Content-Type": "application/json" } : {}),
      ...init?.headers,
    },
  });
  const body = (await response.json().catch(() => ({}))) as T & { error?: string };
  if (!response.ok) throw new Error(body.error ?? `Request failed (${response.status})`);
  return body;
}

function SignalIcon() {
  return (
    <svg viewBox="0 0 32 32" aria-hidden="true">
      <path d="M5 22.5h4V27H5zM12 16h4v11h-4zM19 9h4v18h-4zM26 3h2v24h-2z" />
    </svg>
  );
}

function Login({ onAuthenticated }: { onAuthenticated: () => void }) {
  const [accessKey, setAccessKey] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError("");
    try {
      await jsonRequest("/api/admin/login", {
        method: "POST",
        body: JSON.stringify({ accessKey }),
      });
      setAccessKey("");
      onAuthenticated();
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Sign-in failed");
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="login-shell">
      <section className="login-panel" aria-labelledby="login-title">
        <div className="console-mark">
          <span className="console-mark__icon"><SignalIcon /></span>
          <span>ANILILI / OPS</span>
        </div>
        <p className="eyebrow">Private diagnostic receiver</p>
        <h1 id="login-title">Flight recorder<br />console.</h1>
        <p className="login-copy">
          Review crash, playback, resolver and performance reports sent with explicit user consent.
        </p>
        <form onSubmit={submit} className="login-form">
          <label htmlFor="access-key">Administrator access key</label>
          <div className="key-row">
            <input
              id="access-key"
              type="password"
              autoComplete="current-password"
              value={accessKey}
              onChange={(event) => setAccessKey(event.target.value)}
              required
              minLength={24}
              autoFocus
            />
            <button type="submit" disabled={busy}>{busy ? "Checking…" : "Enter"}</button>
          </div>
          {error && <p className="form-error" role="alert">{error}</p>}
        </form>
        <div className="privacy-note">
          <span className="privacy-note__led" />
          Reports are private, redacted before upload, and automatically removed after 30 days.
        </div>
      </section>
      <aside className="login-rail" aria-hidden="true">
        <span>01</span><span>INGEST</span><span>VALIDATE</span><span>ARCHIVE</span><span>30D</span>
      </aside>
    </main>
  );
}

function Dashboard({ onSignedOut }: { onSignedOut: () => void }) {
  const [payload, setPayload] = useState<ReportResponse | null>(null);
  const [query, setQuery] = useState("");
  const [trigger, setTrigger] = useState("all");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(true);

  const refresh = useCallback(async () => {
    setBusy(true);
    setError("");
    try {
      setPayload(await jsonRequest<ReportResponse>("/api/admin/reports?limit=40"));
    } catch (requestError) {
      const message = requestError instanceof Error ? requestError.message : "Couldn't load reports";
      if (message.includes("authentication")) onSignedOut();
      else setError(message);
    } finally {
      setBusy(false);
    }
  }, [onSignedOut]);

  useEffect(() => { void refresh(); }, [refresh]);

  const reports = useMemo(() => {
    const needle = query.trim().toLowerCase();
    return (payload?.reports ?? []).filter((report) => {
      const matchesTrigger = trigger === "all" || report.trigger === trigger;
      const haystack = `${report.reportId} ${report.appVersion} ${report.versionCode} ${report.buildSha} ${report.platform}`.toLowerCase();
      return matchesTrigger && (!needle || haystack.includes(needle));
    });
  }, [payload, query, trigger]);

  const crashes = payload?.reports.filter((report) => report.trigger === "crash").length ?? 0;
  const totalBytes = payload?.reports.reduce((total, report) => total + report.receivedBytes, 0) ?? 0;

  async function remove(reportId: string) {
    if (!window.confirm(`Permanently delete ${reportId}? This cannot be undone.`)) return;
    try {
      await jsonRequest(`/api/admin/reports/${reportId}`, {
        method: "DELETE",
        headers: { "X-Anilili-Admin": "1" },
      });
      await refresh();
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Delete failed");
    }
  }

  async function signOut() {
    await jsonRequest("/api/admin/logout", {
      method: "POST",
      headers: { "X-Anilili-Admin": "1" },
    }).catch(() => undefined);
    onSignedOut();
  }

  return (
    <main className="dashboard-shell">
      <header className="topbar">
        <div className="console-mark">
          <span className="console-mark__icon"><SignalIcon /></span>
          <span>ANILILI / OPS</span>
        </div>
        <div className="topbar__status"><span /> Receiver online</div>
        <button className="text-button" onClick={signOut}>Sign out</button>
      </header>

      <section className="hero-strip">
        <div>
          <p className="eyebrow">Diagnostic archive</p>
          <h1>Reports,<br /><em>without guesswork.</em></h1>
        </div>
        <p className="hero-strip__copy">
          One consented report contains the app timeline, playback and resolver telemetry,
          performance samples, device profile and crash context—minus credentials.
        </p>
      </section>

      <section className="metrics" aria-label="Diagnostic summary">
        <article><span>Stored reports</span><strong>{payload?.reports.length ?? "—"}</strong><small>latest 40</small></article>
        <article><span>Crashes</span><strong>{crashes}</strong><small>action first</small></article>
        <article><span>Received today</span><strong>{payload?.today.reports ?? "—"}</strong><small>{formatBytes(payload?.today.bytes ?? 0)}</small></article>
        <article><span>Visible storage</span><strong>{formatBytes(totalBytes)}</strong><small>{payload?.retentionDays ?? 30}-day retention</small></article>
      </section>

      <section className="report-section" aria-labelledby="reports-heading">
        <div className="section-heading">
          <div><p className="eyebrow">Incoming queue</p><h2 id="reports-heading">Diagnostic reports</h2></div>
          <button className="refresh-button" onClick={() => void refresh()} disabled={busy}>
            <span className={busy ? "spin" : ""}>↻</span> Refresh
          </button>
        </div>

        <div className="filters">
          <label className="search-field">
            <span>Search</span>
            <input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Report ID, version, build…" />
          </label>
          <label className="select-field">
            <span>Signal</span>
            <select value={trigger} onChange={(event) => setTrigger(event.target.value)}>
              <option value="all">All reports</option>
              <option value="crash">Crash</option>
              <option value="slow_start">Performance</option>
              <option value="manual">Manual</option>
              <option value="shortcut">TV shortcut</option>
            </select>
          </label>
        </div>

        {error && <p className="banner-error" role="alert">{error}</p>}
        {!busy && reports.length === 0 && (
          <div className="empty-state"><span>NO SIGNAL</span><h3>No matching reports.</h3><p>New reports appear here after a user chooses Send diagnostics.</p></div>
        )}
        <div className="report-table" role="table" aria-label="Diagnostic reports">
          {reports.map((report) => (
            <article className="report-row" role="row" key={report.reportId}>
              <div className={`signal-tag signal-tag--${report.trigger}`}>{triggerLabel(report.trigger)}</div>
              <div className="report-id"><strong>{report.reportId}</strong><span>{formatDate(report.receivedUtc)}</span></div>
              <div className="report-fact"><span>Build</span><strong>{report.appVersion} ({report.versionCode})</strong></div>
              <div className="report-fact"><span>Archive</span><strong>{formatBytes(report.receivedBytes)} · {report.entryCount} files</strong></div>
              <div className="report-actions">
                <a href={`/api/admin/reports/${report.reportId}/download`}>Download</a>
                <button onClick={() => void remove(report.reportId)} aria-label={`Delete ${report.reportId}`}>Delete</button>
              </div>
            </article>
          ))}
        </div>
      </section>

      <footer><span>Private HF Bucket</span><span>Passwords · cookies · tokens excluded</span><span>Automatic retention</span></footer>
    </main>
  );
}

export default function App() {
  const [session, setSession] = useState<"checking" | "anonymous" | "authenticated">("checking");

  useEffect(() => {
    jsonRequest("/api/admin/session")
      .then(() => setSession("authenticated"))
      .catch(() => setSession("anonymous"));
  }, []);

  if (session === "checking") {
    return <div className="boot-screen"><SignalIcon /><span>Opening secure console…</span></div>;
  }
  if (session === "anonymous") {
    return <Login onAuthenticated={() => setSession("authenticated")} />;
  }
  return <Dashboard onSignedOut={() => setSession("anonymous")} />;
}
