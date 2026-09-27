# Delivery plan and acceptance gates

The five platforms remain required. Staging the work lets the highest-risk native behavior fail early, before a large shared UI or paid service depends on it. These are work packages, not calendar estimates. Isolated Linux inference and Hyprland settings-window geometry are recorded in the [smoke test](performance-smoke.md). Android now has a [voice popup on Android 13+ and an optional keyboard on Android 8–12](../apps/android/README.md). The popup's cross-app behavior has not been tested on a physical device. The platform interaction tests below remain pending.

## 1. Prove the platform interactions

Make two early spikes: Handy-derived Linux capture/overlay/delivery, and a public-API iOS recorder/keyboard handoff. Also build minimal Windows/macOS insertion and verify both Android popup and keyboard flows so architectural decisions reflect every platform.

For Linux, test CachyOS/Hyprland first, then Sway, GNOME Wayland, KDE Wayland and an X11 session. Use native Wayland and XWayland target apps. Acceptance includes no tiled overlay, no focus theft, no workspace switch, correct monitor placement, fractional scaling, fullscreen, keyboard-layout changes, modifier release, terminal paste, suspend/resume and clipboard changes during processing. Any silent insertion into the wrong field is a release blocker.

For iOS, use a physical iPhone, public APIs, a real containing app and keyboard. Test first launch, microphone denial/revocation, Full Access off/on, app termination, app switching, locked device, interruptions, ordinary text fields, secure fields and apps that reject keyboards. Record the actual user steps and whether the handoff is acceptable. Use an early reviewable build to establish App Store viability before committing to a background-session design. Simulator success is insufficient.

Android needs a physical Pixel-class device and a Samsung device, with current target-SDK behavior. On Android 13+, keep Gboard selected and test the accessibility popup in ordinary text fields across Chrome/WebView, messaging, native editors, split-screen and floating keyboards. Verify that its input connection inserts at the cursor, preserves composition and existing text, and never inserts after the editor, app or field changes during recording or transcription. The popup must disappear for password, numeric, phone and other sensitive fields. Test keyboard-height placement, cancellation, microphone and Accessibility permission revocation, battery optimization, calls/Bluetooth, process death and return from Android settings. Start the microphone foreground service while Murmur is visible, then test recording after switching apps, its notification and Stop action, and what happens when the service is stopped by the system. Test the optional Murmur IME separately, including IME switching and stale `InputConnection` recovery. On sideloaded Android 13+ devices, verify the restricted-settings path needed to enable Accessibility; also establish whether the Accessibility API declaration passes store review. None of these physical-device gates is complete yet.

If the iOS public-API interaction cannot meet the user's bar, bring that product trade-off back before presenting the app as a complete Wispr Flow substitute. Private APIs are not a solution to that gate.

## 2. Ship one dependable pipeline

Complete local STT, conservative cleanup, vocabulary, raw recovery, model download integrity and native credential storage. Support a deliberate BYOK route for STT and cleanup. Benchmark representative devices and accents before choosing defaults. Begin with the [cleanup fixtures](../evals/cleanup.jsonl), then record a consented private corpus of real speech and noisy samples.

Proposed release targets, not measured performance: recording feedback p95 within 150 ms; warm post-stop latency p95 within 2 seconds for a 10-second clip on a declared reference desktop and network; cancellation feedback within 250 ms; zero known meaning-changing failures in the protected regression corpus. A slower local mode is acceptable only with a clear device/model expectation. Measure cold load, peak RAM/VRAM, model download size, thermal behavior and battery separately.

Do not use one average latency to claim parity across CPU-only laptops, gaming GPUs and phones. Track results by OS, hardware, model revision, quantization, audio length and network. Store no private recordings in the public repository.

## 3. Complete the product clients

Add first-run mode selection, microphone check, permissions repair, native indicator states, accessible settings, searchable history, profile/vocabulary tools, durable weekly statistics, local spend estimates, archive import/restore and update handling. Android's popup and keyboard currently insert raw STT output; cleanup, dictionary, history, statistics and cost controls remain to be ported. The desktop has a dictionary, a dedicated Stats page, retained-history statistics, an OpenRouter current-key usage view and a lossless export. Native QA and a per-dictation cost ledger remain; the archive is not yet an in-app compressed storage format. Mobile clients must complete this gate too; a desktop release does not make the five-platform goal complete.

Add a focused audio-file import that can use the selected STT route and preserve timestamps before considering meeting capture. A true pause/resume media option should remember whether Murmur paused playback; the existing mute toggle only silences the output while playback advances. Calendar and Meet integration can follow once meeting permissions, retention and UI boundaries are defined. [OpenWhispr follow-up](research/product-repositories.md#follow-up-product-features).

Measure success on a declared app matrix: browsers and contenteditable forms, Electron editors/chat, office editors, terminals, native editors, password exclusions and accessibility tools. Include dark/light themes, high contrast, reduced motion, keyboard navigation, screen readers and large text. Do not copy Wispr branding, assets or marketing text.

## 4. Add the managed service

Implement authentication, provider allowlisting, validated audio limits, idempotency, atomic reservations, settlement, usage receipts, budgets, concurrency/rate limits and deletion. Run failure tests for provider timeouts after acceptance, duplicate requests, simultaneous sessions, partial STT/cleanup success, refunds, exhausted balances and price changes.

Compare prepaid packs and a small subscription with a clear allowance. Model payment fees and support costs before deciding a markup. Obtain the applicable provider contract and current store-payment rules before public sale. Do not assume an OpenAI-compatible endpoint grants resale rights. Keep direct local/BYOK operation available when the managed service is down.

## 5. Release and maintain

Build signed/notarized desktop installers on each target OS and signed mobile packages. Test clean install, upgrades, uninstall, migrated settings, locked credential stores, interrupted model downloads and recovery after an inference-worker crash. Verify updater signatures and reject invalid artifacts. Linux packaging must prove its GTK/layer-shell/runtime dependencies on a clean machine; Flatpak portal behavior is its own test target.

Before public release, complete per-file/dependency/model licensing records, SBOM generation, trademark/name review and store privacy declarations. Android also needs an Accessibility API disclosure/declaration and a tested install path: sideloaded builds can require a restricted-settings opt-in before the popup can be enabled. Hosted mode needs provider retention/region decisions and account deletion. Test real store builds; debug builds can hide entitlement and background-execution differences.

## Evidence to record

Each result records date, git revision, OS/build, compositor and protocol capabilities, hardware, model/hash, target app/version, action, expected/observed outcome and logs or a short screen recording with private content removed. Automated tests cover parser boundaries, ledger behavior, session transitions and cleanup invariants. GUI/native QA covers focus, permissions, paste and lifecycle behavior that unit tests cannot establish.

The next Linux ticket is a live Hyprland recording-indicator and destination-safe insertion test. The Android popup needs the Pixel/Samsung device matrix above before it can be called dependable. The iOS public-API keyboard handoff is the next platform spike. The managed billing service follows those native interaction gates.
