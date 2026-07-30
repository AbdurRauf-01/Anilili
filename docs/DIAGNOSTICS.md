# Diagnostics and privacy

Anilili sends a diagnostic report only when a user chooses **Send diagnostics** or approves the
crash-report prompt. TV devices use this direct sender because file choosers, share targets and QR
workflows are unreliable from a remote.

Reports can include app/device configuration, Android version, memory and CPU samples, thermal and
frame-jank windows, playback/decoder/buffering statistics, resolver lifecycle, provider hosts and
HTTP timing/status data, WorkManager state, app logs, Java/Kotlin crash details, and Android native
exit traces when the OS exposes them.

Before a report is created, the app centrally redacts passwords, cookies, authorization headers,
access and refresh tokens, JWTs, email addresses, signed URL paths/query parameters, anime titles,
slugs and other sensitive content identifiers. The receiving server performs a second scan for
obvious unredacted credentials. Raw IP addresses are not stored.

The server returns a reference such as `ANL-20260730-ABC123DE45`. Reports are stored in a private
Hugging Face Storage Bucket, are not available through a public listing or download endpoint, and
are deleted after 30 days. The receiver also enforces a 20 GB storage ceiling, compressed and
expanded size limits, ZIP-safety validation, per-client throttling and daily quotas.
