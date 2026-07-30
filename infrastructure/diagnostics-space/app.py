from __future__ import annotations

import hashlib
import json
import os
import re
import tempfile
import threading
import time
import zipfile
from collections import defaultdict, deque
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath

import gradio as gr
import uvicorn
from fastapi import FastAPI, File, Form, HTTPException, Request, UploadFile
from fastapi.responses import JSONResponse


STARTED_MONOTONIC = time.monotonic()
MAX_COMPRESSED_BYTES = 25_000_000
MAX_EXPANDED_BYTES = 50_000_000
MAX_ZIP_ENTRIES = 64
RETENTION_SECONDS = 30 * 24 * 60 * 60
MAX_STORED_BYTES = 20_000_000_000
MAX_REPORTS_PER_IP_HOUR = 8
MAX_REPORTS_PER_DAY = 200
MAX_BYTES_PER_DAY = 2_000_000_000
REPORT_ID_PATTERN = re.compile(r"^ANL-[0-9]{8}-[A-Z0-9]{10}$")
SAFE_FIELD_PATTERN = re.compile(r"^[A-Za-z0-9._-]{1,80}$")
TEXT_ENTRIES = {"manifest.json", "summary.txt", "events.jsonl", "crash.txt", "workmanager.txt"}
SECRET_PATTERNS = (
    re.compile(rb'''(?i)["']?authorization["']?\s*[:=]\s*["']?bearer\s+(?!\[redacted\])[^"'\s,;]+'''),
    re.compile(
        rb'''(?i)["']?(?:password|passwd|cookie|set-cookie|access[_-]?token|refresh[_-]?token)["']?\s*[:=]\s*["']?(?!\[redacted\])[^"'\s,;]{6,}'''
    ),
    re.compile(rb"\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\b"),
)

DATA_ROOT = Path(os.environ.get("DIAGNOSTICS_DATA_DIR", "/data/reports"))
DATA_ROOT.mkdir(parents=True, exist_ok=True)

rate_lock = threading.Lock()
recent_by_client: dict[str, deque[float]] = defaultdict(deque)
daily_usage: dict[str, list[int]] = {}


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def client_key(request: Request) -> str:
    forwarded = request.headers.get("x-forwarded-for", "").split(",", 1)[0].strip()
    address = forwarded or (request.client.host if request.client else "unknown")
    # The raw address is needed only during this process for throttling and is never persisted.
    return hashlib.sha256(address.encode("utf-8", "replace")).hexdigest()


def enforce_rate_limits(request: Request, incoming_bytes: int) -> None:
    now = time.time()
    key = client_key(request)
    day = utc_now().strftime("%Y-%m-%d")
    with rate_lock:
        window = recent_by_client[key]
        while window and window[0] < now - 3600:
            window.popleft()
        if len(window) >= MAX_REPORTS_PER_IP_HOUR:
            raise HTTPException(429, "Too many reports from this device; try again later")
        count, byte_count = daily_usage.get(day, [0, 0])
        if count >= MAX_REPORTS_PER_DAY or byte_count + incoming_bytes > MAX_BYTES_PER_DAY:
            raise HTTPException(429, "Daily diagnostic capacity reached; the app will retry")
        window.append(now)
        daily_usage.clear()
        daily_usage[day] = [count + 1, byte_count + incoming_bytes]


def safe_zip_member(info: zipfile.ZipInfo) -> bool:
    path = PurePosixPath(info.filename)
    if path.is_absolute() or ".." in path.parts or "\\" in info.filename:
        return False
    # Unix symlink file type in the upper mode bits.
    return (info.external_attr >> 16) & 0o170000 != 0o120000


def validate_archive(path: Path) -> dict:
    try:
        with zipfile.ZipFile(path) as archive:
            entries = archive.infolist()
            if not entries or len(entries) > MAX_ZIP_ENTRIES:
                raise HTTPException(400, "Invalid diagnostic archive entry count")
            names = {entry.filename for entry in entries}
            if not {"manifest.json", "events.jsonl"}.issubset(names):
                raise HTTPException(400, "Diagnostic archive is missing required files")
            expanded = 0
            for entry in entries:
                if not safe_zip_member(entry) or entry.flag_bits & 0x1:
                    raise HTTPException(400, "Unsafe diagnostic archive")
                expanded += entry.file_size
                if expanded > MAX_EXPANDED_BYTES:
                    raise HTTPException(413, "Expanded diagnostic archive is too large")
                if entry.filename in TEXT_ENTRIES:
                    content = archive.read(entry)
                    if any(pattern.search(content) for pattern in SECRET_PATTERNS):
                        raise HTTPException(400, "Diagnostic archive contains an unredacted secret")
            manifest = json.loads(archive.read("manifest.json"))
            if not isinstance(manifest, dict):
                raise ValueError("manifest root is not an object")
            return {
                "expandedBytes": expanded,
                "entryCount": len(entries),
                "manifestVersion": str(manifest.get("appVersion", "unknown"))[:40],
            }
    except HTTPException:
        raise
    except (zipfile.BadZipFile, json.JSONDecodeError, KeyError, ValueError) as error:
        raise HTTPException(400, "Invalid diagnostic archive") from error


def cleanup_storage() -> None:
    now = time.time()
    reports = sorted(DATA_ROOT.glob("*.zip"), key=lambda item: item.stat().st_mtime)
    for report in list(reports):
        if report.stat().st_mtime < now - RETENTION_SECONDS:
            report.unlink(missing_ok=True)
            report.with_suffix(".json").unlink(missing_ok=True)
            reports.remove(report)
    total = sum(report.stat().st_size for report in reports)
    for report in reports:
        if total <= MAX_STORED_BYTES:
            break
        size = report.stat().st_size
        report.unlink(missing_ok=True)
        report.with_suffix(".json").unlink(missing_ok=True)
        total -= size


api = FastAPI(
    title="Anilili Diagnostics",
    docs_url=None,
    redoc_url=None,
    openapi_url=None,
)


@api.get("/health")
def health() -> JSONResponse:
    cleanup_storage()
    uptime = int(time.monotonic() - STARTED_MONOTONIC)
    return JSONResponse(
        {"status": "ok", "storage": "private", "retentionDays": 30},
        headers={
            "X-Anilili-Instance-Age": str(uptime),
            "X-Anilili-Cold-Start": str(uptime < 120).lower(),
        },
    )


@api.post("/v1/reports")
async def receive_report(
    request: Request,
    report_id: str = Form(...),
    trigger: str = Form(...),
    app_version: str = Form(...),
    version_code: str = Form(...),
    build_sha: str = Form(...),
    platform: str = Form(...),
    report: UploadFile = File(...),
) -> JSONResponse:
    validation_started = time.monotonic()
    if not REPORT_ID_PATTERN.fullmatch(report_id):
        raise HTTPException(400, "Invalid report reference")
    fields = (trigger, app_version, version_code, build_sha, platform)
    if not all(SAFE_FIELD_PATTERN.fullmatch(value) for value in fields):
        raise HTTPException(400, "Invalid report metadata")
    if report.content_type not in {"application/zip", "application/octet-stream"}:
        raise HTTPException(415, "Diagnostic report must be a ZIP archive")

    destination = DATA_ROOT / f"{report_id}.zip"
    if destination.exists():
        return JSONResponse(
            {"status": "accepted", "reportId": report_id, "receivedBytes": destination.stat().st_size},
        )

    with tempfile.NamedTemporaryFile(prefix="diagnostic-", suffix=".tmp", dir=DATA_ROOT, delete=False) as temp:
        temporary = Path(temp.name)
        received = 0
        try:
            while chunk := await report.read(1024 * 1024):
                received += len(chunk)
                if received > MAX_COMPRESSED_BYTES:
                    raise HTTPException(413, "Diagnostic report is too large")
                temp.write(chunk)
        except Exception:
            temporary.unlink(missing_ok=True)
            raise
        finally:
            await report.close()

    try:
        if received == 0:
            raise HTTPException(400, "Diagnostic report is empty")
        enforce_rate_limits(request, received)
        archive_details = validate_archive(temporary)
        validation_ms = (time.monotonic() - validation_started) * 1000
        store_started = time.monotonic()
        temporary.replace(destination)
        metadata = {
            "reportId": report_id,
            "receivedUtc": utc_now().isoformat(),
            "receivedBytes": received,
            "trigger": trigger,
            "appVersion": app_version,
            "versionCode": version_code,
            "buildSha": build_sha,
            "platform": platform,
            **archive_details,
        }
        destination.with_suffix(".json").write_text(
            json.dumps(metadata, indent=2, sort_keys=True),
            encoding="utf-8",
        )
        cleanup_storage()
        store_ms = (time.monotonic() - store_started) * 1000
    except Exception:
        temporary.unlink(missing_ok=True)
        destination.unlink(missing_ok=True)
        raise

    uptime = int(time.monotonic() - STARTED_MONOTONIC)
    return JSONResponse(
        {"status": "accepted", "reportId": report_id, "receivedBytes": received},
        headers={
            "Server-Timing": f"validate;dur={validation_ms:.1f}, store;dur={store_ms:.1f}",
            "X-Anilili-Instance-Age": str(uptime),
            "X-Anilili-Cold-Start": str(uptime < 120).lower(),
        },
    )


with gr.Blocks(title="Anilili Diagnostics") as landing:
    gr.Markdown(
        """
        # Anilili Diagnostics

        This service receives diagnostic reports only after an Anilili user chooses **Send
        diagnostics** or approves the crash prompt. Reports are stored privately for up to 30
        days. Passwords, cookies, tokens and sensitive links are removed by the app before upload.

        There is no public report browser or download endpoint.
        """
    )


app = gr.mount_gradio_app(api, landing, path="/")


if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=7860)
