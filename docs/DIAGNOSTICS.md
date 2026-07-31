# Diagnostics and privacy

Anilili sends a diagnostic report only when a user chooses **Send diagnostics** or approves the
crash-report prompt. TV devices use this direct sender because file choosers, share targets and QR
workflows are unreliable from a remote.

Manual reports ask the user to describe the problem. A JPEG, PNG or WebP screenshot up to 5 MB can
also be attached explicitly; it is never captured automatically. Crash reports offer the same
optional context, while automatic slow-start and TV-shortcut reports remain usable without it.
After consent, the report, description and screenshot are staged together and WorkManager owns the
network transfer, so leaving the screen does not cancel or duplicate a slow upload.

Reports can include app/device configuration, Android version, memory and CPU samples, thermal and
frame-jank windows, playback/decoder/buffering statistics, resolver lifecycle, provider hosts and
HTTP timing/status data, WorkManager state, app logs, Java/Kotlin crash details, and Android native
exit traces when the OS exposes them.

Before a report is created, the app centrally redacts the written description plus passwords,
cookies, authorization headers,
access and refresh tokens, JWTs, email addresses, signed URL paths/query parameters, anime titles,
slugs and other sensitive content identifiers. The receiving server performs a second scan for
obvious unredacted credentials and redacts credential-like text in descriptions again. Screenshots
can contain whatever is visible in the selected image, so the UI warns users to attach only content
they intend to send. Raw IP addresses are not stored.

The server returns a reference such as `ANL-20260730-ABC123DE45`. Reports are stored in a private
Hugging Face Storage Bucket, are not available through a public listing or download endpoint, and
are deleted after 30 days. Descriptions and screenshot links are visible only in the authenticated
administrator console. The receiver also enforces a 20 GB storage ceiling, compressed and expanded
size limits, ZIP-safety validation, per-client throttling and daily quotas.
