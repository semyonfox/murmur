#!/usr/bin/env python3
"""Check the research workspace without running any upstream application."""

import argparse
from collections import Counter
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import subprocess
import sys
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parents[1]


def git(directory: Path, *args: str) -> str:
    return subprocess.check_output(["git", "-c", "core.hooksPath=/dev/null", *args], cwd=directory, text=True).strip()


def verify() -> dict:
    lock = json.loads((ROOT / "research" / "sources.lock.json").read_text())
    manifest = json.loads((ROOT / "research" / "repositories.json").read_text())
    assert {entry["id"]: entry["url"] for entry in manifest["repositories"]} == {entry["id"]: entry["url"] for entry in lock["repositories"]}, "manifest differs from lock"
    sources = {}
    inventory = []
    for entry in lock["repositories"]:
        directory = ROOT / "research" / "upstream" / entry["id"]
        assert git(directory, "rev-parse", "HEAD") == entry["revision"], f"revision mismatch: {entry['id']}"
        assert git(directory, "remote", "get-url", "origin") == entry["url"], f"origin mismatch: {entry['id']}"
        assert not git(directory, "status", "--porcelain"), f"modified reference: {entry['id']}"
        names = git(directory, "ls-files").splitlines()
        assert len(names) == entry["tracked_files"], f"inventory mismatch: {entry['id']}"
        sources[entry["url"].removesuffix(".git").removeprefix("https://github.com/").lower()] = (directory, entry["revision"])
        inventory.append({
            "id": entry["id"],
            "revision": entry["revision"],
            "tracked_files": len(names),
            "extensions": dict(Counter(Path(name).suffix or "[none]" for name in names).most_common()),
            "root_manifests": [name for name in names if "/" not in name and name in {"Cargo.toml", "package.json", "go.mod", "Package.swift", "build.gradle", "build.gradle.kts", "pyproject.toml", "Makefile"}],
            "workflows": [name for name in names if name.startswith(".github/workflows/")],
            "license_files": entry["license_files"],
            "clean": True,
        })
    docs = [ROOT / "README.md", ROOT / "AGENTS.md", *sorted((ROOT / "docs").rglob("*.md")), ROOT / "evals" / "README.md"]
    local_links = 0
    pinned_links = 0
    external_links = set()
    for document in docs:
        content = document.read_text()
        assert content.endswith("\n"), f"missing final newline: {document}"
        assert all(line == line.rstrip() for line in content.splitlines()), f"trailing whitespace: {document}"
        assert content.count("```") % 2 == 0, f"unbalanced code fence: {document}"
        links = re.findall(r"\[[^\]]*\]\(([^)]+)\)", content)
        links += re.findall(r"^\[[^\]]+\]:\s+(\S+)", content, re.MULTILINE)
        for link in links:
            parts = urlsplit(link)
            if parts.scheme in {"http", "https"}:
                external_links.add(link)
                path_parts = parts.path.strip("/").split("/")
                if parts.netloc == "github.com" and len(path_parts) >= 4 and path_parts[2] in {"blob", "tree"}:
                    source = sources.get("/".join(path_parts[:2]).lower())
                    if source and re.fullmatch(r"[0-9a-f]{40}", path_parts[3]):
                        directory, revision = source
                        assert path_parts[3] == revision, f"stale pin in {document}: {link}"
                        target = directory / unquote("/".join(path_parts[4:]))
                        assert target.exists(), f"missing source path in {document}: {link}"
                        if re.fullmatch(r"L\d+", parts.fragment) and target.is_file():
                            assert int(parts.fragment[1:]) <= len(target.read_text().splitlines()), f"source line out of range: {link}"
                        pinned_links += 1
            elif not parts.scheme and parts.path:
                assert (document.parent / unquote(parts.path)).exists(), f"broken local link in {document}: {link}"
                local_links += 1
    json_files = [*sorted((ROOT / "config").glob("*.json")), ROOT / "research" / "repositories.json", ROOT / "research" / "sources.lock.json"]
    for filename in json_files:
        json.loads(filename.read_text())
    fixture_ids = set()
    for line in (ROOT / "evals" / "cleanup.jsonl").read_text().splitlines():
        case = json.loads(line)
        assert {"id", "raw", "expected_example", "must_preserve", "must_not_add", "review"} <= case.keys()
        assert case["id"] not in fixture_ids, f"duplicate fixture: {case['id']}"
        assert all(isinstance(case[key], str) for key in ["id", "raw", "expected_example", "review"])
        assert all(isinstance(case[key], list) and all(isinstance(value, str) for value in case[key]) for key in ["must_preserve", "must_not_add"])
        fixture_ids.add(case["id"])
    tests = subprocess.run([sys.executable, "-m", "unittest", "discover", "-s", "scripts", "-p", "test_*.py", "-v"], cwd=ROOT, capture_output=True, text=True)
    assert tests.returncode == 0, tests.stdout + tests.stderr
    return {
        "verified_at_utc": datetime.now(timezone.utc).isoformat(),
        "repositories": inventory,
        "tracked_reference_files": sum(entry["tracked_files"] for entry in inventory),
        "documents_checked": len(docs),
        "local_file_links_checked": local_links,
        "pinned_github_links_checked_against_local_sources": pinned_links,
        "unique_external_links_recorded_not_http_tested": len(external_links),
        "json_files_parsed": len(json_files),
        "cleanup_fixtures_parsed_not_model_evaluated": len(fixture_ids),
        "cost_tests": tests.stderr.strip(),
        "runtime_verification": "research links and fixtures checked here; native app and inference checks are documented in docs/performance-smoke.md; credential-store runtime remains untested",
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write-report", action="store_true")
    args = parser.parse_args()
    report = verify()
    if args.write_report:
        (ROOT / "research" / "verification.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({key: value for key, value in report.items() if key != "repositories"}, indent=2))


if __name__ == "__main__":
    main()
