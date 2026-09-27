#!/usr/bin/env python3
"""Estimate transcription and cleanup cost from a dated price snapshot."""

import argparse
from decimal import Decimal
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ZERO = Decimal(0)


def duration_profile(minutes: Decimal, session_seconds: Decimal) -> list[Decimal]:
    if not minutes.is_finite() or not session_seconds.is_finite() or minutes <= 0 or session_seconds <= 0:
        raise ValueError("minutes and session duration must be finite and positive")
    total = minutes * 60
    complete = int(total // session_seconds)
    if complete > 1_000_000:
        raise ValueError("too many sessions; use a smaller scenario")
    remainder = total % session_seconds
    return [session_seconds] * complete + ([remainder] if remainder else [])


def estimate(
    durations: list[Decimal],
    stt: dict,
    cleanup: dict,
    context_tokens: int = 800,
    tokens_per_minute: int = 200,
    reasoning_tokens: int | None = None,
) -> dict[str, Decimal | int]:
    if not durations or any(not duration.is_finite() or duration <= 0 for duration in durations):
        raise ValueError("session durations must be finite and positive")
    if context_tokens < 0 or tokens_per_minute < 0 or (reasoning_tokens is not None and reasoning_tokens < 0):
        raise ValueError("token assumptions must be nonnegative")
    minimum = Decimal(stt["minimum_seconds"])
    billed_seconds = sum((max(duration, minimum) for duration in durations), ZERO)
    if "usd_per_hour" in stt:
        stt_cost = billed_seconds / 3600 * Decimal(stt["usd_per_hour"])
    else:
        stt_cost = billed_seconds / 60 * Decimal(stt["usd_per_minute"])
    transcript_tokens = sum(durations, ZERO) / 60 * tokens_per_minute
    input_tokens = transcript_tokens + context_tokens * len(durations)
    reasoning_per_session = cleanup["assumed_reasoning_tokens_per_session"] if reasoning_tokens is None else reasoning_tokens
    output_tokens = transcript_tokens + reasoning_per_session * len(durations)
    cleanup_cost = (
        input_tokens * Decimal(cleanup["input_usd_per_million"])
        + output_tokens * Decimal(cleanup["output_usd_per_million"])
    ) / 1_000_000
    return {
        "sessions": len(durations),
        "audio_minutes": sum(durations, ZERO) / 60,
        "stt_billable_seconds_estimate": billed_seconds,
        "stt_billable_minutes_estimate": billed_seconds / 60,
        "cleanup_input_tokens_estimate": input_tokens,
        "cleanup_output_tokens_estimate": output_tokens,
        "stt_usd": stt_cost,
        "cleanup_usd": cleanup_cost,
        "inference_usd": stt_cost + cleanup_cost,
    }


def main() -> None:
    rates = json.loads((ROOT / "config" / "pricing.snapshot.json").read_text())
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--minutes", type=Decimal, default=Decimal(300))
    parser.add_argument("--session-seconds", type=Decimal, default=Decimal(30))
    parser.add_argument("--sessions-json", type=Path, help="optional JSON array of positive recorded durations in seconds, overrides minutes/session-seconds")
    parser.add_argument("--stt", choices=rates["stt"], default="groq-turbo")
    parser.add_argument("--cleanup", choices=rates["cleanup"], default="groq-oss20")
    parser.add_argument("--context-tokens", type=int, default=800)
    parser.add_argument("--tokens-per-minute", type=int, default=200)
    parser.add_argument("--reasoning-tokens", type=int, default=None, help="assumed billed reasoning output per session; defaults vary by cleanup model")
    args = parser.parse_args()
    try:
        if args.sessions_json:
            values = json.loads(args.sessions_json.read_text())
            if not isinstance(values, list) or any(isinstance(value, bool) or not isinstance(value, (int, float, str)) for value in values):
                raise ValueError("sessions JSON must be an array of durations")
            durations = [Decimal(str(value)) for value in values]
        else:
            durations = duration_profile(args.minutes, args.session_seconds)
        result = estimate(durations, rates["stt"][args.stt], rates["cleanup"][args.cleanup], args.context_tokens, args.tokens_per_minute, args.reasoning_tokens)
    except (ValueError, ArithmeticError, OSError) as error:
        parser.error(str(error))
    print(json.dumps({
        "price_date": rates["verified_on"],
        "stt": args.stt,
        "cleanup": args.cleanup,
        **{key: str(value) if isinstance(value, Decimal) else value for key, value in result.items()},
        "excludes": "retries, failed paid requests, hosting, model downloads, energy, payments, tax, FX, markup and support",
        "warning": "token counts and some STT rates are estimates; no free tier or prompt cache discount assumed; reasoning allowance is unmeasured",
    }, indent=2))


if __name__ == "__main__":
    main()
