---
title: Anilili Diagnostics
emoji: 🛠️
colorFrom: purple
colorTo: indigo
sdk: gradio
app_file: app.py
pinned: false
license: mit
---

# Anilili Diagnostics Receiver

CPU-only receiver for user-approved Anilili diagnostic reports. The public service exposes only
health information and the upload endpoint. Reports are stored in a private Hugging Face Storage
Bucket mounted at `/data`; there is no public report index or download route.

The Android app centrally redacts passwords, cookies, authorization headers, access/refresh
tokens, signed URL details, email addresses and sensitive content identifiers before upload. The
receiver validates ZIP structure and performs a second obvious-secret scan. Reports are retained
for 30 days, with a 20 GB hard cap and daily upload limits.

This Space does not request a GPU. When hosted as a free ZeroGPU Gradio Space, the upload service
uses only the Space's ordinary CPU process and does not call `@spaces.GPU`.
