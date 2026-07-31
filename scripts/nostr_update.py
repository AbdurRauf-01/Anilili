#!/usr/bin/env python3
"""Publish the Anilili update manifest to Nostr (kind 30078, d-tag "anilili-update").

This is the kill-proof update channel: the app checks GitHub Releases first and
falls back to this signed Nostr manifest when GitHub is unreachable or the repo is
gone. Publishing a new manifest with an apkUrl pointing at ANY mirror is enough to
move every installed app to the new host — no app update required.

Setup (once):
    python -m venv scripts/.venv
    scripts/.venv/Scripts/pip install -r scripts/requirements.txt   # Windows
    scripts/.venv/bin/pip install -r scripts/requirements.txt       # Unix

The signing key is read from nostr-update-key.properties at the repo root
(gitignored) or from the NOSTR_UPDATE_SECRET env var (hex secret key).

Commands:
    keygen                                  Generate a new keypair (only if you must rotate!)
    publish --version 0.1.54 --apk-url URL [--changelog TEXT] [--size-bytes N] [--dry-run]
    fetch                                   Show the manifest relays currently return

Every version bump: publish right after the GitHub release so both channels agree.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
import time
from pathlib import Path

try:
    from coincurve import PrivateKey, PublicKeyXOnly
except ImportError:
    sys.exit("Missing dependency: pip install -r scripts/requirements.txt (use scripts/.venv)")

KIND = 30078
D_TAG = "anilili-update"
RELAYS = [
    "wss://relay.damus.io",
    "wss://nos.lol",
    "wss://relay.nostr.band",
    "wss://relay.primal.net",
]
KEY_FILE = Path(__file__).resolve().parent.parent / "nostr-update-key.properties"


def load_secret_hex() -> str:
    import os

    env = os.environ.get("NOSTR_UPDATE_SECRET")
    if env:
        return env.strip()
    if KEY_FILE.exists():
        for line in KEY_FILE.read_text().splitlines():
            if line.startswith("secretKeyHex="):
                return line.split("=", 1)[1].strip()
    sys.exit(f"No key found. Set NOSTR_UPDATE_SECRET or create {KEY_FILE} (see keygen).")


def load_public_hex() -> str | None:
    if KEY_FILE.exists():
        for line in KEY_FILE.read_text().splitlines():
            if line.startswith("publicKeyHex="):
                return line.split("=", 1)[1].strip()
    return None


def event_id(pubkey: str, created_at: int, kind: int, tags: list, content: str) -> str:
    preimage = json.dumps(
        [0, pubkey, created_at, kind, tags, content],
        separators=(",", ":"),
        ensure_ascii=False,
    )
    return hashlib.sha256(preimage.encode("utf-8")).hexdigest()


def sign_event(sk: PrivateKey, content: str, created_at: int | None = None) -> dict:
    pubkey = sk.public_key_xonly.format().hex()
    created_at = created_at or int(time.time())
    tags = [["d", D_TAG]]
    eid = event_id(pubkey, created_at, KIND, tags, content)
    return {
        "id": eid,
        "pubkey": pubkey,
        "created_at": created_at,
        "kind": KIND,
        "tags": tags,
        "content": content,
        "sig": sk.sign_schnorr(bytes.fromhex(eid)).hex(),
    }


def verify_event(event: dict) -> bool:
    expected = event_id(event["pubkey"], event["created_at"], event["kind"], event["tags"], event["content"])
    if expected != event["id"]:
        return False
    return PublicKeyXOnly(bytes.fromhex(event["pubkey"])).verify(
        bytes.fromhex(event["sig"]), bytes.fromhex(event["id"])
    )


def cmd_keygen(_args: argparse.Namespace) -> None:
    if KEY_FILE.exists():
        sys.exit(
            f"{KEY_FILE} already exists. Rotating the key means the pubkey baked into "
            "shipped APKs no longer matches — only rotate together with an app release. "
            "Delete the file manually if you really must."
        )
    import secrets

    sk = PrivateKey(secrets.token_bytes(32))
    KEY_FILE.write_text(
        "# Nostr update-manifest signing key. BACK UP with the release keystore. NEVER commit.\n"
        f"secretKeyHex={sk.secret.hex()}\n"
        f"publicKeyHex={sk.public_key_xonly.format().hex()}\n"
    )
    print(f"Wrote {KEY_FILE}")
    print(f"Bake this pubkey into NostrUpdateSource.MANIFEST_PUBKEY_HEX:\n  {sk.public_key_xonly.format().hex()}")


def cmd_publish(args: argparse.Namespace) -> None:
    manifest = {"version": args.version, "changelog": args.changelog or "", "apkUrl": args.apk_url}
    if args.size_bytes is not None:
        manifest["sizeBytes"] = args.size_bytes
    content = json.dumps(manifest, separators=(",", ":"), ensure_ascii=False)
    event = sign_event(PrivateKey(bytes.fromhex(load_secret_hex())), content)
    if not verify_event(event):
        sys.exit("Internal error: freshly signed event failed verification")
    print(json.dumps(event, indent=2))
    if args.dry_run:
        print("Dry run — not published.")
        return
    broadcast(event)


def cmd_fetch(_args: argparse.Namespace) -> None:
    pubkey = load_public_hex()
    if not pubkey:
        sys.exit(f"publicKeyHex not found in {KEY_FILE}")
    request = json.dumps([
        "REQ",
        "anilili-update-check",
        {"kinds": [KIND], "authors": [pubkey], "#d": [D_TAG], "limit": 3},
    ])
    events: list[dict] = []

    def on_event(event: dict) -> None:
        events.append(event)

    query_relays(request, on_event)
    if not events:
        print("No manifest found on any relay.")
        return
    newest = max(events, key=lambda e: e["created_at"])
    valid = verify_event(newest)
    print(f"Newest manifest (valid signature: {valid}):")
    print(json.dumps(json.loads(newest["content"]), indent=2))


def query_relays(request: str, on_event) -> None:
    import websocket

    for relay in RELAYS:
        try:
            ws = websocket.create_connection(relay, timeout=10)
            ws.send(request)
            deadline = time.time() + 10
            while time.time() < deadline:
                message = json.loads(ws.recv())
                if message[0] == "EVENT":
                    on_event(message[2])
                elif message[0] == "EOSE":
                    break
            ws.close()
        except Exception as error:  # noqa: BLE001 - relays are unreliable by nature
            print(f"  {relay}: {error}")


def broadcast(event: dict) -> None:
    import websocket

    message = json.dumps(["EVENT", event])
    for relay in RELAYS:
        try:
            ws = websocket.create_connection(relay, timeout=10)
            ws.send(message)
            reply = json.loads(ws.recv())
            ok = reply[0] == "OK" and reply[2] is True
            print(f"  {relay}: {'accepted' if ok else f'rejected: {reply}'}")
            ws.close()
        except Exception as error:  # noqa: BLE001
            print(f"  {relay}: {error}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)

    commands.add_parser("keygen", help="generate a new keypair").set_defaults(func=cmd_keygen)

    publish = commands.add_parser("publish", help="sign and broadcast a new update manifest")
    publish.add_argument("--version", required=True, help="e.g. 0.1.54 — must sort newer than the installed version")
    publish.add_argument("--apk-url", required=True, help="direct download URL of the APK asset")
    publish.add_argument("--changelog", default="", help="release notes text")
    publish.add_argument("--size-bytes", type=int, default=None, help="APK size; used only for progress display")
    publish.add_argument("--dry-run", action="store_true", help="print the signed event without broadcasting")
    publish.set_defaults(func=cmd_publish)

    commands.add_parser("fetch", help="show the manifest relays currently return").set_defaults(func=cmd_fetch)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
