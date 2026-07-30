from __future__ import annotations

import argparse
import collections
import json
import statistics
import zipfile
from pathlib import Path


def number(value, default=0.0):
    try:
        return float(value)
    except (TypeError, ValueError):
        return default


def percentile(values: list[float], fraction: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, int((len(ordered) - 1) * fraction))]


def analyze(path: Path) -> dict:
    with zipfile.ZipFile(path) as archive:
        manifest = json.loads(archive.read("manifest.json"))
        events = [
            json.loads(line)
            for line in archive.read("events.jsonl").decode("utf-8", "replace").splitlines()
            if line.strip()
        ]
        names = set(archive.namelist())

    event_names = collections.Counter(event.get("name", "unknown") for event in events)
    categories = collections.Counter(event.get("category", "unknown") for event in events)
    http_events = [event for event in events if event.get("name") in {"http.call.complete", "http.call.failed"}]
    http_totals = [number(event.get("attributes", {}).get("totalMs")) for event in http_events]
    http_hosts = collections.Counter(event.get("attributes", {}).get("host", "unknown") for event in http_events)
    http_status = collections.Counter(event.get("attributes", {}).get("status", "unknown") for event in http_events)
    process_samples = [event for event in events if event.get("name") == "process.sample"]
    pss = [number(event.get("attributes", {}).get("pssKb")) for event in process_samples]
    cpu = [number(event.get("attributes", {}).get("processCpuPercent")) for event in process_samples]
    jank = [event for event in events if event.get("name") == "jank.sample_completed"]
    playback = [event for event in events if event.get("category") == "playback"]
    resolver = [event for event in events if event.get("category") == "resolver"]
    failures = [
        event for event in events
        if event.get("level") in {"ERROR", "FATAL"} or event.get("name", "").endswith("failed")
    ]

    return {
        "report": path.name,
        "appVersion": manifest.get("appVersion"),
        "versionCode": manifest.get("versionCode"),
        "buildSha": manifest.get("buildSha"),
        "device": manifest.get("device", {}),
        "hasCrash": "crash.txt" in names,
        "nativeExitTraceCount": sum(name.startswith("exit-traces/") for name in names),
        "eventCount": len(events),
        "categories": categories.most_common(),
        "topEvents": event_names.most_common(20),
        "failures": failures[-25:],
        "network": {
            "calls": len(http_events),
            "hosts": http_hosts.most_common(),
            "statuses": http_status.most_common(),
            "medianMs": statistics.median(http_totals) if http_totals else None,
            "p95Ms": percentile(http_totals, 0.95),
            "maxMs": max(http_totals, default=None),
        },
        "performance": {
            "samples": len(process_samples),
            "maxPssKb": max(pss, default=None),
            "maxProcessCpuPercent": max(cpu, default=None),
            "jankWindows": len(jank),
            "jank": jank,
        },
        "playbackEventCount": len(playback),
        "resolverEventCount": len(resolver),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Summarize an Anilili diagnostic ZIP")
    parser.add_argument("report", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    rendered = json.dumps(analyze(args.report), indent=2, ensure_ascii=False)
    if args.output:
        args.output.write_text(rendered + "\n", encoding="utf-8")
    else:
        print(rendered)


if __name__ == "__main__":
    main()
