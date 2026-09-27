# Additional repository audit

Research date: 2026-09-26. Revisions below match `research/sources.lock.json`. This is a static source audit of the main execution paths, manifests, licenses, and selected tests. No upstream program, installer, test suite, model, or mobile build was run. Performance, compatibility, store availability, and device measurements quoted in upstream comments remain maintainer claims.

The most useful additions are Voxtype for Linux integration and Dictus for native iOS architecture and cleanup safeguards. VocaPhone provides a more complete paired iOS/Android reference, with a copyleft licensing decision required before taking code. FUTO is useful for Android design research, but its custom licenses rule it out as a commercial code donor under the published terms.

## Revision and reuse map

| Repository | Audited revision | Top-level license | Recommendation |
| --- | --- | --- | --- |
| [Voxtype][vox-root] | `9c35b72b4fa3635028dfe70b444ca0dc9dcfb389` | MIT | Candidate for attributed Linux code reuse. Review each dependency and model separately. |
| [Dictus iOS][dictus-root] | `0fd7badf47e1f98f3e3757dc84a2b1d4720d9ed8` | MIT | Candidate for selected iOS code reuse. Exclude its private UIKit host-discovery mechanism. |
| [VocaPhone][voca-root] | `8f5ea9bc90073d6a0ab7615494280cff63096b59` | GNU AGPL v3 | Study its mobile state machines. Copy code only after choosing a compatible distribution model. |
| [FUTO Voice Input][fvi-root] | `d6e1eb2d139dc1a6342a4681c283686cca4bfceb` | FUTO Source First 1.0 | Reference only for the planned commercial product. |
| [FUTO Keyboard][fkb-root] | `70a5d390c505a6bbcc4e14966e5628e43ca3f1fc` | FUTO Source First 1.1-kb | Reference only for the planned commercial product. |

The licenses are [Voxtype MIT][vox-license], [Dictus MIT][dictus-license], [VocaPhone AGPL][voca-license], [FUTO Voice Input 1.0][fvi-license], and [FUTO Keyboard 1.1-kb][fkb-license]. These labels cover the repository's own code as stated in its top-level file. They do not automatically cover vendored code, downloadable weights, artwork, trademarks, or external binaries. No upstream implementation code was copied into Murmur during this audit.

## Voxtype

### What the code establishes

Voxtype is a Rust daemon with separate audio, transcription, output, configuration, and on-screen display modules. It is a strong reference for the specific Hyprland complaint. The native OSD creates a Wayland layer-shell `Overlay` surface, sets explicit width and height, disables keyboard interactivity, reserves zero exclusive space, and installs an empty input region so clicks pass through. This is a concrete alternative to presenting the recording indicator as an ordinary tiled application window. The GTK4 OSD also uses layer-shell with no keyboard focus and an exclusive zone of zero. [Native overlay][vox-overlay], [GTK4 overlay][vox-gtk]

Layer-shell is still a compositor capability, not a universal Wayland promise. The native implementation fails its protocol bind when layer-shell is unavailable. Murmur needs capability detection and another indicator path for those sessions. The source is evidence of an appropriate mechanism, not evidence that every scale, monitor, fullscreen app, compositor, and driver has been tested.

Text output is a fallback chain with separate implementations for `wtype`, `eitype`, `dotool`, `ydotool`, and clipboard output. The factory supports explicit driver order. macOS has CGEvent, AppleScript, and `pbcopy` paths. `session.rs` distinguishes Wayland from X11 so clipboard access can use the right tool. `paste.rs` includes clipboard restoration options, and `modifier_guard.rs` addresses held shortcut modifiers. These are the edge cases a polished desktop dictation tool has to handle. The code uses Unix APIs and Linux/macOS conditional compilation; this audit establishes no native Windows backend. [Output factory][vox-output], [session detection][vox-session], [paste implementation][vox-paste], [modifier guard][vox-modifiers]

The [README][vox-readme] recommends compositor bindings that invoke `record start`, `record stop`, or `record toggle`. That is useful on Hyprland because the compositor owns the shortcut. Its evdev fallback requires broader input access, so it should not become Murmur's automatic setup path. No user groups, permissions, services, or compositor configuration were changed.

Cleanup uses an external postprocessor. The transcript goes to stdin, optional prior-chunk context goes through `VOXTYPE_CONTEXT`, and the configured command runs via `sh -c`. On a failed command, timeout, or configured empty-output fallback, it returns the original text. The module describes the boundary clearly:

> "On any failure, the original text is used."

This is a useful recovery contract, but an arbitrary shell command is a power-user integration rather than the provider API for a consumer product. The timeout surrounds `wait_with_output`; it is not proof that every child or descendant process is terminated. Murmur should provide typed providers, cancellation, output validation, and a bounded latency budget. [Postprocessor][vox-postprocess]

There is also real acoustic processing: `GtcrnEnhancer` loads an ONNX speech-enhancement model and processes 16 kHz mono audio through STFT frames. The reviewed call sites use it for meeting microphone audio under the `onnx-common` feature, controlled by meeting echo-cancellation settings. This does not establish that every push-to-talk recording is denoised. Treat acoustic enhancement and transcript cleanup as separate pipeline stages. [Enhancer][vox-enhance], [meeting call sites][vox-daemon]

For model/provider work, the tree contains Whisper, Parakeet, Moonshine, SenseVoice, Paraformer, Dolphin, Omnilingual, Cohere, OpenVINO Whisper, and Soniox modules. `RemoteTranscriber` accepts an endpoint, model, optional key, language, and timeout for an OpenAI-compatible transcription route. The Soniox implementation has actual WebSocket streaming and token-reconciliation logic. Therefore the README's "No cloud" wording should be read as a local-default claim, not absence of cloud code. None of the advertised speed multipliers was reproduced. [Transcription modules][vox-transcribe], [remote provider][vox-remote], [Soniox][vox-soniox]

### What to take

Use the native overlay geometry and input rules, explicit output-backend selection, clipboard recovery, and cleanup-failure behavior as design inputs. Voxtype's MIT notice must accompany substantial reused portions. It does not provide the required five-platform consumer product, mobile keyboards, managed billing, or a demonstrated weekly dictation analytics system.

## Dictus iOS

### What the code establishes

The repository separates `DictusApp`, `DictusKeyboard`, and `DictusCore`. The host app owns audio recording and speech-model execution. The keyboard inserts results and exchanges session state using App Group storage and Darwin notifications. `TranscriptionService` can delegate through `SpeechModelProtocol`, with WhisperKit and alternative engine paths. `DictationCoordinator` handles cold starts, recording requests, cancellation, history, and a separate keyboard-side polish handoff. These are useful native boundaries for Murmur's iOS implementation. [Transcription service][dictus-stt], [coordinator][dictus-coordinator]

The [README][dictus-readme] advertises iOS 17+, local models, and a TestFlight beta. Its roadmap still marks cleanup and history as future work, but this revision contains both implementation paths. Conversely, its statement that Full Access is "required for microphone" must not be copied as an explanation of the keyboard's privileges. The host app records; Full Access does not make microphone capture available to an iOS keyboard extension. Apple's [custom-keyboard documentation][apple-keyboard] explains that extension restriction.

Cleanup is more substantial than the README suggests. `AppleFoundationModelsPolishEngine` is gated to iOS/macOS 26 and the availability of Apple's Foundation Models. It prewarms sessions and discards a session after one transformation to avoid carrying earlier dictations into later prompts. `PolishPipeline` accounts for context limits, cancellation, execution timing, decode/typography work, and rejection outcomes. `PolishGuardrail` checks length, language, grounding, line-level word overlap, and output-prefix alignment. These checks are heuristics; they do not prove semantic fidelity or preservation of every number and negation. [Engine][dictus-polish-engine], [pipeline][dictus-polish-pipeline], [guardrails][dictus-guardrail]

The prompt directory contains repair, natural, auto, notes, message, structure, and translation variants. Study how the implementation separates faithful cleanup from deliberate transformations. Do not assume the same prompt or acceptance thresholds suit both. A physical-device comparison is needed before selecting Apple's model as a default, especially because device and system availability differ. [Prompts][dictus-prompts]

History records text, duration, time, language, and STT provider, and the saved text can be updated when keyboard-side polish completes. That is a useful recovery model. It is not a complete cost ledger or evidence of the requested week-by-week WPM dashboard. [History record][dictus-history]

### Private API finding

`HostAppResolver.swift` explicitly documents its host-app lookup:

> "This is private API"

The implementation resolves `_UIKeyboardArbiterClient`, reads private host-process state, and uses a process-local table to resolve the host bundle ID. `HostArbiterActivation.m` goes further: an Objective-C load-time constructor replaces the private class method `enabled` with an implementation returning `YES`. This is active method replacement before the keyboard's normal startup. Nil guards and runtime lookup do not turn that API into a supported one. [Resolver][dictus-host], [load-time method replacement][dictus-swizzle]

Exclude this mechanism from Murmur's shipping code. Apple's current review guideline 2.5.1 requires public APIs; private UIKit changes also create an OS-update failure risk. Automatic return to every arbitrary host app cannot be promised on the strength of this implementation. Prototype a supported host-app handoff with an explicit user-return fallback, and test it on real devices before promising the desktop interaction on iPhone. This is an engineering and review-risk assessment, not a claim about Dictus's actual review outcome. [Apple review guidelines][apple-review]

MIT makes selected Dictus code a possible donor, subject to notices and dependency licenses. It does not remove these platform constraints.

## VocaPhone

### What the code establishes

VocaPhone has separate Swift iOS and Kotlin Android applications, paired with an optional VocaGateway submodule. The README states that Android is on Google Play for Android 13+ and iOS is in TestFlight for iOS 17+. These are maintainer distribution claims; no install or store review was performed. The repository also links VocaLinux, VocaMac, and VocaWin, but those sibling repositories were not part of this additional source audit. [README][voca-readme]

The iOS host app owns the microphone and model runtime. The keyboard consumes shared session state and inserts through `UITextDocumentProxy`. `RecordingCoordinator` and `LocalModelManager` contain host lifecycle and local-model handling, including WhisperKit and Sherpa recognizers. A separate gateway client creates, uploads, finishes, and fetches sessions. The gateway submodule was not fetched, so this audit cannot establish the gateway's server internals, provider support, accounting, or security. [iOS coordinator][voca-recording], [local models][voca-models], [gateway client][voca-gateway]

The iOS URL handoff deserves its own prototype. `KeyboardURLLauncher.m` walks the responder chain and invokes URL-opening selectors dynamically through Objective-C messaging. The keyboard controller adds a 1.5-second failure fallback because an accepted request can produce no completion callback, then uses `extensionContext.open` as another path. These are source-level signs of host/version-dependent behavior. This audit found no equivalent to Dictus's arbiter swizzle in this path, but that is not proof of universal support or App Store acceptance. [URL launcher][voca-launcher], [keyboard controller][voca-keyboard]

On Android, `VocaPhoneInputMethodService` inserts using `InputConnection.commitText` and reports no-target and unsupported-editor outcomes. The input policy excludes password fields and restricts dictation to ordinary text input. It cancels owned dictation when the editor finishes or input unbinds. The reviewed `undo` method returns `false`; an interface and an undo-shaped data type do not mean working undo is present. `DictationService` holds a foreground service during the dictation, while `DictationController` owns local/gateway routing, retries, and insertion. [IME][voca-ime], [input policy][voca-input-policy], [service][voca-service], [controller][voca-controller]

### Cleanup and statistics

The cleanup contract is unusually clear. `TranscriptRepair` says:

> "Everything here is rule-based."

It masks protected spans, removes selected hesitation sounds, collapses stutters, repairs punctuation, and uses conservative inference rules. It explicitly preserves terms that might carry meaning, and distinguishes English fillers from words in other languages. `TranscriptStyler` then changes case, spacing, and punctuation; it does not rewrite sentences through an LLM. Both have corresponding Kotlin implementations and targeted test files. This is useful for a cheap deterministic first pass, but it does not fulfill Murmur's context-aware grammar and cleanup requirement by itself. [Repair][voca-repair], [style][voca-style], [Android repair tests][voca-repair-tests]

`UsageStats` stores word and dictation totals, duration, daily word counts, and streaks. WPM is aggregate words divided by aggregate recorded minutes. iOS additionally keeps daily dictation counts and seconds. Its event store writes events atomically, folds them into a summary, and tracks consumed IDs to avoid recounting. This is a good pattern for keyboard/host communication without retaining transcripts in the statistics record. [iOS stats][voca-stats-ios], [event store][voca-stats-store], [Android stats][voca-stats-android]

Both platforms cap retained daily buckets at seven. That cannot support a reliable comparison between this week and previous weeks. Android's daily aggregates also omit duration, so historical daily WPM cannot be calculated from those buckets. Murmur should retain longer counts-only daily aggregates and distinguish raw spoken words, cleaned output words, audio duration, and successful insertion count. Missing duration must be excluded from WPM rather than counted as zero-time speech. The reviewed types contain no provider cost or billable-unit ledger.

### Reuse decision

AGPL permits commercial activity, but it has source-distribution obligations, including its network-interaction requirement for modified covered software. Treat this as a licensing choice for any derivative, not a small attribution footer. Keeping separately authored services across a protocol boundary is not a blanket answer to whether copied code or a combined application is covered. If Murmur should remain permissively licensed or proprietary, use VocaPhone's observable designs as research and implement the needed behavior independently. Model packages and the gateway need their own license checks. [License, including sections 5, 6, and 13][voca-license]

## FUTO Voice Input

The README says development has largely moved to FUTO Keyboard. Its Android integration paths are an exported `RECOGNIZE_SPEECH` activity and an IME with a voice subtype. The manifest confirms those entries. A `WhisperRecognizerService` file exists, but its callbacks are empty and its manifest registration is commented out. Do not claim SpeechRecognizer API support just because that class name exists. [README][fvi-readme], [manifest][fvi-manifest], [stub service][fvi-stub]

`VoiceInputMethodService` uses `commitText` for final output and `setComposingText` for partial output. `AudioRecognizer` contains VAD-driven capture and stopping logic. `WhisperGGML` calls a bundled JNI library, serializes inference on a dedicated coroutine context, accepts language and prompt parameters, and receives partial results. The tree includes native Whisper/ggml source. These are useful examples of native Android input and local inference, not an LLM cleanup or multi-provider SaaS implementation. [IME][fvi-ime], [audio/VAD][fvi-audio], [JNI wrapper][fvi-whisper]

The [license][fvi-license] limits use and modification to non-commercial purposes without anticipated commercial application, and distribution to free non-commercial distribution. It also restricts removal of payment functionality and notices. The intended commercial product should not copy or modify this application under those terms. If an underlying component is separately permissively licensed, obtain and audit that component from its own source rather than assuming the FUTO wrapper has the same license.

## FUTO Keyboard

This is a LatinIME-derived Android keyboard with integrated local voice input. `VoiceInputAction` creates a recognizer with language settings, optional personal-dictionary glossary, Bluetooth preference, audio focus, and VAD auto-stop. It owns an input transaction, cancels that transaction when recording is cancelled, and applies final or partial text on the main thread. `ModelOutputSanitizer` uses text before and after the cursor to adjust capitalization, punctuation, and spacing. That is a practical example of bounded insertion context, not general LLM cleanup. [Voice action][fkb-action], [sanitizer][fkb-sanitizer]

The `voiceinput-shared` source contains the recorder, recognizer view, Whisper bridge, and model manager. The repository also declares submodules for models, binary libraries, layouts, translations, themes, and other resources. Those dependencies were not fetched. A source-only checkout is insufficient to establish a reproducible complete build or the license of every shipped asset. The broader keyboard's prediction features should not be mistaken for a transcript cleanup pipeline. [Shared voice input][fkb-shared], [submodules][fkb-submodules]

There is a naming mismatch: the README calls the license FUTO Source First 1.1, while the actual file is headed FUTO Source First 1.1-kb. The latter allows use for any purpose but limits modification to non-commercial purposes and distribution of the software or any source portion to free non-commercial purposes. It also preserves payment functionality and notices. That distinction from Voice Input 1.0 matters, but neither published license is suitable for copying into this planned commercial product. Preserve the exact variant in dependency records; the README shorthand is insufficient. [README][fkb-readme], [license][fkb-license]

The README identifies separately licensed AOSP ancestry and Apache-2.0 layouts. That is not permission to treat the whole modified keyboard as Apache-2.0. Reuse of a particular file would require tracing its provenance and governing notices. Prefer independent implementation from Android's documented IME APIs for Murmur.

## Decisions these references support

1. Keep the Linux recording indicator separate from the settings window. Use a non-focusing layer-shell surface where the compositor supports it, and expose a tested fallback elsewhere.
2. Build native mobile capture and input adapters. A shared transcription protocol is valuable; a shared desktop window is not a mobile keyboard architecture.
3. Preserve deterministic repair, faithful LLM cleanup, deliberate rewriting, and acoustic enhancement as separate operations with separate tests.
4. Adopt session IDs, cancellation, recoverable transcripts, and exactly-once statistics before adding visual polish. Test app-switch and input-target changes explicitly.
5. Store enough counts-only daily history for weekly comparisons. Keep estimated/provider-billed cost in a separate ledger with documented units.
6. Prefer MIT donors for selected implementation reuse. Keep VocaPhone behind an explicit copyleft decision and FUTO out of commercial implementation code under its current terms.

Verification still required includes real Hyprland/KDE/GNOME behavior, Windows injection, signed macOS builds, physical iPhone keyboard lifecycle, Android OEM and IME behavior, model quality/latency, cleanup semantic fidelity, battery/memory cost, and store distribution. No repository in this audit demonstrates all five target platforms with those checks completed.

[vox-root]: https://github.com/peteonrails/voxtype/tree/9c35b72b4fa3635028dfe70b444ca0dc9dcfb389
[vox-license]: https://github.com/peteonrails/voxtype/blob/9c35b72b4fa3635028dfe70b444ca0dc9dcfb389/LICENSE
[vox-readme]: https://github.com/peteonrails/voxtype/blob/9c35b72b4fa3635028dfe70b444ca0dc9dcfb389/README.md
[vox-overlay]: https://github.com/peteonrails/voxtype/blob/9c35b72b4fa3635028dfe70b444ca0dc9dcfb389/src/bin/voxtype_osd_native/app.rs#L211
[vox-gtk]: https://github.com/peteonrails/voxtype/blob/9c35b72b4fa3635028dfe70b444ca0dc9dcfb389/src/bin/voxtype_osd_gtk4.rs#L349
[vox-output]: https://github.com/peteonrails/voxtype/blob/9c35b72b4fa3635028dfe70b444ca0dc9dcfb389/src/output/mod.rs
[vox-session]: https://github.com/peteonrails/voxtype/blob/9c35b72b4fa3635028dfe70b444ca0dc9dcfb389/src/output/session.rs
[vox-paste]: https://github.com/peteonrails/voxtype/blob/9c35b72b4fa3635028dfe70b444ca0dc9dcfb389/src/output/paste.rs
[vox-modifiers]: https://github.com/peteonrails/voxtype/blob/9c35b72b4fa3635028dfe70b444ca0dc9dcfb389/src/output/modifier_guard.rs
[vox-postprocess]: https://github.com/peteonrails/voxtype/blob/9c35b72b4fa3635028dfe70b444ca0dc9dcfb389/src/output/post_process.rs
[vox-enhance]: https://github.com/peteonrails/voxtype/blob/9c35b72b4fa3635028dfe70b444ca0dc9dcfb389/src/audio/enhance.rs
[vox-daemon]: https://github.com/peteonrails/voxtype/blob/9c35b72b4fa3635028dfe70b444ca0dc9dcfb389/src/daemon.rs#L2145
[vox-transcribe]: https://github.com/peteonrails/voxtype/tree/9c35b72b4fa3635028dfe70b444ca0dc9dcfb389/src/transcribe
[vox-remote]: https://github.com/peteonrails/voxtype/blob/9c35b72b4fa3635028dfe70b444ca0dc9dcfb389/src/transcribe/remote.rs
[vox-soniox]: https://github.com/peteonrails/voxtype/blob/9c35b72b4fa3635028dfe70b444ca0dc9dcfb389/src/transcribe/soniox.rs
[dictus-root]: https://github.com/getdictus/dictus-ios/tree/0fd7badf47e1f98f3e3757dc84a2b1d4720d9ed8
[dictus-license]: https://github.com/getdictus/dictus-ios/blob/0fd7badf47e1f98f3e3757dc84a2b1d4720d9ed8/LICENSE
[dictus-readme]: https://github.com/getdictus/dictus-ios/blob/0fd7badf47e1f98f3e3757dc84a2b1d4720d9ed8/README.md
[dictus-stt]: https://github.com/getdictus/dictus-ios/blob/0fd7badf47e1f98f3e3757dc84a2b1d4720d9ed8/DictusApp/Audio/TranscriptionService.swift
[dictus-coordinator]: https://github.com/getdictus/dictus-ios/blob/0fd7badf47e1f98f3e3757dc84a2b1d4720d9ed8/DictusApp/DictationCoordinator.swift
[dictus-polish-engine]: https://github.com/getdictus/dictus-ios/blob/0fd7badf47e1f98f3e3757dc84a2b1d4720d9ed8/DictusCore/Sources/DictusCore/Polish/AppleFoundationModelsPolishEngine.swift
[dictus-polish-pipeline]: https://github.com/getdictus/dictus-ios/blob/0fd7badf47e1f98f3e3757dc84a2b1d4720d9ed8/DictusCore/Sources/DictusCore/Polish/PolishPipeline.swift
[dictus-guardrail]: https://github.com/getdictus/dictus-ios/blob/0fd7badf47e1f98f3e3757dc84a2b1d4720d9ed8/DictusCore/Sources/DictusCore/Polish/PolishGuardrail.swift
[dictus-prompts]: https://github.com/getdictus/dictus-ios/tree/0fd7badf47e1f98f3e3757dc84a2b1d4720d9ed8/DictusCore/Sources/DictusCore/Polish/Prompts
[dictus-history]: https://github.com/getdictus/dictus-ios/blob/0fd7badf47e1f98f3e3757dc84a2b1d4720d9ed8/DictusCore/Sources/DictusCore/History/TranscriptionRecord.swift
[dictus-host]: https://github.com/getdictus/dictus-ios/blob/0fd7badf47e1f98f3e3757dc84a2b1d4720d9ed8/DictusKeyboard/HostAppResolver.swift
[dictus-swizzle]: https://github.com/getdictus/dictus-ios/blob/0fd7badf47e1f98f3e3757dc84a2b1d4720d9ed8/DictusKeyboard/HostArbiterActivation.m
[voca-root]: https://github.com/VocaHQ/vocaphone/tree/8f5ea9bc90073d6a0ab7615494280cff63096b59
[voca-license]: https://github.com/VocaHQ/vocaphone/blob/8f5ea9bc90073d6a0ab7615494280cff63096b59/LICENSE
[voca-readme]: https://github.com/VocaHQ/vocaphone/blob/8f5ea9bc90073d6a0ab7615494280cff63096b59/README.md
[voca-recording]: https://github.com/VocaHQ/vocaphone/blob/8f5ea9bc90073d6a0ab7615494280cff63096b59/ios/VocaPhoneApp/Sessions/RecordingCoordinator.swift
[voca-models]: https://github.com/VocaHQ/vocaphone/blob/8f5ea9bc90073d6a0ab7615494280cff63096b59/ios/VocaPhoneApp/Models/LocalModelManager.swift
[voca-gateway]: https://github.com/VocaHQ/vocaphone/blob/8f5ea9bc90073d6a0ab7615494280cff63096b59/ios/VocaPhoneApp/Networking/GatewayClient.swift
[voca-launcher]: https://github.com/VocaHQ/vocaphone/blob/8f5ea9bc90073d6a0ab7615494280cff63096b59/ios/VocaPhoneKeyboard/KeyboardURLLauncher.m
[voca-keyboard]: https://github.com/VocaHQ/vocaphone/blob/8f5ea9bc90073d6a0ab7615494280cff63096b59/ios/VocaPhoneKeyboard/KeyboardViewController.swift#L1298
[voca-ime]: https://github.com/VocaHQ/vocaphone/blob/8f5ea9bc90073d6a0ab7615494280cff63096b59/android/app/src/main/java/com/vocahq/vocaphone/ime/VocaPhoneInputMethodService.kt#L429
[voca-input-policy]: https://github.com/VocaHQ/vocaphone/blob/8f5ea9bc90073d6a0ab7615494280cff63096b59/android/app/src/main/java/com/vocahq/vocaphone/core/ImeInputPolicy.kt
[voca-service]: https://github.com/VocaHQ/vocaphone/blob/8f5ea9bc90073d6a0ab7615494280cff63096b59/android/app/src/main/java/com/vocahq/vocaphone/dictation/DictationService.kt
[voca-controller]: https://github.com/VocaHQ/vocaphone/blob/8f5ea9bc90073d6a0ab7615494280cff63096b59/android/app/src/main/java/com/vocahq/vocaphone/dictation/DictationController.kt
[voca-repair]: https://github.com/VocaHQ/vocaphone/blob/8f5ea9bc90073d6a0ab7615494280cff63096b59/ios/VocaPhoneShared/TranscriptRepair.swift
[voca-style]: https://github.com/VocaHQ/vocaphone/blob/8f5ea9bc90073d6a0ab7615494280cff63096b59/ios/VocaPhoneShared/TranscriptStyler.swift
[voca-repair-tests]: https://github.com/VocaHQ/vocaphone/blob/8f5ea9bc90073d6a0ab7615494280cff63096b59/android/app/src/test/java/com/vocahq/vocaphone/core/TranscriptRepairTest.kt
[voca-stats-ios]: https://github.com/VocaHQ/vocaphone/blob/8f5ea9bc90073d6a0ab7615494280cff63096b59/ios/VocaPhoneShared/UsageStats.swift
[voca-stats-store]: https://github.com/VocaHQ/vocaphone/blob/8f5ea9bc90073d6a0ab7615494280cff63096b59/ios/VocaPhoneShared/UsageStatsStore.swift
[voca-stats-android]: https://github.com/VocaHQ/vocaphone/blob/8f5ea9bc90073d6a0ab7615494280cff63096b59/android/app/src/main/java/com/vocahq/vocaphone/core/UsageStats.kt
[fvi-root]: https://github.com/futo-org/voice-input/tree/d6e1eb2d139dc1a6342a4681c283686cca4bfceb
[fvi-license]: https://github.com/futo-org/voice-input/blob/d6e1eb2d139dc1a6342a4681c283686cca4bfceb/LICENSE.md
[fvi-readme]: https://github.com/futo-org/voice-input/blob/d6e1eb2d139dc1a6342a4681c283686cca4bfceb/README.md
[fvi-manifest]: https://github.com/futo-org/voice-input/blob/d6e1eb2d139dc1a6342a4681c283686cca4bfceb/app/src/main/AndroidManifest.xml
[fvi-stub]: https://github.com/futo-org/voice-input/blob/d6e1eb2d139dc1a6342a4681c283686cca4bfceb/app/src/main/java/org/futo/voiceinput/WhisperRecognizerService.kt
[fvi-ime]: https://github.com/futo-org/voice-input/blob/d6e1eb2d139dc1a6342a4681c283686cca4bfceb/app/src/main/java/org/futo/voiceinput/VoiceInputMethodService.kt
[fvi-audio]: https://github.com/futo-org/voice-input/blob/d6e1eb2d139dc1a6342a4681c283686cca4bfceb/app/src/main/java/org/futo/voiceinput/AudioRecognizer.kt
[fvi-whisper]: https://github.com/futo-org/voice-input/blob/d6e1eb2d139dc1a6342a4681c283686cca4bfceb/app/src/main/java/org/futo/voiceinput/ggml/WhisperGGML.kt
[fkb-root]: https://github.com/futo-org/android-keyboard/tree/70a5d390c505a6bbcc4e14966e5628e43ca3f1fc
[fkb-license]: https://github.com/futo-org/android-keyboard/blob/70a5d390c505a6bbcc4e14966e5628e43ca3f1fc/LICENSE.md
[fkb-readme]: https://github.com/futo-org/android-keyboard/blob/70a5d390c505a6bbcc4e14966e5628e43ca3f1fc/README.md
[fkb-action]: https://github.com/futo-org/android-keyboard/blob/70a5d390c505a6bbcc4e14966e5628e43ca3f1fc/java/src/org/futo/inputmethod/latin/uix/actions/VoiceInputAction.kt
[fkb-sanitizer]: https://github.com/futo-org/android-keyboard/blob/70a5d390c505a6bbcc4e14966e5628e43ca3f1fc/java/src/org/futo/inputmethod/latin/uix/utils/ModelOutputSanitizer.kt
[fkb-shared]: https://github.com/futo-org/android-keyboard/tree/70a5d390c505a6bbcc4e14966e5628e43ca3f1fc/voiceinput-shared/src/main
[fkb-submodules]: https://github.com/futo-org/android-keyboard/blob/70a5d390c505a6bbcc4e14966e5628e43ca3f1fc/.gitmodules
[apple-keyboard]: https://developer.apple.com/library/archive/documentation/General/Conceptual/ExtensibilityPG/CustomKeyboard.html
[apple-review]: https://developer.apple.com/app-store/review/guidelines/#software-requirements
