# Platform feasibility and delivery plan

Research date: 2026-09-26. Evidence consists of official platform documentation and upstream protocol or implementation sources. No desktop integration, mobile build, physical device, microphone, insertion, signing, or store submission was tested for this report. Upstream support is not a claim about the versions installed on a user's machine.

## Recommendation

Build one dictation pipeline with separate native integration layers. Tauri and Rust remain a good desktop foundation, but a shared webview cannot supply the keyboard extensions, window roles, permissions, and text insertion behavior this product needs. Tauri's own mobile integration requires Swift and Kotlin plugins, and its global shortcut examples explicitly register only on desktop. [Tauri mobile plugins](https://v2.tauri.app/develop/plugins/develop-mobile/), [global shortcut plugin](https://v2.tauri.app/plugin/global-shortcut/).

Prove three risky paths before committing to a fork or promising all-platform parity: a Hyprland overlay that never tiles or steals focus, iOS recording and keyboard handoff that fits public APIs and review rules, and Android IME recording across current permission and process-lifecycle rules. Ship the same transcript quality, vocabulary, history controls, statistics, and cost accounting everywhere. The trigger and insertion interaction must follow each OS.

iOS is the clearest limit on the requirement. A fully useful iOS app and keyboard are feasible, but an identical desktop-style recording hotkey in every app and field is not. This constraint remains even if an existing product appears to achieve a smoother workflow.

## Capability matrix

Every row below is a proposed implementation, not a supported release. "Feasible" means a documented mechanism exists. "Conditional" means permissions, compositor support, store review, hardware, or an unproven integration determines the outcome. All rows are untested in Murmur.

| Target | Capture and activation | Insertion | Recording feedback | Assessment |
| --- | --- | --- | --- | --- |
| CachyOS or Arch with Hyprland | Native audio; GlobalShortcuts portal where registered and bound; explicit compositor binding fallback | Authorized virtual keyboard protocol or an available RemoteDesktop backend; clipboard fallback | Separate layer-shell surface with no keyboard focus and no reserved layout space | Feasible with a dedicated Hyprland adapter. Highest desktop priority |
| KDE Plasma Wayland | Native audio; GlobalShortcuts portal | RemoteDesktop portal and libei after consent; clipboard fallback | Layer shell where advertised | Feasible, subject to installed portal versions and session permissions |
| GNOME Wayland | Native audio; GlobalShortcuts on supporting versions; user-configured desktop shortcut fallback | RemoteDesktop portal and libei after consent; clipboard fallback | Native notification or optional GNOME Shell integration; no generic layer-shell assumption | Conditional for matching the compact HUD experience. Core dictation has a plausible portal path |
| Sway and other wlroots compositors | Native audio; compositor binding unless a suitable shortcut backend exists | Virtual keyboard where compositor permits it; clipboard fallback | Layer shell where advertised | Feasible per compositor. xdg-desktop-portal-wlr alone does not provide the required shortcut or injection portals |
| Linux X11 | Native audio and X11 shortcut registration | XTEST keyboard events or clipboard plus paste | Small non-activating window, verified against target window managers | Feasible; separate from the Wayland path |
| Windows | Native audio and RegisterHotKey; separately prove hold-to-talk key release | Unicode SendInput or clipboard plus paste; elevation boundary applies | Non-activating native tool window | Feasible for normal desktop apps. No promise to type into higher-integrity apps or secure desktop |
| macOS | Native audio and native shortcut adapter | Accessibility-aware insertion or event posting after consent; clipboard fallback | Non-activating NSPanel | Feasible; permission denial and signed-update behavior need testing |
| iOS and iPadOS | Containing app owns microphone; keyboard receives completed text. Any armed background session is a separate proof | UITextDocumentProxy in fields that allow third-party keyboards; copy/share from app elsewhere | Native recorder UI and system recording indicator; keyboard state only | Conditional. Keyboard cannot record directly; some fields and apps exclude it |
| Android | Native recorder plus InputMethodService; microphone tap while IME is visible; separately prove continuation | InputConnection.commitText in the current editor | Native IME state; notification if a valid microphone foreground service is used | Feasible with lifecycle work. OEM behavior and background recording remain acceptance gates |

The following sections contain the evidence behind these choices. Mobile local inference also needs a separate memory, latency, battery, model-license, and language assessment. "Local mode" should mean processing on that device. A phone calling a user-owned desktop server is a separate remote-server mode, even if no commercial provider receives the audio.

## Linux without compositor surprises

### Keep the recording surface out of the tiling layout

Give settings and history a normal application window. Give recording feedback a separate native surface. On supporting Wayland compositors, use `zwlr_layer_shell_v1` with an explicit compact size, `keyboard_interactivity = none`, and `exclusive_zone = 0`. A zero exclusive zone does not reserve work area; the compositor can place the surface around existing panels. Use an empty input region for purely visual feedback. Keep interactive controls in the main app or a deliberately interactive surface. These semantics come from the protocol, rather than a request that a normal window happen to float. [Layer-shell protocol](https://raw.githubusercontent.com/swaywm/wlr-protocols/master/unstable/wlr-layer-shell-unstable-v1.xml).

Proposed HUD contract: initial size 240 by 52 logical pixels, adjustable for accessibility, anchored to the active output's bottom edge with a margin. Recording must never map the settings window. Respect output scale and configure events; handle monitor removal without moving or resizing another application. The exact size is a design proposal, not a measured result.

GNOME Wayland does not support this layer-shell route. The upstream gtk-layer-shell compatibility list explicitly distinguishes GNOME from KDE and wlroots compositors. Treat GNOME notification feedback as a deliberate fallback; if a persistent waveform pill is a release requirement, budget and test a GNOME Shell extension. Do not mark GNOME visual parity complete with a normal always-on-top window that happens to behave on one machine. [gtk-layer-shell supported desktops](https://github.com/wmww/gtk-layer-shell#supported-desktops).

### Detect actual capabilities

Portals depend on a desktop-specific backend, and each interface can route to a different implementation. Probe available interfaces and versions, Wayland globals, and permission outcomes at runtime. A distro name, `WAYLAND_DISPLAY`, or successful screensharing request does not prove injection support. [Portal backends](https://flatpak.github.io/xdg-desktop-portal/docs/), [portal configuration](https://flatpak.github.io/xdg-desktop-portal/docs/configuration-file.html).

The upstream files inspected on the research date show:

| Backend | Relevant evidence | Product consequence |
| --- | --- | --- |
| Hyprland | Its portal descriptor advertises Screenshot, ScreenCast, GlobalShortcuts, and InputCapture, but not RemoteDesktop | Use its shortcut integration. Do not infer an injection API from its InputCapture support |
| KDE | Its descriptor includes GlobalShortcuts, RemoteDesktop, Clipboard, and InputCapture | Portal-based shortcut and insertion paths are candidates, still requiring a working session and user consent |
| GNOME | Its build includes global shortcut and remote desktop implementations | Test installed GNOME versions and backend routing; upstream source does not guarantee an older distro has them |
| wlroots | Its README lists Screenshot and ScreenCast only | A generic wlr portal installation needs other mechanisms for shortcuts and insertion |

Sources: [Hyprland descriptor](https://raw.githubusercontent.com/hyprwm/xdg-desktop-portal-hyprland/master/hyprland.portal), [KDE descriptor](https://invent.kde.org/plasma/xdg-desktop-portal-kde/-/raw/master/data/kde.portal), [GNOME build sources](https://gitlab.gnome.org/GNOME/xdg-desktop-portal-gnome/-/blob/main/src/meson.build), [wlroots backend README](https://github.com/emersion/xdg-desktop-portal-wlr).

InputCapture delivers existing input events to an application. RemoteDesktop permits an application to send input after the user grants a session. They solve different problems. Prefer RemoteDesktop's EIS connection with libei where supported; handle rejection, revocation, closed sessions, and unsupported persistence. Never request screen capture merely to obtain keyboard injection. [InputCapture interface](https://flatpak.github.io/xdg-desktop-portal/docs/doc-org.freedesktop.portal.InputCapture.html), [RemoteDesktop interface](https://flatpak.github.io/xdg-desktop-portal/docs/doc-org.freedesktop.portal.RemoteDesktop.html).

### Hotkeys and insertion

Prefer the GlobalShortcuts portal for named actions such as toggle recording and cancel. It provides activation and deactivation events, which can support hold-to-talk after testing release delivery. Hyprland documents its XDPH-backed global dispatcher and user bindings. Provide version-appropriate instructions if a binding is needed; do not overwrite compositor configuration. [GlobalShortcuts interface](https://flatpak.github.io/xdg-desktop-portal/docs/doc-org.freedesktop.portal.GlobalShortcuts.html), [Hyprland global shortcuts](https://wiki.hypr.land/configuring/core/binds/globals/).

For Hyprland and Sway, prototype the virtual keyboard protocol as a native adapter where advertised and permitted. It supplies keymaps and raw key events and allows the compositor to reject unauthorized clients. It is not a universal Wayland text insertion API. A clipboard-plus-paste path can avoid Unicode keyboard-layout problems, but its clipboard permission and paste behavior need their own tests. [Virtual keyboard protocol](https://raw.githubusercontent.com/swaywm/wlroots/master/protocol/virtual-keyboard-unstable-v1.xml).

Keep X11 registration and XTEST injection behind a separate backend. An X11 implementation running through XWayland does not establish native Wayland compatibility. The XTEST protocol defines simulated input inside the X server. [XTEST specification](https://www.x.org/releases/X11R7.6/doc/xextproto/xtest.pdf).

Do not make a privileged input daemon part of the default installation. ydotool uses `/dev/uinput`, normally needs elevated access, and requires its daemon. Its AGPL license also needs review before bundling or incorporating it. If later offered as an advanced fallback, installation must be an explicit separate choice with a restricted local interface. [ydotool upstream](https://github.com/ReimuNotMoe/ydotool).

Per-app context is optional. Hyprland exposes active-window events and query sockets, but this is compositor-specific integration. No selected desktop path establishes universal access to another application's document text. Start with explicit app profiles and opt-in context from available APIs; keep a context-free cleanup path fully functional. [Hyprland IPC](https://wiki.hypr.land/IPC/).

### Linux diagnostic and packaging plan

Provide an in-app diagnostic page showing session type, compositor version when available, audio devices, shortcut method, overlay method, insertion method, and permission state. A self-test should record a short clip, show the HUD, and insert into an application-owned test field. If insertion is unavailable, retain the transcript and offer Copy. An installation should fail visibly and usefully rather than silently lose text.

Start with a native Arch/CachyOS package and a portable desktop build, followed by Debian and RPM packages. Validate the actual dependencies and distro baseline. Tauri uses WebKitGTK on Linux; a successful web frontend build does not exercise those runtime differences. Flatpak is a separate release gate because sandbox access and portal coverage can change the integration path. [Tauri webview versions](https://v2.tauri.app/reference/webview-versions/), [distribution formats](https://v2.tauri.app/distribute/).

## Windows and macOS

On Windows, use RegisterHotKey for a normal toggle shortcut. Registration can fail when another application owns the chord, so show an actionable conflict and let the user select another. RegisterHotKey's press notification alone does not prove a reliable hold-to-talk implementation; test a dedicated release-detection path or initially ship toggle mode. [RegisterHotKey](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-registerhotkey).

Use a native recording window with `WS_EX_NOACTIVATE` and appropriate tool-window behavior. SendInput supports synthesized keyboard input, and `KEYEVENTF_UNICODE` is intended for non-keyboard text input such as speech recognition. UIPI limits injection to equal or lower integrity processes; the return value does not identify UIPI as the cause. Retain the transcript when insertion cannot be confirmed. Do not run the whole app elevated to hide this limit. [Window styles](https://learn.microsoft.com/en-us/windows/win32/winmsg/extended-window-styles), [SendInput](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-sendinput), [INPUT and Unicode](https://learn.microsoft.com/en-us/windows/win32/api/winuser/ns-winuser-input).

On macOS, implement the HUD as a non-activating NSPanel. Separate microphone permission from accessibility trust and event-posting permission checks. Request permissions when the user enables the relevant feature, and recheck after revocation and app updates. An accessibility trust check can prompt asynchronously; it does not synchronously grant access. [NSWindow style masks](https://developer.apple.com/documentation/appkit/nswindow/stylemask-swift.struct/utilitywindow), [accessibility trust](https://developer.apple.com/documentation/applicationservices/1459186-axisprocesstrustedwithoptions), [event access APIs](https://developer.apple.com/documentation/coregraphics/core-graphics-functions).

For both platforms, clipboard insertion must be a transaction. Preserve supported clipboard formats only for the brief operation, restore only if Murmur still owns the clipboard, and do not overwrite a new user copy. A fixed timer cannot prove another application has consumed the paste. Prefer direct insertion where reliable, preserve a recovery copy inside Murmur, and make unsupported restoration visible. Never submit a message, press Enter, or execute a terminal command automatically after dictation.

## iOS and iPadOS

### The keyboard cannot own the microphone

Apple's custom-keyboard guide explicitly excludes direct microphone access. Current UIKit open-access documentation still lists that restriction and describes Full Access as enabling networking and additional shared-container access, not a microphone entitlement. The current interface documentation also excludes secure fields and phone-pad fields, and allows apps to reject third-party keyboards entirely. [Custom keyboard limits](https://developer.apple.com/library/archive/documentation/General/Conceptual/ExtensibilityPG/CustomKeyboard.html), [open access](https://developer.apple.com/documentation/uikit/configuring-open-access-for-a-custom-keyboard), [field and host-app restrictions](https://developer.apple.com/documentation/uikit/configuring-a-custom-keyboard-interface).

Use a native Swift containing app for recording, provider credentials, local model execution, cleanup, history, and settings. Keep the keyboard extension small. Apple documents device-dependent extension memory limits and termination when those limits are exceeded. Running the full STT and cleanup models inside the keyboard would add risk without solving microphone access. [Creating a custom keyboard](https://developer.apple.com/documentation/uikit/creating-a-custom-keyboard).

### First implement the supported handoff

1. The user opens Murmur and explicitly starts a recording.
2. The containing app records, transcribes, and cleans the text. It writes a completed transcript envelope to its App Group container with a session identifier and expiry.
3. The user returns to the destination app and selects the Murmur keyboard.
4. The keyboard displays the available result and inserts only after a deliberate tap, through `textDocumentProxy.insertText`.
5. The extension records consumption in its own local storage to prevent duplicate insertion. With authorized shared writes, it can also acknowledge consumption to the containing app. Without that permission, the containing app expires old results itself.

This proposed design follows two documented mechanisms. A keyboard without Full Access can read its containing app's shared container, but cannot write there or access the network. UITextDocumentProxy provides insertion and surrounding context without direct access to the host's text view. Reading a completed result therefore does not inherently require Full Access; bidirectional control does. Both paths still require a device prototype. [Shared-container capabilities](https://developer.apple.com/documentation/uikit/configuring-open-access-for-a-custom-keyboard), [text interaction APIs](https://developer.apple.com/documentation/uikit/handling-text-interactions-in-custom-keyboards).

For apps that exclude the keyboard, provide Copy and the native share flow in Murmur. Clearly explain this limitation during setup. Do not claim access to all text fields or a guaranteed automatic return to the previous app.

### A smoother session is a separate product gate

Apple allows an app with a configured audio session and background audio mode to continue an active recording when it moves to the background. This establishes a possible containing-app recording path, not permission to silently retain the microphone indefinitely or launch a suspended recorder from any keyboard. Calls and other audio sessions can interrupt recording. [AVAudioSession recording category](https://developer.apple.com/documentation/avfaudio/avaudiosession/category-swift.struct/record), [audio lifecycle guidance](https://developer.apple.com/library/archive/documentation/Audio/Conceptual/AudioSessionProgrammingGuide/AudioGuidelinesByAppType/AudioGuidelinesByAppType.html).

Prototype a user-started, visibly recording, bounded session while the user switches to another app. Prove stop controls, interruption recovery, timeout, cleanup completion, extension restart, and insertion. Do not use silent audio to keep an otherwise idle app alive. Stop capture promptly when the session ends.

App Review rule 4.4.1 requires keyboard input, keyboard switching, and useful operation without Full Access or networking. It prohibits keyboard extensions from launching apps other than Settings. Rules 2.5.4 and 2.5.14 constrain background-service use and require consent plus a recording indication. Therefore a microphone button that opens the containing app through an undocumented responder-chain trick is not an acceptable assumed foundation. [App Review Guidelines](https://developer.apple.com/app-store/review/guidelines/).

Recommendation: submit the smallest working native recorder and keyboard through an early review cycle before building a business around the smoother session. A TestFlight build can prove installation and behavior; it does not establish final App Store approval. If the acceptable iOS experience requires keyboard-triggered cold-start recording with no manual app switch, feasibility remains unresolved.

## Android

Implement a native Kotlin `InputMethodService`, declared with `BIND_INPUT_METHOD` and its input-method metadata, alongside the main application. Use the current InputConnection to commit a completed transcript into the editor. Support the normal keyboard switcher, field types, composition, and cancellation when the editor changes. This is a normal IME integration; an accessibility service or unrestricted overlay is unnecessary for the core feature. [Creating an input method](https://developer.android.com/develop/ui/views/touch-and-input/creating-input-method), [InputConnection](https://developer.android.com/reference/android/view/inputmethod/InputConnection).

Start the initial recording prototype from a visible IME microphone button after the user has granted `RECORD_AUDIO`. Exercise actual AudioRecord access on physical devices. Stop when input finishes or the user changes editors unless a separately validated, user-visible continuation mode is active. Do not treat "the service started" as evidence that audio samples are available.

There are two distinct restrictions to account for. Android's general background foreground-service start rules list the current IME as an exemption. The separate rules for services needing while-in-use microphone permission have a different exemption list, which does not list IMEs. IME status therefore does not by itself prove that an arbitrary background microphone service can start. [Foreground-service start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start).

If continued recording uses a foreground service, declare its `microphone` type and the required foreground-service permissions, obtain microphone runtime permission, and obey its notification and lifecycle requirements. The official microphone type is intended to continue recording in the background after a valid start. Test permission denial, microphone privacy toggle, app backgrounding, lock, process death, and OEM battery management without instructing users to disable their device protections. [Foreground-service types](https://developer.android.com/develop/background-work/services/fgs/service-types).

Treat password variations as sensitive and disable transcript history, contextual cleanup, and cloud dictation there by default. Honor `IME_FLAG_NO_PERSONALIZED_LEARNING`; it explicitly requests that the keyboard not update typing history or personalized language data. Use EditorInfo for field behavior and opt-in app profiles, not unrestricted cross-app inspection. [EditorInfo](https://developer.android.com/reference/android/view/inputmethod/EditorInfo).

An InputConnection may become invalid while transcription is in flight. Associate each recording with the editor session and revalidate before committing. If the editor changed, keep the result in Murmur and offer explicit insertion. A successful API call is not proof that the host persisted the final text, so preserve recoverability.

## Release and distribution constraints

| Target | Proposed distribution | Required proof before release |
| --- | --- | --- |
| Linux | Arch/CachyOS package first; Debian/RPM and portable artifacts after matrix coverage | Install on clean supported systems, compatible WebKitGTK and audio dependencies, signed update verification, rollback, correct Wayland and X11 backend selection. Flatpak separately proves sandboxed integration |
| Windows | Signed installer initially; evaluate Store MSIX later | Trusted signing identity, install/uninstall and update tests, clean standard-user account, microphone setup, WebView2 availability, reputation prompts, no administrator requirement for ordinary use |
| macOS | Developer ID distribution initially | Sign app and bundled executables, hardened runtime, notarization, stapled ticket, clean-machine permission onboarding, update preserves intended identity |
| iOS | Native app plus keyboard extension through TestFlight then App Store | Apple account and provisioning, App Group entitlements, microphone declaration, physical devices, keyboard functionality without Full Access, reviewed recording handoff, final App Review |
| Android | Signed APK for controlled testing; AAB with Play App Signing for Play | Stable package and signing identity, native-library ABI checks, target-SDK and policy compliance, physical phones and tablets, foreground-service disclosures if used |

Microsoft distinguishes Store MSIX signing from publisher-signed MSI/EXE distribution. A signature does not guarantee that a new non-Store binary avoids SmartScreen prompts. Apple notarization is different from App Review, and its direct-distribution workflow requires Developer ID signing and hardened runtime. Android requires signed APKs and uses an upload key plus Play App Signing for app bundles. [Windows distribution paths](https://learn.microsoft.com/en-us/windows/apps/package-and-deploy/choose-distribution-path), [SmartScreen behavior](https://learn.microsoft.com/en-us/windows/apps/package-and-deploy/distribution-feature-status), [Apple notarization](https://developer.apple.com/documentation/security/notarizing-macos-software-before-distribution), [Android signing](https://developer.android.com/studio/publish/app-signing).

Recheck store SDK deadlines, developer verification, billing rules, and regional distribution rules at the time of submission. Android is rolling out developer verification requirements; direct APK distribution should not be assumed to avoid all platform registration requirements. No accounts were purchased, signing identities created, devices registered, or store submissions made during this research. [Android developer verification](https://developer.android.com/developer-verification/).

Use native CI runners for each desktop OS and macOS infrastructure for Apple builds. Share the Rust provider and pipeline code where it reduces duplication, while keeping Swift and Kotlin lifecycle and keyboard code native. No release credential belongs in this repository.

## Acceptance gates

These are proposed release criteria, not completed tests. Save each result with app commit, OS build, compositor and portal versions, hardware, model and quantization, audio route, insertion backend, permissions, and a short recording or reproducible log without private text.

### Hardware and environment coverage

| Gate | Minimum coverage | Passing result |
| --- | --- | --- |
| Hyprland | Physical CachyOS/Arch desktop; current packaged compositor; native Wayland and XWayland apps; at least two display scales | 100 start/stop/cancel cycles, zero tiled HUDs, zero focus theft, zero unexplained lost or duplicated insertions |
| Other Linux | Physical or interactive GNOME and KDE sessions plus Sway and an X11 session | Native integration succeeds where claimed; denied or absent APIs always leave a recoverable transcript and usable Copy action |
| Windows | Physical standard-user Windows machine; laptop microphone and USB/Bluetooth routes | Normal apps insert reliably; elevated apps fail safely; signed installation and upgrade work |
| macOS | Physical Apple Silicon Mac; add Intel only if claiming Intel support | Permission grant, denial, revocation, sleep/resume, Spaces, fullscreen and signed update all pass |
| iOS/iPadOS | Oldest claimed iPhone class and a current iPhone; iPad if advertised | Recorder, manual handoff, Full Access off/on, keyboard process restart, secure-field fallback, interruptions and memory pressure pass; review gate resolved |
| Android | Physical Pixel and Samsung; one low-memory device; tablet/foldable if advertised | Visible-IME recording, editor switching, process death, denied microphone, privacy toggle, screen lock and valid foreground-service path pass |

### Cross-platform behavior

- Dictate into a plain text editor, browser textarea and contenteditable editor, office document, chat composer, and terminal. Include native and Electron apps on desktop. Do not send or execute the result.
- Cover English punctuation, Irish names and accents, emoji, multiline text, RTL text, non-Latin text, dead keys, non-US layouts, an active IME composition, and a selection that should be replaced.
- Test first launch, a conflicting shortcut, hold release, toggle, cancel, rapid double presses, missing microphone, disconnected headset, changed sample rate, sleep/resume, screen lock, and another app taking audio focus.
- Change focus or the target field while STT or cleanup runs. Never paste a delayed result into an unrelated destination. Preserve the result for explicit recovery.
- Copy an image or rich text before dictating, then copy new content while processing. Verify the app neither destroys the clipboard nor restores stale content over the user's newer copy.
- Test airplane mode with downloaded local models, no model present, interrupted model download, provider timeout, exhausted credit, bad BYOK credentials, cancelled requests, and cleanup failure. Never switch from local to cloud without the user's selected fallback policy.
- Measure keypress-to-recording-indication, stop-to-first-text, stop-to-cleaned-text, insertion latency, peak memory, CPU/GPU use, mobile battery cost, and thermal throttling. Publish results by hardware and model; do not hide slow local hardware inside one average.
- Reconcile spoken duration, raw words, delivered words, cleanup changes, cancellations, retries, and billed attempts. Weekly WPM must use a defined word count and recording duration on all clients, with the same timezone and week boundary. Retries must not count as another dictation or inflate user activity.

Proposed responsiveness goal: visible recording feedback within 150 ms at the 95th percentile on reference hardware, independent of model loading. A spinner must distinguish model warm-up from an active microphone. End-to-cleaned-text goals should be fixed only after the model benchmark. No latency or battery numbers have been measured yet.

### Unresolved decisions that affect the product

1. Is the documented iOS app-to-keyboard handoff acceptable if a smoother reviewed session cannot be shipped? This affects the core parity promise.
2. Does GNOME require the same persistent waveform pill, with the maintenance cost of a shell integration, or is native recording feedback sufficient?
3. Which minimum desktop versions, mobile devices, languages, and local model sizes will receive support commitments? Choose from measured results, not framework marketing.

The first implementation milestone should resolve those questions through native proofs and recorded evidence. A working settings screen or successful cross-compilation does not close them.
