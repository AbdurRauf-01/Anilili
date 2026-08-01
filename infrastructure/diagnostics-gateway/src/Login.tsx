import { FormEvent, useState } from "react";
import { jsonRequest } from "./api";
import { SignalIcon } from "./SignalIcon";

export function Login({ onAuthenticated }: { onAuthenticated: () => void }) {
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
          Crash, playback, resolver and performance reports, sent with explicit user consent.
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
          Reports are redacted before upload and removed automatically after 30 days.
        </div>
      </section>
      <aside className="login-rail" aria-hidden="true">
        <span>01</span><span>INGEST</span><span>VALIDATE</span><span>ARCHIVE</span><span>30D</span>
      </aside>
    </main>
  );
}
