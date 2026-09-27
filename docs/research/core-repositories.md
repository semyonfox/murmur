# Core repository audit

Inspected 26 September 2026. These are source-level findings from pinned checkouts, not results from running the applications. The complete revisions, commit dates, and file counts are in [sources.lock.json](../../research/sources.lock.json). Repositories and names were resolved from the descriptions in the request. `Yap` means `Enriquefft/yap`; several unrelated projects share that name.

## Handy

Best starting point for the desktop application. The current source already has substantially more than a minimal recorder. It has separate audio/model/history managers, local transcription, optional model-based post-processing, a vocabulary path, shortcuts, and OS-specific paste handling. MIT at the pinned revision.

| Area | Source evidence | Implication |
| --- | --- | --- |
| Inference | [Cargo.toml](https://github.com/cjpais/Handy/blob/8f9cf53cd1410cda26beea39ff802ac306e39585/src-tauri/Cargo.toml) selects `transcribe-cpp` and ONNX `transcribe-rs`; Linux and Windows x64 use Vulkan and dynamic backends, macOS Metal, Windows ARM static CPU | Evaluate the existing adapters before building another Whisper wrapper. CPU instruction compatibility and DLL packaging already required upstream fixes |
| Overlay | [overlay.rs](https://github.com/cjpais/Handy/blob/8f9cf53cd1410cda26beea39ff802ac306e39585/src-tauri/src/overlay.rs) initializes GTK layer shell when supported, disables keyboard interactivity and sets exclusive zone zero | There is useful native work to extend. Tauri window flags alone are insufficient; unsupported-compositor behavior still needs proof |
| Injection | [clipboard.rs](https://github.com/cjpais/Handy/blob/8f9cf53cd1410cda26beea39ff802ac306e39585/src-tauri/src/clipboard.rs) distinguishes Wayland and X11 and avoids `wtype` on KDE/GNOME | Copy the capability reasoning, then test protocol support instead of trusting only desktop names |
| Clipboard restoration | [paste_tx](https://github.com/cjpais/Handy/tree/8f9cf53cd1410cda26beea39ff802ac306e39585/src-tauri/src/paste_tx) has separate macOS and Windows receipt-based paths | A fixed sleep before restoring the clipboard is not reliable under load |
| Cleanup | [actions.rs](https://github.com/cjpais/Handy/blob/8f9cf53cd1410cda26beea39ff802ac306e39585/src-tauri/src/actions.rs) and [settings.rs](https://github.com/cjpais/Handy/blob/8f9cf53cd1410cda26beea39ff802ac306e39585/src-tauri/src/settings.rs) select provider, model and prompt independently | Extend this path with separate privacy/cost policies and regression gates; do not treat every OpenAI-compatible API as identical |
| Recovery | [history.rs](https://github.com/cjpais/Handy/blob/8f9cf53cd1410cda26beea39ff802ac306e39585/src-tauri/src/managers/history.rs) stores raw and processed text and whether cleanup was requested | Useful recovery design, but statistics should survive text-history deletion only when the user elects to retain aggregate metrics |

The pinned [README Linux limitations](https://github.com/cjpais/Handy/blob/8f9cf53cd1410cda26beea39ff802ac306e39585/README.md) acknowledge focus theft, paste failures, compositor differences, and an overlay disabled by default. This prevents calling Handy a finished Linux solution. The native integration milestone is a condition of choosing the base, not optional polish after launch.

Fork candidate verdict: yes for desktop. Do not infer mobile support from Tauri's mobile targets or scattered conditional compilation. Keep the upstream license, remove upstream product identity and updater endpoints in the eventual product fork, and audit dependency/file notices before release.

## Flow

The clearest compact pipeline and a better statistics reference than the original shortlist suggests. MIT. It is specifically a macOS application, with unconditional CoreAudio/AppKit imports and a Metal Whisper dependency in [Cargo.toml](https://github.com/jgvilchezc/flow/blob/d106903ab14f837d05970fd937ad635433101172/src-tauri/Cargo.toml). Porting it is more work than using Handy for the desktop shell.

The [formatter](https://github.com/jgvilchezc/flow/blob/d106903ab14f837d05970fd937ad635433101172/src-tauri/src/format.rs) supports Ollama, Groq, and off; sends few-shot examples as actual message turns; retains a warm Ollama model; and falls back to raw text on errors, empty results, or rejected output. Prompts live in [separate resources](https://github.com/jgvilchezc/flow/tree/d106903ab14f837d05970fd937ad635433101172/src-tauri/prompts), shared with evaluation scripts. This is worth borrowing as a workflow. Its word-overlap guard cannot prove meaning preservation: removing a negation still leaves almost all vocabulary unchanged. One warning logs rejected formatted text, which should not become a default in Murmur.

The [stats module](https://github.com/jgvilchezc/flow/blob/d106903ab14f837d05970fd937ad635433101172/src-tauri/src/stats.rs) calculates WPM as total words divided by recorded time, with streaks, per-app totals, a heatmap and raw-to-formatted differences. Borrow weighted aggregation. Change the naming of differences: edits are not proof of errors corrected. Its SQL local-time bucketing needs a deliberate timezone policy for travel and sync.

Avoid copying [settings persistence](https://github.com/jgvilchezc/flow/blob/d106903ab14f837d05970fd937ad635433101172/src-tauri/src/settings.rs), which serializes the provider key to settings JSON. Use native credential storage. The [injection code](https://github.com/jgvilchezc/flow/blob/d106903ab14f837d05970fd937ad635433101172/src-tauri/src/inject.rs) saves text clipboard contents, pastes, waits 120 ms, then restores. That does not preserve all clipboard formats or prove paste consumption. It is a reference implementation, not a cross-platform paste contract.

## Yap

The selected [Enriquefft/yap](https://github.com/Enriquefft/yap/tree/25f7a93441d13ee74c06496c10f28998aae24d25) is MIT and Linux-focused, written in Go. It is a good architecture reference rather than a desktop UI base.

[Transcribe](https://github.com/Enriquefft/yap/blob/25f7a93441d13ee74c06496c10f28998aae24d25/pkg/yap/transcribe/transcribe.go), [transform](https://github.com/Enriquefft/yap/blob/25f7a93441d13ee74c06496c10f28998aae24d25/pkg/yap/transform/transform.go), and [inject](https://github.com/Enriquefft/yap/blob/25f7a93441d13ee74c06496c10f28998aae24d25/pkg/yap/inject/inject.go) have separate contracts. Cancellation, final chunks, and errors are explicit. Per-request context is distinct from provider construction. The local Whisper backend manages a `whisper-server` process, with model download verification in the model package. These are useful patterns to translate into Rust without carrying over an entire second language runtime.

The [Linux injection directory](https://github.com/Enriquefft/yap/tree/25f7a93441d13ee74c06496c10f28998aae24d25/internal/platform/linux/inject) separates Hyprland/Sway/X11 detection, target classification, terminal-specific paths, and delivery strategies. A read-only strategy resolver explains which delivery path would run. Murmur should offer that diagnostic without collecting document text. OSC52 mainly updates a terminal clipboard; do not describe it as universal insertion into arbitrary remote editors.

Context collection from project documents and terminal sessions is useful but too broad as a default. Require opt-in per application and explicit cloud disclosure. Never promote external document content into trusted instructions. The current Linux implementation is not proof of Windows or macOS support.

## whispers

The selected [OneNoted/whispers](https://github.com/OneNoted/whispers/tree/5f0763109f9dde6c780bd2ef50cbd22e310c28c3) is MIT, with additional bundled notices. It is the most directly relevant requested Wayland reference, and now includes more than local recognition.

The [native OSD](https://github.com/OneNoted/whispers/blob/5f0763109f9dde6c780bd2ef50cbd22e310c28c3/src/bin/whispers-osd.rs) binds the layer-shell protocol directly, requests a fixed-size bottom overlay and no keyboard interaction. It uses exclusive zone `-1`, which ignores other exclusive zones; Murmur should test a zero-zone policy that reserves no desktop space while respecting panels. This distinction matters on docks and multiple monitors.

[Keyboard injection](https://github.com/OneNoted/whispers/blob/5f0763109f9dde6c780bd2ef50cbd22e310c28c3/src/inject/keyboard.rs) creates a `/dev/uinput` virtual device and synthesizes Ctrl+Shift+V. That is a terminal-friendly shortcut, not a universal paste shortcut. `/dev/uinput` access requires a permissions decision. Do not silently add the user to broad input groups or install udev rules.

[Cloud transcription](https://github.com/OneNoted/whispers/blob/5f0763109f9dde6c780bd2ef50cbd22e310c28c3/src/cloud.rs) supports OpenAI and compatible adapters, multipart audio, timeout settings and metadata logging. [Post-processing finalization](https://github.com/OneNoted/whispers/blob/5f0763109f9dde6c780bd2ef50cbd22e310c28c3/src/postprocess/finalize.rs) has raw fallbacks, explicit degraded reasons, conservative correction guards and structured-literal handling. Study those failure paths before adding advanced voice editing.

The [feature configuration](https://github.com/OneNoted/whispers/blob/5f0763109f9dde6c780bd2ef50cbd22e310c28c3/Cargo.toml) matters: local rewrite is optional and runs in its own worker; CUDA can accelerate that worker when enabled, whereas the Vulkan feature shown enables Whisper. Do not advertise GPU cleanup solely because a transcription build has Vulkan.

Use it to strengthen the Linux adapter and failure behavior. Do not adopt its full evolving rewrite machinery for the first release, or mistake a native Wayland overlay for universal Wayland hotkeys and injection.

## What this scan did and did not establish

All tracked-file inventories were enumerated, with focused reading of manifests, licenses, platform code, provider paths, cleanup, recovery and statistics. This is not an exhaustive line-by-line security audit. No dependencies were installed, no application was built, no credentials were entered, and no compositor or input permissions were changed. Runtime reliability, release artifact integrity, performance and model quality remain acceptance tests in [the roadmap](../roadmap.md).
