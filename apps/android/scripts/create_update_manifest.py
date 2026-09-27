#!/usr/bin/env python3
"""Create the GitHub Release manifest for one signed Android APK."""

import argparse
import hashlib
import json
import re
from pathlib import Path
from urllib.parse import quote


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--repository", required=True, help="GitHub owner/repo")
    parser.add_argument("--tag", required=True)
    parser.add_argument("--version-code", required=True, type=int)
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()

    if not args.apk.is_file() or args.apk.suffix.lower() != ".apk":
        parser.error("apk must be an existing .apk file")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", args.repository) or any(
        part in {".", ".."} for part in args.repository.split("/")
    ):
        parser.error("repository must be owner/repo")
    if args.version_code < 1 or not args.version_name.strip():
        parser.error("version code and name must be set")
    if not args.tag or "/" in args.tag:
        parser.error("tag must be one GitHub Release tag")

    digest = hashlib.sha256()
    with args.apk.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)

    manifest = {
        "versionCode": args.version_code,
        "versionName": args.version_name,
        "apkUrl": f"https://github.com/{args.repository}/releases/download/{quote(args.tag)}/{quote(args.apk.name)}",
        "sha256": digest.hexdigest(),
        "sizeBytes": args.apk.stat().st_size,
    }
    output = args.output or args.apk.parent / "android-update.json"
    output.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(output)


if __name__ == "__main__":
    main()
