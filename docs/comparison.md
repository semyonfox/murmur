# Repository comparison and decision

Research snapshot: 26 September 2026. Read the source audits linked below for exact files and immutable source links. Platform entries describe inspected implementation, not Murmur's tested support. A repository listing a target is not proof of a polished release on it.

| Repository | Main implementation | Platform evidence | Cleanup and useful features | Reuse decision |
| --- | --- | --- | --- | --- |
| Handy | Tauri, Rust, React | Linux, Windows, macOS code and packaging | Local multi-engine STT, optional provider cleanup, history, model management, native overlay/paste work | MIT. First desktop fork candidate; Linux reliability must pass a dedicated gate |
| OpenWhispr | Electron desktop; Expo plus native iOS modules | Desktop trio; substantial iOS keyboard; Android parity missing | Broad providers, local model lifecycle, vocabulary, analytics, hosted-service client | MIT. Product/provider/iOS reference. Wayland defaults to XWayland; local mobile STT bypasses cleanup |
| Flow | Tauri, Rust, macOS APIs | macOS-specific | Small local/Groq STT → local/Groq cleanup → paste pipeline; weighted WPM and activity stats | MIT. Borrow pipeline/evaluation patterns; replace plaintext key storage and timed text-only clipboard restoration |
| FreeFlow | Swift, AppKit/SwiftUI | macOS-specific | Context-aware cleanup, correction handling, dictation versus editing distinction | MIT. Focused cleanup reference; review key storage and destructive history recovery before reuse |
| Yap | Go daemon and library | Linux implementation | Transcribe/transform/inject contracts, local Whisper subprocess, Groq/custom APIs, terminal strategies | MIT. Architecture reference. Name resolved to Enriquefft/yap; other projects named Yap were not substituted silently |
| Whispering / Epicenter | TypeScript/Svelte, Tauri native host, server packages | Desktop/web; no verified native mobile keyboard path | Separation of raw/polished output, local/provider/managed routes, hosted billing | AGPL-3.0-or-later at current pin. Study design; decide copyleft before copying code. STT credit gate permits overspend |
| whispers | Rust, direct Wayland overlay | Wayland/Linux | Local/cloud recognition, rewrite worker, conservative output checks, model CLI | MIT plus bundled notices. Strong native Linux reference; `/dev/uinput` and paste assumptions require changes |
| VoiceInk | SwiftUI/AppKit | macOS-specific | Whisper/FluidAudio/transcribe.cpp/Apple engines, local/cloud cleanup, separate usage metrics | GPLv3. Design/model reference until license decision; Voxtral in this pin is cloud-backed |
| Voxtype | Rust daemon and native/GTK OSD | Linux-centric with macOS output paths | No-focus layer-shell overlay, input fallback chain, external cleanup, several STT engines | MIT. Strong additional Linux donor candidate; not a five-platform product base |
| Dictus iOS | Swift app, core and keyboard | Native iOS | Host-owned recording, local STT, polish safeguards, history | MIT. Selected native reference; exclude private UIKit discovery/swizzling and prove public-API handoff |
| VocaPhone | Swift iOS, Kotlin Android | Native mobile implementations | Local recognition, gateway client, deterministic repair, WPM/streaks | AGPLv3. Mobile design reference; full LLM cleanup and longer trend retention still needed |
| FUTO Voice Input | Kotlin/JNI | Android activity and IME | Local Whisper, VAD, partial and final editor text | Source First 1.0. Reference only for the planned commercial product |
| FUTO Keyboard | Kotlin/Java/JNI, LatinIME ancestry | Android keyboard | Integrated voice action, context-aware spacing, dictionary/model handling | Source First 1.1-kb. Reference only; separately licensed ancestry does not license the whole application permissively |

Evidence: [Handy, Flow, Yap, whispers](research/core-repositories.md), [OpenWhispr, FreeFlow, Epicenter, VoiceInk](research/product-repositories.md), [five additional repositories](research/additional-repositories.md).

## Recommendation

Choose Handy for the first desktop integration spike. It is smaller than OpenWhispr and already uses the intended Rust/Tauri stack with native audio/model/platform paths. Treat its Linux failure modes as work to resolve, not proof that its foundation is unsuitable. Test the actual fork before doing a broad UI rewrite.

Use Flow and FreeFlow to define and evaluate faithful cleanup. Use OpenWhispr for provider capabilities, delivery outcomes, independent usage events and selected iOS transport ideas. Use whispers and Voxtype for native Wayland mechanics. Study Epicenter and VoiceInk without importing their covered implementation into a differently licensed product. Build native mobile interaction paths instead of trying to stretch a desktop window into a keyboard.

No single fork meets the complete requirement. Starting from the largest repository would inherit notes, meetings, assistant features and sync work while leaving the same iOS and Linux constraints unresolved. The smallest useful plan is a focused desktop fork, two native mobile clients, shared behavior contracts and an optional managed service.

## Other technologies considered

[The model/runtime study](research/models-and-cleanup.md) additionally covers whisper.cpp, faster-whisper, CTranslate2, sherpa-onnx, WhisperKit/Argmax, Moonshine, Parakeet, Voxtral, transcribe.cpp, transcribe-rs, Silero VAD, RNNoise, Ollama and llama.cpp. These were reviewed through primary documentation and model cards; they are not all cloned as application references.

The names Flow, Yap and VoiceInk are not unique. This workspace records the selected owner/repository for each. Whispering resolves to its current Epicenter home rather than keeping a second clone of a redirect. FUTO Keyboard was added because the standalone Voice Input project points development there. VocaPhone's gateway submodule and sibling desktop projects were not recursively fetched or audited. These coverage limits are explicit so the report can be extended without pretending to have audited every related repository.
