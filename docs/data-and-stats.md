# Usage, speed and cost data

The statistics should answer how much the user dictated, how their speaking rate changed, and what it cost. They should not imply that a model's edits are all corrections or that every emitted paste event reached the intended field.

The current desktop stores raw and cleaned text with WAV recordings. Fresh installs keep them until manual deletion or a shorter retention choice; older saved choices are preserved. The optional `.tar.gz` export includes retained entries and available original WAV files, with no encryption or restore flow yet. It is a backup copy rather than in-app audio compression. The desktop Stats page derives words, recorded pace and minutes from retained history. It also requests current-key usage from OpenRouter for configured OpenRouter speech and cleanup keys. Identical keys are counted once. These provider totals can include use outside Murmur and do not identify which dictation caused a charge. Direct SiliconFlow charges and local inference costs are not included. The richer per-operation, cross-device and managed-service records below remain proposed work. [OpenRouter current-key endpoint](https://openrouter.ai/docs/api/api-reference/api-keys/get-current-api-key), [BYOK billing explanation](https://openrouter.ai/docs/guides/overview/auth/byok).

## Local records

Keep a session record with a unique ID, device ID, start time in UTC, captured local timezone/date, language, recording duration, VAD speech duration if available, raw word count, final word count, delivery status, platform adapter, model/provider identifiers, prompt version and stage latencies. Word counts need a versioned tokenizer policy. Use language-aware segmentation for scripts that do not separate words with spaces. Do not compare language mixes as though every word count were equivalent.

Store raw and final text in a separate optional history record keyed by session ID. Audio is separate again. Deleting history can retain anonymous local aggregates if the user chooses; deleting all activity removes both. Optional per-app metrics use an app identifier, not window titles or document contents. A user can disable them without disabling WPM.

Provider operations are separate from dictation sessions. One session might use local STT, paid cleanup and a retry. Store provider operation and attempt IDs, billed audio duration or token usage, estimated versus reconciled cost, price-version ID and currency. Never equate character counts divided by four with billed provider tokens.

## Definitions

| Metric           | Definition                                                                                                                                                                        |
| ---------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Speaking WPM     | `60 × sum(raw_word_count) / sum(recording_seconds)` for successful recognition sessions. This is an ASR-derived estimate of speech rate, not a measurement of every acoustic word |
| Articulation WPM | Optional `60 × raw_words / VAD_speech_seconds`, clearly labeled and not mixed into speaking WPM                                                                                   |
| Output WPM       | `60 × final_words / recording_seconds`, separately labeled because cleanup can shorten speech                                                                                     |
| Words delivered  | Final word count for confirmed insertion. Show unacknowledged sends and copies separately where the OS cannot confirm insertion                                                   |
| Weekly change    | Compare weighted WPM and volume across two complete local calendar weeks; current-week view compares the same elapsed period of the prior week                                    |
| Activity         | Sessions, recorded minutes and final words by day/week; counts deduplicate by session ID across devices                                                                           |
| Cleanup edits    | Estimated edit distance between raw and final text. Label as edits, never guaranteed corrections                                                                                  |
| Time saved       | Optional estimate using a user-specified typing baseline, subtracting speaking and processing time. Label the assumption and clamp negative savings to zero                       |
| Latency          | Capture-stop to final-ready and final-ready to insertion, with p50/p95 and cold/warm model distinction                                                                            |
| Cost             | STT, cleanup and total; provider estimate, reconciled usage and customer charge remain distinguishable                                                                            |

WPM is a ratio of sums, not an average of session rates. A 10-second session containing 20 words and a 50-second session containing 80 words produce 100 WPM overall, not 108. Exclude zero-duration and cancelled sessions from speed calculations. Very short clips should show insufficient data rather than a dramatic weekly speed claim. Show sample minutes beside every comparison. A suggested first threshold is one minute of recorded speech, subject to usability testing.

Do not double-count retranscription, cleanup retries, copying history or inserting the same saved result twice as new spoken words. Keep those actions visible separately if useful. Day and week definitions use the captured timezone and a user-selected week start; a timezone change must not silently rewrite past charts.

## Spend control

Direct BYOK shows a conservative local estimate and stops sending from that device at a configured cap. It cannot enforce the provider account's total spend across other applications or devices. Recommend provider-side budgets where available and label the local limitation.

Managed accounts reserve credit before each request, settle authoritative usage afterwards and reconcile unknown outcomes. A server-enforced monthly cap includes outstanding reservations, otherwise parallel requests can overspend. Pricing changes apply through versioned effective dates and never silently change a completed receipt.

Local mode has zero API cost, with energy/hardware costs excluded. Hosting a model yourself replaces API spend with utilization and operations costs; do not present it as free. Include retry rates, average clip duration and cleanup context length in projections. [The calculator](../scripts/cost_model.py) models short clips and repeated cleanup prompt cost.

## Acceptance examples

- Weighted WPM matches the 100-word, 60-second example.
- A cancelled recording adds no delivered words and no speaking-speed sample.
- An API timeout with uncertain billing remains pending, not zero cost.
- Two devices syncing the same session do not duplicate weekly totals.
- Deleting transcripts leaves only the aggregates the user chose to retain.
- A Sunday session recorded before travel remains in the same captured local day.
- Changing the STT model or cleanup prompt remains visible in trend interpretation.
- A cost receipt can be recomputed from usage and the exact price snapshot.
