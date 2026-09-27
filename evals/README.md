# Cleanup evaluation

`cleanup.jsonl` contains 24 synthetic text fixtures. They test cleanup independently of speech recognition and contain no private recordings. The [published 23-case benchmark](../docs/cleanup-benchmark.md) predates the casual-wording fixture. Its exact second-run prompt is archived in `benchmark-prompt-20260926.txt`; the runner uses the current `benchmark-prompt.txt`.

`expected_example` is one acceptable wording, not an exact-match oracle. The runner flags missing required text, including missing repeated occurrences, and forbidden additions. These are review cues, not quality scores: capitalization, contractions and equivalent numeric spellings can be acceptable.

Preview the five-model plan without making requests:

```bash
python3 scripts/benchmark_cleanup.py --provider openrouter --case-id ambiguous-time --case-id emphasis
```

Add `--run` to send requests. The script prompts for a test key without echoing it and saves synthetic outputs and usage under ignored `evals/private/`; it does not save the key. Use `--provider siliconflow` for direct SiliconFlow calls. With no case IDs, the runner uses the first three fixtures; `--case-limit 23` runs all 23. Repeat `--case-id` to select specific cases anywhere in the file, and use `--repeats 2` or `3` to check variation. The two providers use the same prompt and cases but have distinct model IDs and request formats.

For each candidate, record model/checkpoint/hash, quantization, runtime version, prompt version, context, decoding settings, raw result, finish reason, usage, latency and reviewer judgment. Run each case more than once where the backend is nondeterministic. A valid JSON result still fails if it changes a number, negation, name or the speaker's intent. Store results separately from fixtures.

Read [the cleanup contract](../docs/research/models-and-cleanup.md) before evaluating. Add a consented real-audio corpus under ignored `private/` covering Irish-accented English, names, technical dictation, background noise, headset changes, quiet speech, silence, interrupted speech and explicit corrections. Text fixtures alone cannot measure recognition quality, acoustic cleanup or VAD clipping.

Validation and cost scripts do not call providers. Decide on the budget and data policy before adding consented recordings to a live evaluation.
