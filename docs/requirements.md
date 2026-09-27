# Product requirements

Murmur is a temporary name for a replacement for Wispr Flow. The goal is reliable dictation into the application the user is already using, with natural cleanup, useful statistics and predictable cost. The product must cover Linux, Windows, macOS, iOS and Android. A Linux desktop MVP and an Android endpoint-first input-method prototype exist; no platform is certified for release.

## Required behavior

| Requirement            | What counts as working                                                                                                                                                                                                              |
| ---------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Desktop dictation      | Configurable hold and toggle shortcuts, immediate recording feedback, cancellation, transcription and insertion without opening a settings window                                                                                   |
| Linux polish           | Native Hyprland/CachyOS path; the recording indicator never becomes a tiled half-screen window, changes focus or moves the active workspace. Documented adapters and acceptance evidence for GNOME, KDE, Sway and X11               |
| Other desktop systems  | Signed releases, onboarding for permissions, appropriate native recording indicator, reliable insertion, tray/menu-bar controls, suspend/resume and microphone changes                                                              |
| iOS application        | A real containing app and keyboard extension, local and cloud options, model/key onboarding, transcript recovery and statistics. Honest handling of microphone and keyboard restrictions, with physical-device and App Review gates |
| Android application    | A native input method with voice entry, direct editor insertion, ordinary keyboard switching, local/cloud choices, lifecycle recovery and visible microphone use                                                                    |
| Acoustic processing    | Device selection and level checks, resampling, VAD, silence handling, optional denoising when it improves measured recognition                                                                                                      |
| Transcript cleanup     | Remove incidental fillers, punctuation and grammar errors; apply explicit self-corrections; preserve facts, negations, names, code, language and tone. Raw text remains recoverable for the configured retention period             |
| Provider choice        | Local STT and cleanup; direct BYOK STT and cleanup; an optional managed service with no provider key setup. Each stage can be chosen separately                                                                                     |
| Privacy                | No account needed for local/BYOK. Explicit consent for cloud audio, cloud transcript and app context. No automatic change from local to cloud                                                                                       |
| Vocabulary and context | User dictionary with exact spellings, per-app profiles, bounded optional selected/surrounding text, sensitive-app exclusions. No general screen/document scraping by default                                                        |
| Statistics             | Speaking WPM, output volume, recording time, activity by week, previous-period comparison, provider usage, costs and pipeline latency; sync optional                                                                                |
| Cost control           | Estimated cost before enabling a provider, monthly budgets and hard managed limits, separate STT/cleanup costs, usage reconciliation and a visible balance                                                                          |
| Recovery               | Lost focus, denied permissions, API timeout, revoked key, empty STT, failed cleanup, app crash and model download interruption all preserve a useful next step                                                                      |

## Product decisions for the first implementation

1. Use Handy as the desktop starting candidate and isolate platform adapters. Keep the inference layer replaceable. Confirm the Linux overlay and insertion spike before investing in a redesign.
2. Use native Swift/SwiftUI for the iOS containing app and keyboard, and Kotlin/Compose plus Android IME APIs for Android. Share contracts and fixtures first. Share Rust through FFI only where measured benefit justifies it.
3. Offer local mode without subscription or cloud dependence. Show BYOK as a separate choice. Introduce the managed service after the ledger and spend controls work.
4. Default to conservative cleanup. Rewriting style, composing replies and executing commands are separate future features. Never execute dictated shell commands or press Enter automatically.
5. Store usage metadata locally by default. Text history and audio retention have separate controls. Do not build account sync into the first local pipeline.

These are recommendations for implementation, not a claim that all source projects already meet them. The project name, public license, commercial prices and supported minimum device specifications remain undecided.

## Scope boundaries

This pass creates research, configuration examples, reference checkouts, a price model, evaluation fixtures and a delivery plan. It does not deliver runnable client applications. Meetings, diarization, voice agents, calendar actions, an extension marketplace and team administration are outside the initial product. A hosted transcription/cleanup service remains in scope, but deployment and payments are later work.

Equal polish means consistent quality and recovery adapted to each OS. It cannot mean identical system privileges. In particular, iOS secure fields and apps that reject third-party keyboards cannot be promised universal custom-keyboard dictation. See [platform evidence](research/platforms.md).
