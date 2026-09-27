# Murmur for Android

Murmur is a native Kotlin dictation app. On Android 13 and newer, its optional voice bubble appears beside an ordinary focused text field while your usual keyboard stays selected. The idle bubble is a compact microphone control. Drag it to move it, tap to record, then tap the expanded control to transcribe and insert at the cursor. Input settings offer bubble size and opacity sliders; the position and appearance stay saved on the phone. The existing Murmur keyboard remains available, including on Android 8–12.

The app has Home, Speech, History, Words and Input sections in a floating bottom dock with equal-size touch targets. Home leads with retained words, this week's words, recorded speaking pace, recorded minutes, an eight-week words chart and weekly pace. Recent dictation and the next voice setup action follow. Speech opens separate Recognition and Cleanup screens, each showing its current status. History keeps raw and final text for recovery, and Words stores preferred spellings for cleanup. Input covers the bubble, optional device noise suppression and keyboard fallback. The bubble uses an Android `AccessibilityService` with the input method editor capability. It checks field focus, type, and cursor context, then uses `AccessibilityInputConnection.commitText` to insert there. It does not need Android's draw-over-other-apps permission or upload existing field text. Password, numeric and phone fields are excluded. A microphone foreground service, started while Murmur is visible, keeps recording available when you switch apps and provides a persistent **Voice ready** notification with a Stop action. Android restricts starting a microphone service from a background bubble, so this explicit activation is required.

## Set up the bubble

Home keeps the next voice setup action below the stats and recent dictation. Speech → Recognition contains the transcription key and service settings; Speech → Cleanup holds optional text editing. Input contains bubble controls.

1. Open Murmur and allow microphone access. In Speech → Recognition, choose on-device recognition if the phone offers an installed offline speech service. Otherwise pick OpenRouter, OpenAI or Groq to fill in the transcription URL and model, or enter your own. Paste a key if the service needs one and tap **Save**.
2. Tap **Enable voice bubble**, read and accept the accessibility disclosure, then enable **Murmur voice bubble** in Android settings.
3. Return to Murmur and tap **Turn on voice bubble** on Home. Keep your normal keyboard selected. The Input section has the Murmur keyboard fallback for phones older than Android 13.
4. Focus an ordinary text field in another app. Drag the bubble where it is comfortable, tap it to record, then tap Done to transcribe. Use × to cancel. Change its size or opacity in Input. If the field changes or insertion fails, open History in Murmur and copy the result manually.

On some Android 13+ phones, sideloaded apps cannot enable an Accessibility service until you open **Settings → Apps → Murmur → More → Allow restricted settings**. Then return to Accessibility and enable the service. Android documents this [restricted-settings step](https://support.google.com/android/answer/12623953?hl=en); enable it only for an APK you trust. Signing the APK does not by itself remove this sideload restriction. The development APK is already debug-signed; release signing is a separate requirement for distribution.

The default endpoint is OpenRouter's OpenAI-compatible `/audio/transcriptions` API, with `openai/whisper-large-v3-turbo` prefilled. The URL and model are editable. HTTPS is required except for the phone's own `localhost` or `127.0.0.1`; a desktop's localhost is not the phone's localhost. On Android 12 and newer, the optional on-device mode uses the phone's installed offline speech service. Availability, languages and model quality depend on the device. Murmur does not download or select its model, and it never switches from local recognition to the cloud endpoint automatically.

Transcript cleanup is off by default. Speech → Cleanup has a separate `/chat/completions` URL, required model ID, formality setting and encrypted key; its default URL is OpenRouter. The status immediately below the switch explains what is missing. Entering a valid model and key, then turning on the switch saves the fields and enables cleanup. Choosing a service preserves any model ID already typed. A custom endpoint on `127.0.0.1` means a server running on the phone; a server on another machine needs HTTPS. When enabled, cleanup receives raw transcript text and Words entries, not audio. A failed or over-aggressive response falls back to raw text, and the latest failure appears in Cleanup. Raw and final text remain separate in History. The Input page offers Android's device noise suppressor when available; it is off by default and can alter quiet speech.

Private app preferences hold encrypted speech and cleanup keys, settings and the latest result. Changing either endpoint URL removes its saved key. A local SQLite database stores raw and final transcripts, recording duration and timestamps. History defaults to keeping text until deletion; choosing 7, 30 or 90 days asks before removing older entries. History can export raw and final text as an unencrypted JSON file to a location you select. Home's stats use retained text; WPM uses raw words divided by recording time, including pauses. Temporary WAV files are deleted after processing, including failures and cancellation. No audio playback, audio export or retranscription from history exists yet. The Android app also lacks desktop model downloads, an embedded text-cleanup model and provider cost controls.

## Move data to the new Android app

Android treats `dev.local.murmur` and `ie.semyon.murmur` as separate apps. Install the `0.1.5-bridge` APK over the old app first. Then install the `0.1.5` APK with the new ID and tap **Move data from old app** on Home. The new app copies endpoint and cleanup settings, decrypts and re-encrypts both saved keys in its own Android Keystore, and merges all retained raw and final text history. Repeating the transfer does not duplicate old dictations. The bridge only serves the snapshot to `ie.semyon.murmur` when both apps have the same signing certificate. It streams directly between the apps and does not save a plaintext export file.

The old app keeps its data. Check the new app's History, Words and Speech settings before removing the old app. If Android Keystore can no longer decrypt an old key, history still moves and the app tells you to re-enter that key. Android grants microphone, accessibility and keyboard access separately to the new package, so enable those again. A phone with an old debug build needs the bridge APK signed by the same debug certificate. The two APKs can be built with `./gradlew :app:assembleDebug -PmurmurLegacyBridge=true` and `./gradlew :app:assembleDebug` respectively; copy the bridge APK before the second build overwrites the output path. The bridge property is only for migrating older installs. New installs use `ie.semyon.murmur`.

## Build and verify

### Sideloaded updates

Home has **Check for updates**. A build with an update feed checks once when the app opens and can download a verified APK before opening Android's installer. Android asks the user to allow installs from Murmur and to confirm the update. The voice bubble must be off before installation. The updater never runs a silent installation.

The feed is a public `android-update.json` asset in GitHub Releases. Builds point to `semyonfox/murmur` by default. Override the URL at build time for another release channel:

```sh
./gradlew :app:assembleRelease \
  -PmurmurUpdateManifestUrl=https://github.com/semyonfox/murmur/releases/latest/download/android-update.json
```

Set `MURMUR_ANDROID_KEYSTORE`, `MURMUR_ANDROID_STORE_PASSWORD`, `MURMUR_ANDROID_KEY_ALIAS`, and `MURMUR_ANDROID_KEY_PASSWORD` in the build environment before that command. The APK must be signed with the release key. Do not upload an unsigned APK or put signing values in tracked Gradle files.

The release must contain `android-update.json` and the signed APK named in it. For example:

```json
{
  "versionCode": 6,
  "versionName": "0.1.5",
  "apkUrl": "https://github.com/semyonfox/murmur/releases/download/v0.1.5/murmur-0.1.5.apk",
  "sha256": "64 lowercase hexadecimal characters from the exact signed APK",
  "sizeBytes": 12345678
}
```

Generate the manifest from the exact signed APK, then upload both files as assets of the same public GitHub Release:

```sh
python3 scripts/create_update_manifest.py path/to/murmur-0.1.5.apk \
  --repository semyonfox/murmur --tag v0.1.5 --version-code 6 --version-name 0.1.5
```

Increase `versionCode` for each release. Use the same application ID and signing certificate as the installed version; changing either prevents an in-place update. New installs use `ie.semyon.murmur`; the bridge above handles retained data from `dev.local.murmur`. The current APK is still debug signed; protect a durable release signing key before distributing the first release APK. An existing debug installation cannot be upgraded to a differently signed release. Neither the key nor its passwords belong in this repository. Do not publish the manifest until the APK is uploaded and its SHA-256 and byte size match. The updater checks those values and the APK's package, version and signer before asking Android to install it. It requires a public HTTPS release; private GitHub Releases cannot be read by the app without credentials.

The current updater is for sideloaded builds. A future Google Play build should use Play's in-app update API and omit `REQUEST_INSTALL_PACKAGES` from its manifest.

Use JDK 17 or later and Android SDK Platform 36. The Gradle wrapper is pinned to 8.13 and verifies its distribution checksum.

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Install `app/build/outputs/apk/debug/app-debug.apk` on an Android 8.0 or newer device. The popup requires Android 13 or newer; the optional keyboard works on older versions. An earlier Android 14 emulator run verified the popup with a mock STT server, but the new on-device recognition, cleanup, noise suppression, history and retention paths have only passed compilation, JVM tests and lint. See the [QA record and screenshot](../../docs/android.md#verification-still-needed). A release still needs a signing key, physical-device testing, and Google Play's Accessibility API declaration. Test Gboard, Chrome/WebView, messaging apps, split-screen, floating keyboards, editor switches, sensitive fields and permission revocation. No physical Android device is connected to the build machine yet.

The design follows Android's [Accessibility input method API](https://developer.android.com/reference/android/accessibilityservice/InputMethod), [accessibility service guide](https://developer.android.com/guide/topics/ui/accessibility/views/service), and [microphone foreground-service restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start). The default endpoint follows [OpenRouter's STT format](https://openrouter.ai/docs/guides/overview/multimodal/stt).
