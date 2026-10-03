# Murmur desktop

The desktop MVP is a Handy-derived Tauri 2 application with a Rust native host and React settings UI. It includes local or endpoint speech recognition, optional transcript cleanup, global shortcuts, text insertion, history and activity statistics.

The frontend and native Rust host build on the CachyOS PC. The AppImage transcribed synthetic audio on Vulkan and CPU, and a synthetic shortcut press started microphone capture and displayed a recording indicator on native Hyprland Wayland. Text insertion and full spoken dictation still need physical desktop verification. Windows and macOS builds and runtime behavior remain unverified. Android has a separate native app; iOS and the managed paid service do not exist yet. The broader [architecture](../../docs/architecture.md) describes planned work beyond this implementation.

## Build and run

Use Node.js 24+, pnpm, a current stable Rust toolchain and the native dependencies for your operating system. Start with the official [Tauri prerequisites](https://v2.tauri.app/start/prerequisites/) for Linux, Windows or macOS. Linux needs WebKitGTK 4.1, GTK, tray integration and a native compiler. Windows needs the MSVC C++ tools and WebView2. macOS needs Xcode or its Command Line Tools.

The audio, inference and credential-store dependencies in [Cargo.toml](src-tauri/Cargo.toml) require more than a minimal Tauri installation:

| Platform       | Additional requirements                                                                                                                                                                      |
| -------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Linux          | CMake, Clang/libclang, pkg-config, ALSA, libevdev, OpenSSL, D-Bus and GTK layer-shell development files; Vulkan headers and the `glslc` shader compiler for the configured inference backend |
| Windows x86-64 | CMake and the Vulkan SDK, including `glslc`, available to the MSVC build environment                                                                                                         |
| macOS          | CMake; Metal is the configured inference backend. Intel Mac ONNX builds may need a separately installed ONNX Runtime and `ORT_LIB_LOCATION`/`ORT_PREFER_DYNAMIC_LINK` configuration          |

For example, the Linux additions include `libasound2-dev`, `libdbus-1-dev`, `libgtk-layer-shell-dev`, `libevdev-dev`, `libclang-dev`, `libvulkan-dev`, SPIR-V headers and `glslc` on Debian/Ubuntu. On Arch/CachyOS, the corresponding packages include `alsa-lib`, `dbus`, `gtk-layer-shell`, `libevdev`, `clang`, `vulkan-devel`, `spirv-headers` and `shaderc`. These names supplement the Tauri prerequisites; no complete distribution installation recipe has been tested here. GPU drivers are also needed to use GPU inference at runtime.

From the project root:

```sh
cd apps/desktop
pnpm install --ignore-scripts
pnpm tauri dev
```

`pnpm tauri dev` starts both Vite and the Rust host. Running `pnpm run dev` alone serves the browser UI without native microphone, shortcut, model or storage commands. First builds need network access for dependencies and native runtime artifacts.

Build the frontend or compile a native release without creating an installer:

```sh
pnpm run build
pnpm tauri build --no-bundle
```

The second command still requires all native prerequisites. It passed on the CachyOS PC, but a compiled executable alone is not a portable distribution.

For a Linux AppImage that preserves native Wayland on Hyprland, run:

```sh
pnpm run bundle:linux-wayland
```

This command uses `NO_STRIP=1` because the bundled `linuxdeploy` strip tool cannot read `.relr.dyn` sections in CachyOS libraries, then [repackages the AppImage](scripts/repack-wayland-appimage.sh) without the GTK hook that forces XWayland. The [Cargo configuration](.cargo/config.toml) disables the system BLAS link for the transcription decoder on native builds; that link left an unresolved CBLAS symbol in an earlier AppImage. The repack script checks for that symbol. It creates `src-tauri/target/release/bundle/appimage/Murmur_0.1.0_wayland_amd64.AppImage` for this version and architecture. The [26 September smoke build](../../docs/performance-smoke.md) launched on CachyOS/Hyprland with `xwayland: false` and ran bundled Vulkan and CPU inference. The 27 September cleanup revision packaged successfully and passed Rust tests but has not been launched. Model weights are still downloaded on request. Builds from a rolling distribution may depend on newer system libraries; this file has not passed clean-machine or older-distribution compatibility tests. Signing and update handling are also unverified. The `NO_STRIP` setting is supported by [linuxdeploy](https://github.com/linuxdeploy/linuxdeploy/issues/72).

## Settings layout

The sidebar follows the dictation flow. Dictation covers the shortcut, microphone, spoken language, recording indicator and sounds. Lectures records from the microphone or imports an audio file, then transcribes it into History. Models chooses where speech is recognised and lists every saved API key. Cleanup holds filler-word removal, the AI cleanup provider, its key and model, tone and custom instructions. Dictionary, Text insertion, History, Privacy, App and About follow. Privacy is a read-only summary of what leaves the computer and what is stored, with links to the page that controls each item.

Lecture capture writes a 16 kHz mono WAV in the recordings directory and checkpoints it every five seconds. Finishing the recording automatically transcribes it in two-minute chunks using the selected speech provider, then applies the selected cleanup to each chunk. Import accepts WAV, MP3, M4A/MP4, FLAC and Ogg audio and converts it to the same WAV format. A long transcription retains completed raw chunks as it works; History can retry from the saved audio after a failure. Lecture audio and text follow the same retention and export rules as other History entries. These sessions do not insert text into another app or count toward dictation pace statistics. The timestamps mark chunk starts, not individual words. Cloud endpoint mode sends each chunk to the configured service and may incur provider charges. This path has not yet been tested with a physical lecture recording.

## Choose speech recognition

Open Models, choose On this device or Cloud API or your own server under Speech recognition, and select Save. You can choose a cloud service before downloading any local speech model.

### On this device

Local recognition is the default. Select a model under On-device models, or browse the catalog there to download one. Speech model weights are not bundled with the application. The small bundled voice activity detector does not replace a speech model.

The model manager scans Murmur's app-data `models/` directory for compatible local files and can reuse supported models in the shared Hugging Face cache. That cache follows `HF_HOME`, with `~/.cache/huggingface/hub` as its usual location. Existing cache support depends on the model format and catalog entry; an arbitrary model directory is not guaranteed to work. Models listed for download use the bundled catalog.

On Linux, a compatible Whisper-family `.bin` or `.gguf` file outside that cache can be linked into the app-data `models/` directory, then found with Models → Rescan. Use the app-data directory control to locate the directory. The link reuses the existing file rather than downloading another copy; the model format and architecture still need to be supported by the native engine.

Downloads happen on request. Once a compatible model is available, local speech recognition runs on the device and does not switch to a cloud endpoint if it fails. Cloud cleanup is a separate choice and can still send the resulting transcript off-device.

### Cloud API or your own server

Choose Cloud API or your own server. The OpenAI, Groq and OpenRouter buttons fill in the base URL and a speech model; for anything else, enter the API base URL and the model ID that service accepts. Paste the key if it needs one, then select Save. Examples of URL shapes are:

```text
http://127.0.0.1:8000/v1
https://api.groq.com/openai/v1
https://openrouter.ai/api/v1
```

A complete URL ending in `/audio/transcriptions` also works. The adapter sends the finished WAV recording as a multipart request to an OpenAI-compatible transcription endpoint and expects a JSON object containing a nonempty `text` string. It does not stream audio to the endpoint while recording.

For hosted STT through OpenRouter, use `https://openrouter.ai/api/v1` and model `openai/whisper-large-v3-turbo`, then save an OpenRouter key for this speech endpoint. This is the provisional low-cost hosted default for Android; it is separate from the cleanup model and can be changed without rebuilding. OpenRouter accepts this multipart request and reports usage in its response, though the current desktop adapter reads only the transcript. BYOK charges need reconciliation against the upstream provider's bill. [OpenRouter's STT guide](https://openrouter.ai/docs/guides/overview/multimodal/stt) documents the request and model discovery. A SiliconFlow key configured within OpenRouter only applies when that provider serves the chosen STT model; the current Whisper Large V3 Turbo listing names DeepInfra and Groq as its providers. [Model listing](https://openrouter.ai/openai/whisper-large-v3-turbo/api).

An existing local server can be reused when it exposes this transcription endpoint. Configuring an Ollama model for text cleanup alone does not provide speech recognition to Murmur. Murmur's desktop executable is not a headless speech server; even its no-window test commands currently initialize GTK. Use a separate compatible speech service on a server without a desktop session.

If the endpoint requires authentication, enter its key in the API key field. Save stores it with a new URL and model; Save key replaces the key for an endpoint that is already saved. Murmur stores it in the operating system credential store, scoped to the exact configured URL, and never in its settings file. Changing URLs does not reuse the previous endpoint's key. The field reports whether a key is stored without revealing the saved value, and Models → Saved API keys lists every stored speech and cleanup key with a Remove action. On Linux, this requires an accessible Secret Service over the session D-Bus. A keyless local server needs no key.

For a launcher that supplies secrets through its environment, set both `MURMUR_STT_API_BASE_URL` to the configured endpoint URL and `MURMUR_STT_API_KEY` to its key. Murmur uses this fallback only when those URLs match and no stored key is available. The settings field reports stored keys only; removing one does not unset a launcher-supplied key. Restart after changing the launch environment. Keep keys out of repository files.

Remote endpoints must use HTTPS. Plain HTTP is accepted only for `localhost` or a loopback IP address. Credentials, query strings and fragments in the URL are rejected, and redirects are disabled. Requests have a 25 MB WAV limit and a 180-second timeout. Provider-specific limits can be lower.

Endpoint mode sends recordings to the configured service, which may charge for them. Murmur does not automatically switch speech providers. This client has no provider spend cap or billing reconciliation yet.

## Set up transcript cleanup

Cleanup is off by default. It is a separate text-processing step after speech recognition. The default prompt asks the selected model to remove empty filler, false starts and accidental repetition, correct clear spelling and grammar errors, and split long dictation into readable sentences while preserving the speaker's words and substantive points. It explicitly rejects synonym swaps such as “with a bit of” to “with a touch of.” Five formality levels chiefly adjust punctuation, sentence breaks and clear grammar; Natural is the default. The Dictionary page helps local recognition and supplies preferred spellings to cleanup. It does not perform acoustic noise suppression. Model output still needs evaluation for omissions and changes in meaning.

For local cleanup:

1. Run Ollama separately and make a suitable text model available in that service.
2. Open the cleanup settings and enable cleanup.
3. Choose Ollama, with its default base URL `http://127.0.0.1:11434/v1`.
4. Refresh the model list and choose your installed model.
5. Keep the default Improve Transcriptions prompt initially, or edit a prompt containing the `${output}` transcript placeholder.

Murmur does not install Ollama, start its server or download its text models. The Ollama provider works without an API key. A local speech model and a local Ollama server keep both processing stages on your device.

For cloud cleanup, select the provider, enter its key in the cleanup settings and choose a model. For the tested SiliconFlow BYOK route, select **OpenRouter** in Murmur and configure the SiliconFlow key, provider order and failover in OpenRouter. `qwen/qwen3.5-35b-a3b` is the [provisional cleanup candidate](../../docs/cleanup-benchmark.md), with a known ambiguous-number error; it is not preselected. The separate SiliconFlow provider calls SiliconFlow directly only when selected. Cleanup keys use the operating system credential store. On Linux this requires an accessible Secret Service over the session D-Bus; a locked or unavailable store produces an error instead of a plaintext fallback. The settings UI reports whether a key is stored and provides Remove key without reading the saved secret back into the field.

Cloud cleanup sends the transcript and cleanup prompt to that provider, even when speech recognition runs locally. It has separate credentials and charges from the speech endpoint. If cleanup fails, the pipeline retains the raw transcription as its output. Raw and processed text remain available in history while the entry is retained.

Cleanup treats ellipses caused by thinking pauses as recognition artifacts and chooses ordinary punctuation from the sentence meaning. Explicitly dictated ellipses and clear trailing-off intent remain allowed. Responses marked as truncated by the provider fall back to raw text rather than inserting an incomplete cleanup result.

The cleanup policy asks the model to apply clear spoken corrections to the final stated wording. For example, "send it Tuesday, oh no Thursday, before lunch" becomes "Send it Thursday before lunch." It preserves genuine alternatives, uncertainty and quoted wording.

Before the model runs, cleanup locally resolves an immediately preceding word followed by "spelled" or "spelt" and clear hyphenated ASCII letters or uppercase spaced letters. For example, "my name is Simion, spelt S-E-M-Y-O-N" becomes "my name is Semyon" before sentence cleanup. A comma or dash marks an explicit replacement; without one, the guess must resemble the spelled word. This keeps ordinary phrases such as "John spelled C-A-T for the class" intact for the model. If the model drops or changes an explicit spelling, cleanup returns this prepared text. Quoted or literal transcripts, partial letter sequences and ambiguous spaced endings are left to the model. Semantic corrections still depend on the selected model. The original transcript remains available under the history retention setting; a provider failure retains that original text.

## Record and insert

The default shortcut behaviour supports both holding and toggling. Hold the shortcut while speaking and release to finish, or tap once to begin and again to finish. Settings also offer dedicated push-to-talk and toggle modes.

| Action  | Linux and Windows | macOS          |
| ------- | ----------------- | -------------- |
| Dictate | `Ctrl+Space`      | `Option+Space` |
| Cancel  | `Escape`          | `Escape`       |

The dictation shortcut is customisable. Turn cleanup on or off in Transcript cleanup; the same shortcut handles both modes. Older saved cleanup shortcuts are removed on upgrade without changing the main dictation binding. The legacy `--toggle-post-process` CLI flag now acts like `--toggle-transcription` and follows the cleanup setting.

Choose the microphone and insertion behaviour in settings. Allow the operating system's microphone access and any required accessibility/input permissions. Verify insertion in an ordinary text field before depending on it in your daily applications. History provides recovery and copying when automatic insertion fails.

With recording sounds enabled, Murmur warms the microphone while discarding its input, then plays the start cue. Capture opens after playback and a 60 ms settling interval; the ready indicator appears at that point. The stop cue waits until capture, including any configured extra recording buffer, has fully drained. Neither cue is included in saved audio or live recognition input.

### Linux and Hyprland

On Wayland, Murmur selects the `handy-keys` evdev shortcut listener automatically because the Tauri hotkey backend listens through X11. This requires permission to read the keyboard's `/dev/input/event*` device; without it, shortcuts may fail to initialize. The listener observes keys without grabbing them, so a shortcut may also activate an action in the foreground application. Choose a binding that does not conflict with your common apps. On supported Wayland compositors, the recording indicator uses GTK layer shell with no keyboard focus or reserved screen area. The compact indicator is 256 by 50 logical pixels. New installs default to the Live style, which uses this compact pill for endpoint recognition and expands only for streaming local models. Existing saved indicator settings remain intact. If the Wayland session lacks working layer-shell support, Murmur suppresses the recording overlay rather than opening a normal window that the compositor could tile. The tray remains available; enable recording sounds if you want them, which are off by default. Marimba, Pop and custom cues play through the desktop event-sound path via `canberra-gtk-play`, or `paplay` with the event role if Canberra is absent. Murmur uses the system's output and volume settings for these cues.

On Hyprland, the full settings window asks the compositor to float it at 680 by 570 pixels after it appears. This uses `hyprctl` for Murmur's own process through [Hyprland's dispatcher interface](https://wiki.hypr.land/Configuring/Basics/Dispatchers/); it does not change compositor configuration. Hyprland 0.56.2 was tested. Earlier versions use a separate dispatcher fallback and remain unverified. If `hyprctl` is unavailable, the compositor may still tile the settings window.

Text insertion and shortcuts have their own compositor requirements. The inherited integration can use `wl-copy` from `wl-clipboard` and typing helpers such as `wtype`, with other helpers for different sessions. Advanced settings expose paste and typing-tool choices. Some helpers require additional input permissions. No compositor configuration or input permissions are changed by this project setup.

The executable also accepts `--toggle-transcription`, `--toggle-post-process` and `--cancel` for commands sent to a running instance. These can be used for a manually configured compositor binding when the in-app shortcut backend is unsuitable.

Hyprland's client list reported the settings window as a floating 680×570 native Wayland surface. A synthetic press of the configured shortcut started the microphone stream and created a `gtk-layer-shell` indicator; releasing it removed the indicator without a crash. Fractional scaling, multiple monitors, focus handling, desktop portals, clipboard restoration and insertion into different toolkits still need real desktop testing. Windows and macOS native overlays and insertion also remain untested in this fork.

## History and statistics

History stores transcripts in `history.db` and WAV files in `recordings/` inside the application data directory. Use the app-data directory control in settings to locate it. Entries support playback, copying, retranscription, saving and deletion.

Fresh installations retain transcripts and WAV audio indefinitely until you delete them or change retention. Existing saved retention choices remain in effect. Saved entries are exempt from automatic deletion. The History page offers shorter periods or a count limit; its count starts at 1,000 for fresh installs and only applies when that mode is selected. Reducing retention can immediately delete older unsaved entries and reduce the history available for statistics, so the UI asks before applying it.

The History page can export retained entries to a `.tar.gz` archive on request. It contains a `manifest.json` with raw and cleaned text, prompt and recording metadata, plus the original WAV files that still exist. The manifest marks missing audio. Export is lossless and does not delete local history; it also does not encrypt the archive. A compressed archive can still be large because the audio stays as WAV. This is an export option, not in-app audio compression or automatic backup.

Statistics are calculated from retained history:

- Word totals use the processed transcript when present, otherwise the raw transcript.
- Recorded WPM uses raw transcript words divided by the duration of recordings whose WAV files still exist. Pauses count toward that duration.
- The week starts on Monday in local time. The percentage compares the current week so far with the entire previous week.
- The chart shows eight weeks of word counts and recorded WPM where audio is available.

Deleting history reduces the statistics. Missing audio still allows word counts but removes that entry from recorded WPM and audio duration. There is no separate permanent activity aggregate or account sync. The desktop does not yet show actual API costs; the repository's [cost calculator and service research](../../docs/research/costs-and-service.md) cover estimates and the proposed billing design.

## Verification status

During development on 26 September 2026, dependency installation with `pnpm install --ignore-scripts` and the TypeScript/Vite production build succeeded on Ubuntu and CachyOS. Frontend lint, formatting and translation checks also passed. On the CachyOS PC, `cargo check --locked`, `cargo build --locked`, a production `pnpm tauri build --no-bundle --ci`, and all 318 library tests passed. That AppImage transcribed a synthetic WAV through bundled Vulkan and CPU backends, and its shortcut triggered microphone capture and the recording indicator. On 27 September, the updated cleanup build passed all 319 Rust library tests, frontend lint and build, and AppImage packaging; the running PC app still uses the earlier build until it is relaunched. See the [Linux smoke record](../../docs/performance-smoke.md) for timings and limits.

The Ubuntu development host still cannot compile the native app because it lacks pkg-config and the required GTK, WebKitGTK, ALSA and D-Bus development files. The CachyOS PC supplied those native libraries. Its SPIR-V Headers were supplied in a user cache for the build, without changing system packages. The Ubuntu endpoint adapter passed seven isolated Rust tests; credential provider-ID and speech key URL-scoping tests passed with a temporary vendored D-Bus build configuration. No real credential-store transaction, in-app cloud provider call, spoken dictation or text insertion test has been completed.

Useful checks from this directory are:

```sh
pnpm run build
pnpm run lint
pnpm run format:check
pnpm run check:translations
pnpm run check:model-languages
pnpm run test:keyboard
cargo test --manifest-path src-tauri/Cargo.toml --locked
```

Physical desktop testing and native release builds are still required before calling this a reliable daily replacement. Mobile clients, managed accounts/payments, durable statistics, per-app context and provider cost controls remain separate implementation work.

## Upstream code

The desktop includes code from Handy under its retained [MIT license](LICENSE). The repository's [source lock](../../research/sources.lock.json) records the reference revision. Preserve upstream notices when redistributing or extending this code; see [licensing and reuse](../../docs/licensing-and-reuse.md) for the broader source audit. Some internal identifiers still reflect Handy's original implementation.

Normal installations use the stable application ID `ie.semyon.murmur`. Settings live in `settings_store.json` inside the operating system's application data directory, alongside history, recordings and downloaded models. Updating the application does not replace this directory. On first launch, an existing `dev.local.murmur` data directory is copied to the new location before settings or history load. The old directory remains as a backup. Portable installations continue to use their adjacent `Data/` directory.
