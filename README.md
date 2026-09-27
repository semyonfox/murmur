# Murmur

Murmur is a Handy-based desktop dictation MVP with local speech recognition, bring-your-own-key endpoints and optional transcript cleanup. The intended product covers Linux, Windows, macOS, iOS and Android. The desktop app and an initial native Android voice bubble with a keyboard fallback are implemented; iOS and the managed service remain future work. `Murmur` is a temporary name, with no name or trademark clearance.

Start with the [desktop setup guide](apps/desktop/README.md). The frontend and native Linux host build on the CachyOS PC. A native-Wayland AppImage passed synthetic local inference on Vulkan and CPU; a global shortcut started microphone capture and showed a layer-shell recording indicator on Hyprland. The settings window stayed at 680×570. The 27 September build adds a modifier-release guard before Linux insertion and is running on the PC. Text insertion and full live dictation still need physical QA, so this is not yet a verified daily replacement for Wispr Flow.

## Desktop MVP

The application uses Tauri 2, Rust and React. Its current source includes:

- Local speech recognition with model selection, compatible existing-cache reuse and downloads on request. Local failures do not automatically switch to cloud recognition.
- An OpenAI-compatible speech endpoint with a configurable model and URL. Its optional API key is saved in the operating system credential store for that URL; a local server can work without a key.
- Optional transcript cleanup through a local Ollama server or a selected cloud provider. One dictation shortcut follows the cleanup on/off setting. Five formality levels guide tone; longer rambles can be lightly reorganized and repeated thoughts removed without intentionally dropping substantive points. Cleanup keys use the native credential store. Failed cleanup falls back to the raw transcription.
- Global shortcuts, microphone selection, text insertion and recording indicators. Native Wayland uses an evdev shortcut listener; on Hyprland, the settings window opens as a compact floating window and the recording indicator uses GTK layer shell. If layer shell is unavailable, Murmur suppresses the overlay rather than opening a tiled normal window.
- A visible dictionary for unusual names and terms, plus local transcript/audio history, recorded WPM by week, weekly word counts and week-over-week activity. Fresh installations keep history until the user deletes it or chooses a shorter retention period. History can be exported with lossless compression; statistics depend on retained history and available audio.

Speech recognition and cleanup are separate choices. Local recognition with cloud cleanup sends text to the cleanup provider. Endpoint recognition sends audio to the chosen endpoint. The app has no managed accounts, payment flow, provider spend cap or actual-cost dashboard. Transcript cleanup edits text; it does not perform acoustic noise suppression.

## Run the desktop

Use Node.js 24+, pnpm, stable Rust and the native prerequisites listed in the [desktop guide](apps/desktop/README.md). With those dependencies installed, run from the repository root:

```sh
cd apps/desktop
pnpm install --ignore-scripts
pnpm tauri dev
```

`pnpm tauri dev` starts the frontend and native host. `pnpm run dev` starts only the browser UI. `pnpm run build` checks TypeScript and creates the frontend production bundle; it does not build a native application.

On CachyOS/Hyprland, `pnpm run bundle:linux-wayland` builds the tested AppImage. The [desktop guide](apps/desktop/README.md) explains its packaging workaround and compatibility limits.

The desktop guide covers selecting a local model or endpoint, configuring Ollama or cloud cleanup, shortcuts, Linux insertion helpers, retention and native builds. No personal API keys or recordings belong in this repository.

## What has been verified

| Area               | Evidence and limits                                                                                                                                                                                                                                                                                                                                                              |
| ------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Frontend           | Dependency installation and production build passed on Ubuntu and CachyOS. ESLint, translations for all 25 locales and model-language checks passed                                                                                                                                                                                                                              |
| Rust               | `cargo check --locked`, `cargo build --locked`, a production `tauri build --no-bundle`, and all 325 library tests passed on the CachyOS PC. The endpoint adapter also passed seven isolated tests on Ubuntu                                                                                                                                                                      |
| Native application | The revised Wayland AppImage built and runs on the PC without a development server. Its startup log confirms Enigo and the nonblocking Wayland shortcut manager initialized. An earlier Hyprland check found a centered, floating 680×570 settings window. Cross-distribution portability remains untested                                                                       |
| Desktop behaviour  | Bundled Whisper Tiny transcribed a synthetic WAV on Vulkan and CPU. A synthetic key press started microphone capture and showed a layer-shell indicator, which disappeared on key release. Text insertion, actual spoken dictation and native credential-store transactions remain unverified                                                                                    |
| Other platforms    | The Android bubble and keyboard fallback built a debug APK; three JVM tests and lint passed. An earlier Android 14 emulator run verified the popup, microphone recording, mock STT insertion and password-field exclusion. The new local recognition, cleanup, noise suppression, dictionary and history paths need device tests. No Windows/macOS native build, iOS app, signed installer or store submission has been completed |

The [Linux smoke record](docs/performance-smoke.md) gives the inference setup, timings and native-Wayland shortcut result. Windows and macOS retain the upstream native integrations and need validation in this fork. The [roadmap](docs/roadmap.md) defines the physical-device and application tests required before release.

## Design and research

The research covers all eight requested projects and five additional repositories. Design documents describe the full intended product, including behaviour beyond the current MVP. The [desktop guide](apps/desktop/README.md) describes the implementation as it stands.

| Document                                            | Contents                                                                                             |
| --------------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| [Comparison and recommendation](docs/comparison.md) | Repository comparison and reasons for the Handy desktop base                                         |
| [Requirements](docs/requirements.md)                | Five-platform scope and acceptance criteria                                                          |
| [Architecture](docs/architecture.md)                | Native clients, processing stages, provider boundaries and proposed managed service                  |
| [Roadmap](docs/roadmap.md)                          | Remaining implementation work, device tests and release gates                                        |
| [Statistics and data](docs/data-and-stats.md)       | Proposed durable activity statistics, retention, privacy and cost accounting                         |
| [Licensing and reuse](docs/licensing-and-reuse.md)  | Recorded Handy import, retained notices, replaced branding and unresolved redistribution obligations |

Detailed audits cover [core repositories](docs/research/core-repositories.md), [product repositories](docs/research/product-repositories.md), [additional repositories](docs/research/additional-repositories.md), [platform constraints](docs/research/platforms.md), [models and cleanup](docs/research/models-and-cleanup.md), and [costs and commercial service](docs/research/costs-and-service.md). Source claims link to pinned code or primary documentation. The single synthetic inference smoke does not establish representative model quality or end-to-end performance.

The mobile approach uses a Swift iOS recorder and keyboard extension, plus a Kotlin Android app. The [Android prototype](apps/android/README.md) has a compact dictation bubble beside the user's existing keyboard on Android 13+, with a keyboard fallback on older phones. It offers a configured speech endpoint or the phone's installed on-device recognizer, followed by optional separate transcript cleanup. It also has a preferred-spelling dictionary, text history, retained-history word and pace statistics, and optional device noise suppression, and endpoint-based lecture recording and audio-file transcription. Lecture audio stays on the phone for retry and sharing. It defaults to [OpenRouter Whisper Large V3 Turbo](https://openrouter.ai/openai/whisper-large-v3-turbo/api) for hosted STT. Model downloads, embedded cleanup and provider cost statistics remain future work, and the new paths need physical-device testing. The [Android design and checks](docs/android.md) track those gaps. The proposed managed service would let customers use hosted transcription and cleanup without supplying keys, with usage accounting and spending controls. The iOS client and service do not exist yet.

## Research workspace

The 13 pinned source references are under `research/upstream/`, excluded from Git. [repositories.json](research/repositories.json) records their identities; [sources.lock.json](research/sources.lock.json) records exact revisions. Checkouts are shallow and exclude submodule contents and LFS objects. The desktop is a separate copied and adapted Handy application under `apps/desktop/`; the research checkouts remain references.

Python 3.10+ and Git suffice for the research tools. They use the Python standard library. Reproduce the locked references and check the workspace:

```sh
python3 scripts/fetch_references.py
python3 scripts/verify_workspace.py --write-report
```

Fetching preserves the locked revisions and refuses to overwrite modified reference checkouts. Verification checks reference revisions, clean trees, source paths, document links, JSON fixtures and calculator tests. It does not run the desktop or upstream applications. Its latest saved report is [verification.json](research/verification.json).

[product.example.json](config/product.example.json) contains proposed product settings and separate stage policies. It does not configure the desktop. The [cleanup fixtures](evals/README.md) contain 23 synthetic cases; the [benchmark](docs/cleanup-benchmark.md) records 230 OpenRouter requests across five models and two prompt versions.

## Cost calculator

The calculator estimates provider costs without making API calls:

```sh
python3 scripts/cost_model.py --minutes 300
python3 scripts/cost_model.py --minutes 300 --session-seconds 5
python3 scripts/cost_model.py --minutes 300 --stt openai-mini --cleanup openai-4.1-mini
python3 -m unittest discover -s scripts -p 'test_*.py' -v
```

At the 26 September 2026 list prices and documented token assumptions, 300 audio minutes in 30-second clips cost about $0.2945 for Groq Turbo STT plus GPT-OSS 20B cleanup. Five-second clips raise that estimate to $0.8545 through minimum audio charges and repeated cleanup overhead. These figures exclude retries, hosting, payments, tax, profit and other operating costs. They do not establish equivalent model quality. See the [assumptions and primary price sources](docs/research/costs-and-service.md).

Use `--sessions-json` with a JSON array of individual clip durations to model a real usage distribution. The [price snapshot](config/pricing.snapshot.json) is a planning input. The desktop does not yet connect these estimates to its history, and a production billing ledger remains to be built.

## License and provenance

The imported desktop code retains Handy's [MIT license and copyright notice](apps/desktop/LICENSE). The [licensing record](docs/licensing-and-reuse.md) identifies its exact source revision, local changes and remaining dependency, model and sound-asset checks. Murmur's app and tray icons have been replaced. Other research repositories retain their own licenses and are not blanket permission to copy their code.

There is no repository-wide public distribution license yet. The desktop's MIT metadata does not relicense its dependencies, model weights or the separate research references.
