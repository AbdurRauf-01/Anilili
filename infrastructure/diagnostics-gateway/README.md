# Anilili Diagnostic Console

Cloudflare Workers receiver and administrator dashboard for user-approved Anilili diagnostic
reports. Android and TV send the multipart request covered by `DiagnosticUploadClientTest`; the
Worker validates and stores reports in the private `anilili/anilili-diagnostics` Hugging Face
Storage Bucket.

The deployment is designed for Cloudflare Workers Free. Static dashboard assets are served by the
Worker, KV holds rate-limit counters and the small report index, and Hugging Face holds the ZIP
files. No Google service is involved.

## Security model

- Hugging Face S3 credentials and administrator secrets exist only in Cloudflare encrypted secrets.
- The public surface is limited to `GET /health` and `POST /v1/reports`.
- Report browsing, downloading, and deletion require an HttpOnly administrator session.
- Report IDs and metadata are validated. ZIP central-directory validation rejects traversal,
  symlinks, encryption, duplicate names, excess entries, and excessive declared expansion.
- The Android exporter performs the content redaction before upload; the Worker never logs report
  contents, client addresses, credentials, cookies, or streaming URLs.
- Reports expire after 30 days; hourly cleanup prevents high-volume days from creating a retention
  backlog. Daily upload count and byte caps protect both free services.

## API contract

`POST /v1/reports` accepts one `multipart/form-data` request with:

- `report_id`
- `trigger`
- `app_version`
- `version_code`
- `build_sha`
- `platform`
- `report` (`application/zip`, maximum 25MB)

Successful response:

```json
{
  "status": "accepted",
  "reportId": "ANL-20260730-ABC123DE45",
  "receivedBytes": 427
}
```

Repeated report IDs are idempotent. HTTP `408`, `425`, `429`, and `5xx` remain retryable by the
Android WorkManager queue.

## Local verification

```powershell
npm install
npm run worker:types
npm test
npm run typecheck
npm run deploy:dry-run
npx wrangler check startup
```

For local Worker development, create an ignored `.dev.vars` file with test-only values for the four
required secrets and run `npm run dev`. The Node receiver in `server/` remains only as a local
contract reference and can be started with `npm run dev:node`.

Never add real values to `.dev.vars`, `.env`, Git, Android resources, Gradle properties, or
BuildConfig. See [DEPLOYMENT.md](DEPLOYMENT.md) for the production rollout and rollback gates.
