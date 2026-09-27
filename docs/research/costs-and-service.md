# Costs and managed service

Research snapshot: 2026-09-26, with the OpenRouter transcription listing checked on 2026-09-27. Prices below are public list prices observed on those dates, in USD unless marked EUR. These are planning calculations, not a retail price commitment. The later [cleanup benchmark](../cleanup-benchmark.md) made BYOK requests with reported upstream cost; its invoices were not independently verified. No managed account or infrastructure was created.

## Recommendation

Build local and direct bring-your-own-key modes first, with the same usage accounting interface that a managed service will need. The provisional hosted STT choice is OpenRouter `openai/whisper-large-v3-turbo`, using a separate key and endpoint from cleanup. Compare it with direct Groq Whisper Turbo and OpenAI transcription on real speech before treating it as a quality or latency winner. Keep Deepgram as the streaming and vocabulary candidate. The [cleanup benchmark](../cleanup-benchmark.md) provisionally favors Qwen3.5-35B-A3B through OpenRouter; those measurements do not establish STT quality.

The speech and cleanup calls can cost well below a dollar for ordinary personal usage. The expensive part of a polished product is maintaining five platforms, support, payments, and reliability. A tiny percentage markup on inference alone will not reliably fund that work. An optional service charge plus metered usage is a more honest starting point for the commercial model. Do not promise unlimited usage.

## Provider price snapshot

Use separate price records for transcription and cleanup. The token rates below are ordinary online inference rates, with no batch discounts, cache discounts, free credits, or negotiated commitments.

### Speech recognition

| Provider and model                         |                                     Public price | Metering note                                                                     |
| ------------------------------------------ | -----------------------------------------------: | --------------------------------------------------------------------------------- |
| OpenRouter `openai/whisper-large-v3-turbo` | From $0.00000333/audio second, about $0.012/hour | Model catalog rate; provider minimums and BYOK invoices need checking per request |
| Groq `whisper-large-v3-turbo`              |                                 $0.04/audio hour | Minimum 10 billed seconds per request                                             |
| Groq `whisper-large-v3`                    |                                $0.111/audio hour | Minimum 10 billed seconds per request                                             |
| OpenAI `gpt-4o-mini-transcribe`            |                          Estimated $0.003/minute | Use returned usage for settlement; the minute figure is an estimate               |
| OpenAI `gpt-4o-transcribe`                 |                          Estimated $0.006/minute | Same qualification                                                                |
| Deepgram Nova-3 prerecorded, monolingual   |                                   $0.0043/minute | Pay as you go                                                                     |
| Deepgram Nova-3 prerecorded, multilingual  |                                   $0.0052/minute | Pay as you go                                                                     |
| Deepgram Nova-3 streaming, monolingual     |      Promotional $0.0048/minute; regular $0.0077 | Do not base a long-term retail promise on the promotion                           |
| Deepgram Nova-3 streaming, multilingual    |      Promotional $0.0058/minute; regular $0.0092 | Same qualification                                                                |

Sources: [OpenRouter model listing](https://openrouter.ai/openai/whisper-large-v3-turbo/api) and [transcription guide](https://openrouter.ai/docs/guides/overview/multimodal/stt), [Groq speech-to-text documentation](https://console.groq.com/docs/speech-to-text), [OpenAI API pricing](https://developers.openai.com/api/docs/pricing), [Deepgram pricing](https://deepgram.com/pricing).

Deepgram's pricing-page FAQ specifies per-second billing and charging separately for processed channels. Send mono dictation audio. Its published pay-as-you-go keyterm prompting add-on costs another $0.0013/minute; smart formatting is included. Formatting punctuation is not a replacement for transcript cleanup that handles corrections and repetition. The growth plan begins with $4,000 of annual prepaid credits, so it makes little sense for an initial personal deployment. [Deepgram pricing and FAQ](https://deepgram.com/pricing)

For Groq, a 5-second recording incurs a 10-second minimum. An 11-second recording is not documented as becoming 20 seconds. Calculate `sum(max(request_duration_seconds, 10))`, with any finer provider rounding reconciled against actual usage. Splitting one utterance into many VAD chunks multiplies minimum charges. VAD should normally trim silence and decide when to stop; it need not create a billable request for every detected phrase. [Groq audio limits](https://console.groq.com/docs/speech-to-text)

### Transcript cleanup

| Provider and model         |  Input per million tokens | Output per million tokens | Planning use                                                     |
| -------------------------- | ------------------------: | ------------------------: | ---------------------------------------------------------------- |
| Groq `openai/gpt-oss-20b`  |                    $0.075 |                     $0.30 | Cheap candidate to evaluate                                      |
| Groq `openai/gpt-oss-120b` |                     $0.15 |                     $0.60 | Quality comparison candidate                                     |
| OpenAI `gpt-4.1-mini`      |                     $0.40 |                     $1.60 | Non-reasoning comparison candidate                               |
| Local cleanup model        | $0 provider inference fee | $0 provider inference fee | Device compute, memory, battery, and model download costs remain |

Sources: [Groq supported models](https://console.groq.com/docs/models), [GPT-4.1 mini model documentation](https://developers.openai.com/api/docs/models/gpt-4.1-mini). The current Groq catalog lists Llama 3.1 8B and Llama 3.3 70B as enterprise models with contact-sales pricing, so old public Llama rates should not be copied into a new default configuration.

GPT-OSS has reasoning output. Groq documents `low`, `medium`, and `high` effort for it, without a `none` setting. Hiding reasoning in a response does not establish that its generation is free. Reserve for total generated output and record reported usage. Use the lowest effort that passes the cleanup evaluation. [Groq reasoning documentation](https://console.groq.com/docs/reasoning)

No ChatGPT subscription credit is assumed here. Murmur needs a provider API billing arrangement for these calls. Subscription access to ChatGPT or Codex is not a dictation API entitlement; OpenAI documents API-key usage against API pricing separately. [Official OpenAI pricing guidance](https://learn.chatgpt.com/docs/pricing)

### OpenRouter fees

OpenRouter can route both transcription and cleanup, but it adds another processor and billing layer. The transcription endpoint accepts the same key as chat, returns usage data, and currently does not apply chat-style per-request `provider.order` or `only` controls. For BYOK, reconcile reported upstream cost against the provider's own bill; the [cleanup benchmark](../cleanup-benchmark.md) observed `usage.cost: 0` while upstream inference cost was nonzero. A SiliconFlow BYOK key for cleanup does not imply that Whisper STT runs on SiliconFlow; the listed Whisper Large V3 Turbo providers are DeepInfra and Groq. Its Standard plan lists a 5.5% platform fee; Business lists 8%. Current BYOK allowances use list-price inference value, not request count: Standard and Business include $25,000/month without the BYOK fee, followed by 5%; Enterprise lists $200,000/month. Verify the customer's actual plan before calculating. [OpenRouter STT guide](https://openrouter.ai/docs/guides/overview/multimodal/stt), [model providers](https://openrouter.ai/openai/whisper-large-v3-turbo/api), [pricing](https://openrouter.ai/pricing).

For Standard card top-ups, OpenRouter describes a 5.5% credit-purchase fee with a $0.80 minimum. Thus $5 of credits costs $5.80 before tax, while $20 costs $21.10. Apply this at purchase, then allocate it across consumed credits; do not add $0.80 to each inference. OpenRouter says failed requests are not billed, but a client timeout is not proof the upstream request failed. [OpenRouter spend-control explanation](https://openrouter.ai/blog/insights/governing-team-ai-spend/)

Prefer direct provider adapters for the managed service's first version. OpenRouter remains useful as an explicit user choice and for evaluating alternatives. Preserve its provider selection and privacy settings when retrying.

## Reproducible monthly examples

Assume every recording is 30 seconds, with 150 spoken words/minute and 4/3 tokens/word. That gives 200 transcript tokens per audio minute. Each cleanup call sends another 800 input tokens for instructions, vocabulary, and bounded app context. The cleaned output has the same token count as the transcript.

For GPT-OSS only, include a provisional budget of 200 extra reasoning tokens per call. This is a sensitivity assumption, not a measured average or a guaranteed ceiling. GPT-4.1 mini has no reasoning step. Real vocabulary size, language, punctuation, and output length change these numbers.

```text
M = recorded audio minutes
S = seconds per recording
N = 60 * M / S

cleanup_input_tokens = 200 * M + 800 * N
cleanup_visible_output_tokens = 200 * M
cleanup_reasoning_tokens = 200 * N for the GPT-OSS example; 0 for GPT-4.1 mini

groq_audio_cost = N * max(S, 10) / 3600 * hourly_rate
cleanup_cost = input_tokens / 1,000,000 * input_rate
             + total_output_tokens / 1,000,000 * output_rate
```

The formula for `N` is an illustrative uniform-session model. Production accounting sums individual recordings, including partial final recordings. A mean duration alone cannot determine minimum-charge overhead when sessions cross the 10-second threshold.

| Monthly audio                                    |  60 minutes | 300 minutes | 1,200 minutes |
| ------------------------------------------------ | ----------: | ----------: | ------------: |
| Number of 30-second recordings                   |         120 |         600 |         2,400 |
| Cleanup input tokens                             |     108,000 |     540,000 |     2,160,000 |
| Visible cleanup output tokens                    |      12,000 |      60,000 |       240,000 |
| Extra GPT-OSS reasoning allowance                |      24,000 |     120,000 |       480,000 |
| Groq Turbo STT only                              |     $0.0400 |     $0.2000 |       $0.8000 |
| Groq GPT-OSS 20B cleanup only                    |     $0.0189 |     $0.0945 |       $0.3780 |
| Groq Turbo + GPT-OSS 20B                         |     $0.0589 |     $0.2945 |       $1.1780 |
| Groq Large V3 + GPT-OSS 20B                      |     $0.1299 |     $0.6495 |       $2.5980 |
| OpenAI mini-transcribe + GPT-4.1 mini            |     $0.2424 |     $1.2120 |       $4.8480 |
| Deepgram prerecorded mono + GPT-4.1 mini         |     $0.3204 |     $1.6020 |       $6.4080 |
| Deepgram prerecorded multilingual + GPT-4.1 mini |     $0.3744 |     $1.8720 |       $7.4880 |
| Fully local STT and cleanup                      | $0 API fees | $0 API fees |   $0 API fees |

These totals exclude retries, failures that still consume provider compute, taxes, currency conversion, payment fees, storage, networking, support, and retail margin. They are arithmetic comparisons, not evidence of equal accuracy or speed. No caching discount is assumed.

For 300 minutes/month, short requests change the Groq Turbo + GPT-OSS 20B example:

| Recording length | Requests | Groq billed audio minutes |     STT | Cleanup |   Total |
| ---------------- | -------: | ------------------------: | ------: | ------: | ------: |
| 5 seconds        |    3,600 |                       600 | $0.4000 | $0.4545 | $0.8545 |
| 15 seconds       |    1,200 |                       300 | $0.2000 | $0.1665 | $0.3665 |
| 30 seconds       |      600 |                       300 | $0.2000 | $0.0945 | $0.2945 |
| 60 seconds       |      300 |                       300 | $0.2000 | $0.0585 | $0.2585 |

At 30-second sessions, increasing the reasoning allowance from 200 to 1,000 tokens per call adds $0.144 at 300 audio minutes. This makes token reporting and a completion limit useful even at these low rates. A 10,000-token context repeated for every short dictation would be wasteful and expose unnecessary content.

## Three operating modes and their boundaries

| Mode        | Payment and key ownership                                    | Data path                                                                        | Cost controls                                                                       |
| ----------- | ------------------------------------------------------------ | -------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------- |
| Fully local | User supplies device; no provider key                        | Audio, transcript, cleanup, and history stay on device under the proposed design | CPU/GPU/RAM limits, model storage, battery policy                                   |
| Direct BYOK | User pays provider; key stays in OS credential storage       | Client sends only the required audio or text directly to selected provider       | Local estimates and session limits; provider budget is authoritative across devices |
| Managed     | Murmur holds provider credentials server-side and bills user | Client → Murmur service → selected providers                                     | Atomic server budget reservations, usage settlement, account and global caps        |

A local STT model combined with cloud cleanup still sends the transcript and selected context off the device. A BYOK key routed through Murmur would also add Murmur to the data path, so it must not be labelled direct BYOK. For the first version, keep BYOK keys out of the managed service entirely. Never switch a local session to cloud without the user's explicit choice.

Provider controls differ. Deepgram retains requests for model improvement by default; send `mip_opt_out=true` on every dictation request. Its regional endpoint and opt-out setting together define the documented residency boundary. Since March 5, 2026, opting out does not change public pay-as-you-go or Growth rates. [Deepgram data handling](https://developers.deepgram.com/trust-security/your-data), [pricing change](https://developers.deepgram.com/changelog/2026/3/5)

Groq documents optional zero data retention controls, with exceptions and defaults around abuse and reliability logging. OpenAI's endpoint table distinguishes transcription, which lists no abuse-monitoring retention, from cleanup through chat/responses, which normally has up to 30 days of abuse-monitoring retention. `store=false` should be set where applicable, but it is not a blanket zero-retention guarantee. [Groq data controls](https://console.groq.com/docs/your-data), [OpenAI data controls](https://developers.openai.com/api/docs/guides/your-data)

OpenRouter provider retention also depends on the chosen upstream provider. Treat provider routing restrictions as part of the user's data policy. [OpenRouter provider logging](https://openrouter.ai/docs/guides/privacy/provider-logging)

Default managed processing should keep content only long enough to finish the request and recover an interrupted delivery. Do not put audio, transcript text, clipboard contents, or app context into request logs or payment metadata. History and statistics belong on the device by default. Cloud sync, if added, needs its own retention and deletion design.

## Managed service design

The following is a proposed implementation contract, not work already built.

### Reserve, execute, settle

1. The authenticated client starts a session with a unique session ID, selected provider/model, maximum recording duration, cleanup policy, and its accepted price version. The server validates these values against an allowlist.
2. In one database transaction, calculate the maximum allowed cost and reserve it against both the user's available balance and monthly cap. Include provider minimums, bounded context, output-token ceiling, permitted retries, currency conversion policy, and the published service fee. Concurrent sessions must compete for the same balance atomically.
3. Accept bounded audio, validate real duration server-side, and record each provider attempt separately. A client-provided duration or token estimate must never determine the final debit.
4. Run STT, then cleanup if enabled and within the reservation. Each stage records provider request ID, model, measured or reported units, quoted price version, latency, and outcome. Preserve a raw transcript on the client according to its retention setting so cleanup failure does not discard successful dictation.
5. Settle reported usage once, release unused reserved funds, and return a receipt containing the stage costs and service charge. The user sees an estimate until actual usage is known. Keep provider costs and the customer price as separate records.
6. If a request times out after transmission, mark its cost unresolved. Reconcile it against provider usage when possible. Do not release the entire reservation and silently create another paid request. Bound this unresolved state with a documented loss policy and maximum financial exposure.

Use integer micro-units or decimal arithmetic for money. Keep immutable ledger entries for reserve, settle, release, refund, and correction, plus currency and price-version IDs. Enforce a unique settlement for each operation. Corrections append entries; they do not overwrite history.

An idempotency key must be scoped to the account and operation, with a payload fingerprint. Reusing the key with different input is an error. A duplicate identical request returns the saved outcome or current state. This prevents duplicate Murmur debits; it cannot promise exactly-once upstream execution if a provider lacks equivalent guarantees. Keep the smallest short-lived encrypted result cache needed for delivery recovery, isolated by account.

### Retries and partial failures

Respect `Retry-After`, use bounded backoff with jitter, and do not retry invalid credentials, invalid input, or exhausted budgets. A retry must fit the original reservation or obtain a new reservation under the same user-approved cap. Do not retry the complete pipeline when only cleanup failed. Return the raw transcript and allow an explicit cleanup retry.

Track supplier costs even when the product refunds the customer. A suitable first policy is one customer charge for a successful delivered dictation, with bounded automatic recovery costs absorbed by the service. Make partial-success treatment explicit: a successful raw transcript with failed cleanup can charge STT only. A local paste failure should recover the existing result without invoking paid models again.

Treat refunds, chargebacks, duplicate payment webhooks, and out-of-order store notifications as ledger events. A top-up becomes available only after verified payment settlement. Never trust the client to grant paid credits or a subscription. Bind every store purchase to the correct account and deduplicate by its transaction identifier.

### Limits that matter

Set per-session maximum duration, input size, context tokens, output tokens, concurrent requests, retries, daily spending, and monthly spending. Add an organization-wide provider cap and a service-wide emergency stop. Expose remaining budget before recording and preserve work if the provider becomes unavailable.

Provider dashboards are a second line of defence. Groq spend tracking can lag by 10 to 15 minutes and in-flight requests complete after its limit is reached. Its rate limits apply at organization level, so new API keys do not create extra capacity. Current free Whisper limits include 20 requests/minute and 7,200 audio seconds/hour; use actual account limits for admission control. [Groq spend limits](https://console.groq.com/docs/spend-limits), [Groq rate limits](https://console.groq.com/docs/rate-limits)

The published Deepgram pay-as-you-go plan lists up to 50 REST and 150 WebSocket STT connections. OpenAI model pages publish tier-specific limits, with free access unsupported for the models used here. None of these public numbers proves what an unprovisioned Murmur account will receive. [Deepgram plans](https://deepgram.com/pricing), [OpenAI mini-transcribe model](https://developers.openai.com/api/docs/models/gpt-4o-mini-transcribe)

### Price records and user-facing cost management

Record provider, endpoint, model and revision, region, currency, unit rates, minimum duration, rounding policy, add-ons, observed date, effective date if supplied, source URL, and whether a rate is promotional or estimated. Never recalculate historical sessions against today's price. Preserve FX and markup policies alongside the quote.

For each session, expose recorded seconds, provider-billed seconds, STT cost, cleanup cost, retry adjustment, service fee, and final total. Show local inference as zero API cost, with device usage separately if measured. Give users a month-to-date total, budget alert, forecast, and a CSV export. Forecast from their session distribution and actual tokens, not a generic words-per-minute assumption.

Keep speaking statistics independent of billing. Weighted WPM should use accepted word count divided by the selected time measure, with recording time and detected speech time labelled separately. Do not inflate productivity stats with retries, failed pastes, duplicate sync events, or provider minimum-billed duration. Local use should retain the same weekly charts.

## Payment economics

Stripe's Ireland pricing lists 1.5% + €0.25 for standard EEA cards. Premium EEA cards, UK cards, other international cards, FX, and additional Stripe products can cost more. The table isolates the standard card fee and is not a tax or FX model. [Stripe Ireland pricing](https://stripe.com/ie/pricing)

| Customer payment | Standard EEA card fee | Share lost to payment fee |
| ---------------- | --------------------: | ------------------------: |
| €1               |                €0.265 |                     26.5% |
| €5               |                €0.325 |                      6.5% |
| €10              |                €0.400 |                      4.0% |

A 20% markup on €1 of supplier cost produces €1.20 of revenue. The illustrative Stripe fee is €0.268, leaving a €0.068 loss before any hosting or support. With a 15% store commission instead, that same markup leaves €0.02 before overhead. A markup and a margin are different measures.

Aggregate payments into sensible top-ups or monthly billing. A minimum top-up of €5 to €10 is an option to test, not a selected launch price. Explain any fixed service charge plainly rather than calling all margin a network fee. Actual network expenditure should be measured.

```text
required pre-tax price =
  (supplier cost + allocated infrastructure + target contribution + fixed payment fee)
  / (1 - percentage payment fee)
```

Keep tax calculation and USD/EUR conversion outside that simplified equation. Before publishing a price, include refunds, failed payments, store commissions, fraud, support time, model downloads, and the number of active paying users sharing fixed hosting costs. Do not count free credits as recurring margin.

## Store billing and commercial permission

### iOS and macOS App Store

Apple's standard review rules generally require IAP for digital functionality and require IAP availability for covered multiplatform purchases. A free companion exception exists, but its applicability to this product is not established. Purchased IAP credits may not expire. Keep purchasing in the containing app; extensions cannot contain IAP. US storefront external-link rules differ from other storefronts. [App Review Guidelines, sections 3.1 and 4.4](https://developer.apple.com/app-store/review/guidelines/)

Eligible enrolled developers can receive the Small Business Program's 15% commission. Do not assume eligibility or apply this rate to every storefront and distribution method. [Apple Small Business Program](https://developer.apple.com/app-store/small-business-program/)

This snapshot lands five days before another EU change. Apple announced unified EU terms effective October 1, 2026, including a 5% Core Technology Commission on digital transactions in apps distributed outside the App Store and removal of the previous Initial Acquisition and Store Services fees. The announcement also changes alternative payment options. Recheck the accepted agreement and exact distribution route before designing checkout or quoting margins. [Apple announcement dated August 18, 2026](https://developer.apple.com/news/?id=gmws0jgp)

### Android and Google Play

Play's payment policy covers in-app digital features and cloud software, subject to regional and programme exceptions. Direct Android distribution is possible, but it is a separate release and update channel. Do not assume a Stripe link inside the Play build is universally allowed. [Payments policy](https://support.google.com/googleplay/android-developer/answer/9858738?hl=en), [policy explanation](https://support.google.com/googleplay/android-developer/answer/10281818?hl=en)

Google's current fee page distinguishes EEA, UK, and US transactions from June 30, 2026. It lists a 10% service fee plus a 5% Play billing fee for auto-renewing subscriptions; other transactions also depend on earnings tier, install date, and programme. Remaining markets retain the documented 15% subscription rate and the enrolled first-$1-million tier pending rollout. Alternative billing does not automatically eliminate Play fees. Model the actual region and enrolled terms at launch. [Google Play service fees](https://support.google.com/googleplay/android-developer/answer/112622?hl=en-GB)

### Provider permission

A technical proxy is straightforward; permission to sell standalone access is a separate question. Groq's agreement permits integration into a customer application and access by its end users, while prohibiting resale or lease of the account itself. Its authorized-reseller provisions describe a distinct arrangement. [Groq Services Agreement, sections 3 and 18](https://console.groq.com/docs/legal/services-agreement)

Deepgram's August 6, 2026 terms prohibit standalone resale and permit application integration that adds material independent functionality. Murmur's native capture, cleanup, vocabulary, insertion, history, and analytics are relevant to that distinction, but this report does not decide contractual compliance. [Deepgram terms, section 2.4](https://deepgram.com/terms)

Before enabling paid managed access, review the chosen providers' then-current agreements, end-user obligations, data-processing terms, and any requested reseller approval. OpenRouter's terms updated 31 August 2026 contemplate customers using Models inside their own products in section 5.1, but section 7.4 prohibits accessing its service to resell API access to Models or develop a competing service. A paid Murmur dictation feature may be distinguishable from a generic API proxy, but do not market or launch the proposed proxy/markup through OpenRouter without written clarification. OpenAI resale terms were not resolved in this study. Sell a dictation application and its service, never shared API credentials. Keep a small managed pilot behind explicit capacity and spending caps until real invoices match the ledger and store acceptance is established. [OpenRouter terms](https://openrouter.ai/terms).

SiliconFlow's published `.cn` Platform Use Agreement limits access to personal or internal business use in section 2.1 and restricts selling or transferring API keys and reselling the service in section 2.2. Section 3.5 nevertheless discusses developers providing services to third parties, so these clauses do not settle whether a paid, integrated Murmur dictation service is permitted. Confirm which agreement governs the actual `.com` or OpenRouter BYOK account and get written permission for the exact managed route before taking customer payments. Direct user-supplied keys do not amount to Murmur selling its own API access. [SiliconFlow Platform Use Agreement](https://api-docs.siliconflow.cn/docs/legals/terms-of-service).
