#!/usr/bin/env python3
"""Compare transcript cleanup with the same prompt and synthetic inputs."""

import argparse
import getpass
import io
import json
import statistics
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path
from unittest import TestCase, mock


ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "evals" / "cleanup.jsonl"
PROMPT = ROOT / "evals" / "benchmark-prompt.txt"
MODELS = [
    ("Qwen3.5-9B", "Qwen/Qwen3.5-9B", "qwen/qwen3.5-9b"),
    ("Qwen3.5-35B-A3B", "Qwen/Qwen3.5-35B-A3B", "qwen/qwen3.5-35b-a3b"),
    ("DeepSeek-V4.1-Flash", "deepseek-ai/DeepSeek-V4.1-Flash", "deepseek/deepseek-v4.1-flash"),
    ("Qwen3.5-122B-A10B", "Qwen/Qwen3.5-122B-A10B", "qwen/qwen3.5-122b-a10b"),
    ("Kimi-K2.6", "moonshotai/Kimi-K2.6", "moonshotai/kimi-k2.6"),
]
ENDPOINTS = {
    "siliconflow": "https://api.siliconflow.com/v1/chat/completions",
    "openrouter": "https://openrouter.ai/api/v1/chat/completions",
}
ROUTE_RETRY_DELAYS = (0.5, 1.0, 2.0)


def fixtures() -> list[dict]:
    with FIXTURES.open(encoding="utf-8") as source:
        return [json.loads(line) for line in source if line.strip()]


def content_delta(payload: dict) -> str:
    choices = payload.get("choices") or []
    if not choices:
        return ""
    content = choices[0].get("delta", {}).get("content")
    return content if isinstance(content, str) else ""


def lexical_review(case: dict, output: str) -> tuple[list[str], list[str]]:
    folded = output.casefold()
    seen_required = Counter()
    missing = []
    for item in case.get("must_preserve", []):
        match = item.casefold()
        seen_required[match] += 1
        if folded.count(match) < seen_required[match]:
            missing.append(item)
    added = [item for item in case.get("must_not_add", []) if item.casefold() in folded]
    return missing, added


def run_case(provider: str, model: str, case: dict, prompt: str, key: str) -> dict:
    body = {
        "model": model,
        "messages": [
            {"role": "system", "content": prompt},
            {
                "role": "user",
                "content": json.dumps(
                    {"transcript": case["raw"], "vocabulary": case.get("vocabulary", [])},
                    ensure_ascii=False,
                ),
            },
        ],
        "stream": True,
        "temperature": 0,
        "max_tokens": 1200,
    }
    if provider == "siliconflow":
        body["enable_thinking"] = False
    else:
        body["reasoning"] = {"enabled": False}
        body["stream_options"] = {"include_usage": True}

    request = urllib.request.Request(
        ENDPOINTS[provider],
        data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
        headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"},
        method="POST",
    )
    start = time.monotonic()
    first_token_ms = None
    chunks = []
    usage = None
    actual_model = None
    generation_id = None
    finish_reason = None
    reasoning_seen = False
    with urllib.request.urlopen(request, timeout=90) as response:
        for raw_line in response:
            line = raw_line.decode("utf-8", errors="replace").strip()
            if not line.startswith("data: "):
                continue
            data = line[6:]
            if data == "[DONE]":
                break
            payload = json.loads(data)
            if payload.get("error"):
                raise ValueError("Provider returned an error during streaming")
            actual_model = payload.get("model") or actual_model
            generation_id = payload.get("id") or generation_id
            usage = payload.get("usage") or usage
            choices = payload.get("choices") or []
            if choices:
                finish_reason = choices[0].get("finish_reason") or finish_reason
                delta = choices[0].get("delta", {})
                reasoning_seen |= bool(delta.get("reasoning") or delta.get("reasoning_content"))
            chunk = content_delta(payload)
            if chunk:
                if first_token_ms is None:
                    first_token_ms = round((time.monotonic() - start) * 1000)
                chunks.append(chunk)

    output = "".join(chunks).strip()
    missing, added = lexical_review(case, output)
    details = (usage or {}).get("completion_tokens_details") or {}
    reasoning_tokens = details.get("reasoning_tokens") or 0
    return {
        "provider": provider,
        "model": model,
        "actual_model": actual_model,
        "generation_id": generation_id,
        "case": case["id"],
        "ttft_ms": first_token_ms,
        "total_ms": round((time.monotonic() - start) * 1000),
        "output": output,
        "finish_reason": finish_reason,
        "usage": usage,
        "reasoning_seen": reasoning_seen,
        "reasoning_tokens": reasoning_tokens,
        "missing_required_text": missing,
        "introduced_forbidden_text": added,
    }


def openrouter_route(generation_id: str, key: str) -> dict:
    url = "https://openrouter.ai/api/v1/generation?" + urllib.parse.urlencode({"id": generation_id})
    request = urllib.request.Request(url, headers={"Authorization": f"Bearer {key}"})
    unavailable = {"status": "unavailable", "reason": "metadata_not_ready", "attempts": 0}
    for attempt in range(len(ROUTE_RETRY_DELAYS) + 1):
        if attempt:
            time.sleep(ROUTE_RETRY_DELAYS[attempt - 1])
        try:
            with urllib.request.urlopen(request, timeout=15) as response:
                payload = json.load(response)
        except urllib.error.HTTPError as error:
            if error.code != 404:
                return {"status": "unavailable", "reason": "http_error", "http_status": error.code,
                        "attempts": attempt + 1}
            unavailable = {"status": "unavailable", "reason": "metadata_not_ready", "http_status": 404,
                           "attempts": attempt + 1}
            continue
        except (urllib.error.URLError, TimeoutError):
            return {"status": "unavailable", "reason": "network_error", "attempts": attempt + 1}
        except ValueError:
            return {"status": "unavailable", "reason": "invalid_response", "attempts": attempt + 1}

        data = payload.get("data") if isinstance(payload, dict) else None
        if not isinstance(data, dict) or not data.get("provider_name"):
            unavailable = {"status": "unavailable", "reason": "metadata_not_ready", "http_status": 200,
                           "attempts": attempt + 1}
            continue
        return {"status": "ready", "attempts": attempt + 1, **{
            field: data.get(field)
            for field in ("provider_name", "is_byok", "router", "upstream_inference_cost")
        }}
    return unavailable


class RouteMetadataTests(TestCase):
    def test_retries_missing_metadata_then_returns_provider(self) -> None:
        missing = urllib.error.HTTPError("https://example.test", 404, "missing", None, None)
        empty = io.BytesIO(b'{"data": {}}')
        ready = io.BytesIO(b'{"data": {"provider_name": "SiliconFlow", "is_byok": true}}')
        with mock.patch("urllib.request.urlopen", side_effect=[missing, empty, ready]) as open_request, \
                mock.patch("time.sleep") as sleep:
            route = openrouter_route("generation-test", "test-token")
        self.assertEqual(route["provider_name"], "SiliconFlow")
        self.assertEqual(route["is_byok"], True)
        self.assertEqual(route["attempts"], 3)
        self.assertEqual([call.args[0] for call in sleep.call_args_list], [0.5, 1.0])
        self.assertEqual(open_request.call_count, 3)

    def test_exhaustion_records_status_without_response_body(self) -> None:
        missing = urllib.error.HTTPError("https://example.test", 404, "secret body", None, None)
        with mock.patch("urllib.request.urlopen", side_effect=missing), mock.patch("time.sleep"):
            route = openrouter_route("generation-test", "test-token")
        self.assertEqual(route, {"status": "unavailable", "reason": "metadata_not_ready",
                                 "http_status": 404, "attempts": 4})


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--provider", choices=ENDPOINTS, required=True)
    parser.add_argument("--case-limit", type=int, help="run the first N cases (default: 3, or all selected IDs)")
    parser.add_argument("--case-id", action="append", metavar="ID", help="select a fixture ID; repeat for more")
    parser.add_argument("--repeats", type=int, default=1)
    parser.add_argument("--route-metadata", action="store_true", help="query OpenRouter generation metadata")
    parser.add_argument("--run", action="store_true", help="make billable API requests")
    args = parser.parse_args()
    if (args.case_limit is not None and args.case_limit < 1) or args.repeats < 1 or args.repeats > 3:
        parser.error("case-limit must be positive and repeats must be 1–3")
    cases = fixtures()
    if args.case_id:
        selected = set(args.case_id)
        unknown = selected - {case["id"] for case in cases}
        if unknown:
            parser.error(f"unknown case ID(s): {', '.join(sorted(unknown))}")
        cases = [case for case in cases if case["id"] in selected]
    if args.case_limit is not None:
        cases = cases[: args.case_limit]
    elif not args.case_id:
        cases = cases[:3]
    plan = [(name, sf if args.provider == "siliconflow" else or_id, case)
            for name, sf, or_id in MODELS for case in cases for _ in range(args.repeats)]
    print(f"{args.provider}: {len(plan)} requests, {len(cases)} fixed synthetic cases, {args.repeats} repeat(s)")
    if not args.run:
        print("Dry run only. Pass --run to make billable requests.")
        return 0

    key = getpass.getpass(f"{args.provider} test key: ").strip()
    if not key:
        parser.error("API key is required")
    prompt = PROMPT.read_text(encoding="utf-8").strip()
    output_dir = ROOT / "evals" / "private"
    output_dir.mkdir(exist_ok=True)
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    destination = output_dir / f"cleanup-{args.provider}-{stamp}.jsonl"
    completed = defaultdict(list)
    with destination.open("x", encoding="utf-8") as target:
        for name, model, case in plan:
            try:
                result = run_case(args.provider, model, case, prompt, key)
            except urllib.error.HTTPError as error:
                result = {"provider": args.provider, "model": model, "case": case["id"],
                          "error": f"HTTP {error.code}", "total_ms": None}
            except (urllib.error.URLError, TimeoutError, ValueError) as error:
                result = {"provider": args.provider, "model": model, "case": case["id"],
                          "error": type(error).__name__, "total_ms": None}
            if args.route_metadata and args.provider == "openrouter" and result.get("generation_id"):
                result["route"] = openrouter_route(result["generation_id"], key)
            target.write(json.dumps(result, ensure_ascii=False) + "\n")
            target.flush()
            completed[name].append(result)
            status = result.get("error") or f"{result['total_ms']} ms"
            print(f"{name} / {case['id']}: {status}")
            if result.get("reasoning_seen") or result.get("reasoning_tokens"):
                print("Reasoning appeared despite disable request; stopping this benchmark.")
                break

    for name, results in completed.items():
        successes = [result for result in results if "output" in result]
        if not successes:
            print(f"{name}: no successful cases")
            continue
        ttfts = [result["ttft_ms"] for result in successes if result["ttft_ms"] is not None]
        median_ttft = round(statistics.median(ttfts)) if ttfts else None
        median_total = round(statistics.median(result["total_ms"] for result in successes))
        lexical_flags = sum(bool(result["missing_required_text"] or result["introduced_forbidden_text"])
                            for result in successes)
        print(f"{name}: {len(successes)} successful, median TTFT {median_ttft} ms, "
              f"median total {median_total} ms, {lexical_flags} lexical review flags")
    print(f"Synthetic outputs and usage saved privately to {destination}")
    print("Review meaning, ASR repairs, tone, and unnecessary rewriting by hand; lexical flags are not quality scores.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
