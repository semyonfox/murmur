#!/usr/bin/env python3
"""Fetch public source references without running their code or installers."""

import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
LOCK = ROOT / "research" / "sources.lock.json"


def git(*args: str, cwd: Path | None = None) -> str:
    result = subprocess.run(
        ["git", "-c", "core.hooksPath=/dev/null", *args],
        cwd=cwd,
        text=True,
        capture_output=True,
        env={**os.environ, "GIT_LFS_SKIP_SMUDGE": "1", "GIT_TERMINAL_PROMPT": "0"},
        check=True,
        timeout=240,
    )
    return result.stdout.strip()


def fetch(entry: dict, pinned: dict | None) -> dict:
    target = ROOT / "research" / "upstream" / entry["id"]
    if not target.exists():
        git("clone", "--depth=1", "--no-tags", entry["url"], str(target))
    if git("remote", "get-url", "origin", cwd=target) != entry["url"]:
        raise ValueError(f"unexpected origin for {entry['id']}")
    if git("status", "--porcelain", cwd=target):
        raise ValueError(f"reference checkout has local edits: {entry['id']}")
    if pinned and git("rev-parse", "HEAD", cwd=target) != pinned["revision"]:
        git("fetch", "--depth=1", "origin", pinned["revision"], cwd=target)
        git("checkout", "--detach", pinned["revision"], cwd=target)
    files = git("ls-files", cwd=target).splitlines()
    licenses = [
        item for item in files
        if "/" not in item and item.lower().startswith(("license", "copying", "notice"))
    ]
    return {
        **entry,
        "revision": git("rev-parse", "HEAD", cwd=target),
        "commit_date": git("show", "-s", "--format=%cI", "HEAD", cwd=target),
        "tracked_files": len(files),
        "license_files": licenses,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--record", action="store_true", help="create the initial lock; never overwrites an existing lock")
    args = parser.parse_args()
    manifest = json.loads((ROOT / "research" / "repositories.json").read_text())
    if args.record and LOCK.exists():
        parser.error("lock already exists; updating evidence requires an explicit new snapshot")
    if not LOCK.exists() and not args.record:
        parser.error("no lock exists; use --record for the initial source snapshot")
    existing = json.loads(LOCK.read_text()) if LOCK.exists() else {"repositories": []}
    pins = {entry["id"]: entry for entry in existing["repositories"]}
    if not args.record and set(pins) != {entry["id"] for entry in manifest["repositories"]}:
        parser.error("manifest and lock differ; reconcile before fetching")
    results = []
    failed = False
    with ThreadPoolExecutor(max_workers=4) as executor:
        jobs = [(entry, executor.submit(fetch, entry, pins.get(entry["id"]))) for entry in manifest["repositories"]]
        for entry, job in jobs:
            try:
                result = job.result()
                results.append(result)
                print(f"{result['id']}: {result['revision'][:12]} ({result['tracked_files']} files)", flush=True)
            except (subprocess.SubprocessError, OSError, ValueError) as error:
                print(f"{entry['id']}: {error}", file=sys.stderr)
                if isinstance(error, subprocess.CalledProcessError):
                    print(error.stderr, file=sys.stderr)
                failed = True
    if failed:
        return 1
    if args.record:
        LOCK.write_text(json.dumps({
            "research_date": manifest["research_date"],
            "fetched_at_utc": datetime.now(timezone.utc).isoformat(),
            "checkout_mode": "shallow checkouts including tracked assets; no submodules, LFS objects, builds, separate model downloads, or installers",
            "repositories": results,
        }, indent=2) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
