# Murmur for Android

Murmur is a native Kotlin dictation app. On Android 13 and newer, its optional voice bubble appears beside an ordinary focused text field while your usual keyboard stays selected. The idle bubble is a compact microphone control at a fixed medium size and 90% opacity. Drag it to move it, tap to record, then tap the expanded control to transcribe and insert at the cursor. Its position stays saved on the phone.

The interface uses Jetpack Compose with Material 3, wallpaper-based dynamic colour on Android 12 and newer, edge-to-edge layout and predictive back. A floating frosted dock has Home, Lectures, History and Settings. On Android 12 and newer it blurs the content scrolling behind it. Installing an update pauses the voice bubble without turning it off, so it comes back on the new version. Home shows the dictation setup state with its next action, lecture shortcuts, the latest dictation and an activity summary that opens Stats. Settings holds Recognition, Transcript cleanup, Words, Voice bubble, noise suppression, History and storage, Stats and provider cost, anonymous reporting and About. Stats has the retained-word and pace charts plus OpenRouter key usage for the week, month and all time. Those key totals can include use outside Murmur, and local models and direct provider keys are excluded. Recognition shows the online form only when an online service is selected. History keeps raw and final dictation text for recovery and Words stores preferred spellings for cleanup. The bubble uses an Android `AccessibilityService` with the input method editor capability. It checks field focus, type, and cursor context, then uses `AccessibilityInputConnection.commitText` to insert there. It does not need Android's draw-over-other-apps permission or upload existing field text. Password, numeric and phone fields are excluded. A microphone foreground service, started while Murmur is visible, keeps recording available when you switch apps and provides a persistent **Voice ready** notification with a Stop action. Android restricts starting a microphone service from a background bubble, so this explicit activation is required.

The [model-settings comparison](../../docs/android-model-parity.md) lists the desktop controls and their Android behavior.

## Set up the bubble

Home shows the current microphone, recognition and input setup states and the next voice setup action. Settings → Recognition contains the transcription key and service settings, Settings → Transcript cleanup holds optional text editing and Settings → Voice bubble has the bubble controls.

1. Open Murmur and allow microphone access. In Settings → Recognition, use the phone's speech service, download a Whisper or Parakeet model for offline dictation and lecture files, or configure OpenRouter, OpenAI, Groq or a compatible endpoint. Enter a language code or leave `auto`. Whisper models can translate speech to English. Parakeet is much faster on a phone CPU and punctuates by itself, but it covers 25 European languages and cannot translate. Dictation is cut at natural pauses from three seconds on, and each piece is transcribed while you keep speaking, so finishing only waits for the last piece. Local Whisper models wait for twelve seconds because Whisper processes a full 30 second window for every piece. OpenRouter, OpenAI and Groq need a key; a custom endpoint may not. Tap **Save** to switch to the configured endpoint.
2. Tap **Enable voice bubble**, read and accept the accessibility disclosure, then enable **Murmur voice bubble** in Android settings.
3. Return to Murmur and tap **Turn on voice bubble** on Home. Keep your normal keyboard selected.
4. Focus an ordinary text field in another app. Drag the bubble where it is comfortable, tap it to record, then tap Done to transcribe. Use × to cancel. If the field changes or insertion fails, open History in Murmur and copy the result manually.

On some Android 13+ phones, sideloaded apps cannot enable an Accessibility service until you open **Settings → Apps → Murmur → More → Allow restricted settings**. Then return to Accessibility and enable the service. Android documents this [restricted-settings step](https://support.google.com/android/answer/12623953?hl=en); enable it only for an APK you trust. Signing the APK does not by itself remove this sideload restriction. The development APK is already debug-signed; release signing is a separate requirement for distribution.

The default endpoint is OpenRouter's OpenAI-compatible `/audio/transcriptions` API, with `openai/whisper-large-v3-turbo` prefilled. The URL and model are editable. HTTPS is required except for the phone's own `localhost` or `127.0.0.1`; a desktop's localhost is not the phone's localhost. On Android 12 and newer, the optional on-device mode uses the phone's installed offline speech service. Availability, languages and model quality depend on the device. Murmur does not download or select its model, and it never switches from local recognition to the cloud endpoint automatically.

Transcript cleanup is off by default. Settings → Transcript cleanup has a separate `/chat/completions` URL, required model ID, formality setting and encrypted key; its default URL is OpenRouter. The status immediately below the switch explains what is missing. Entering a valid model and key, then turning on the switch saves the fields and enables cleanup. Choosing a service preserves any model ID already typed. A custom endpoint on `127.0.0.1` means a server running on the phone; a server on another machine needs HTTPS. When enabled, cleanup receives transcript text and Words entries, not audio. It prepares clear spoken spellings locally, then asks the selected provider to apply explicit corrections and remove thinking-pause ellipses. A comma or dash marks an explicit spelling replacement; without one, the guessed word must resemble the stated letters. Ordinary descriptions such as "John spelled C-A-T for the class" stay intact for the provider. If the provider overwrites or drops a prepared spelling, cleanup keeps the prepared transcript. Failed, blank, excessively expanded or truncated responses fall back to raw text, and the latest failure appears in Cleanup. Raw and final text remain separate in History. Settings offers Android's device noise suppressor when available; it is off by default and can alter quiet speech.

Private app preferences hold encrypted speech and cleanup keys per endpoint URL, settings and the latest result. A local SQLite database stores raw and final transcripts, recording duration and timestamps. History defaults to keeping data until deletion; choosing 7, 30 or 90 days asks before removing older entries. History can export raw and final text as an unencrypted JSON file to a location you select. Stats counts dictations; WPM uses raw words divided by recording time, including pauses. Its OpenRouter card queries usage with configured OpenRouter keys on demand. Short dictation WAV files are temporary. Lecture WAV files stay in private app storage until their retention period ends or you delete them. Android offers verified local Whisper downloads and provider-specific cleanup endpoints, model discovery, filler removal and custom instructions. Embedded text cleanup remains planned.

## Record or import a lecture

Open the **Lectures** tab and tap **Record**, or use the shortcuts on Home. The recorder shows a running timer and a live level meter so you can see it is picking up the room. **Pause** stops writing audio until you resume, the tick finishes and transcribes, and the bin discards the recording after a confirmation. You can hide the recorder, switch apps or lock the phone while recording. The notification shows a running timer with Pause or Resume and Finish actions, and tapping it returns to the recorder. The import icon in the Lectures bar opens Android's document picker. Android decodes the file to 16 kHz mono WAV, and decoder support depends on the phone. Recordings and imports are limited to eight hours. Murmur transcribes two-minute parts with the selected downloaded model or endpoint, and an endpoint can charge for each part. It then sends each text part to the selected cleanup provider if cleanup is enabled. The raw transcript remains available if cleanup fails. Silence can yield an empty part.

Both actions require a downloaded model or endpoint in **Settings → Recognition**. Android's installed speech service cannot transcribe saved files, and Murmur never switches to an endpoint automatically. Opening a lecture shows transcription progress as parts arrive, an audio player and the transcript split by part. Tapping a part's time plays the audio from there and the playing part is highlighted. A Cleaned and Raw switch appears when cleanup changed the text. The menu copies or shares the text without timestamps, shares the audio, transcribes again or deletes the lecture, and the pencil renames it. Transcribing again with a cloud endpoint starts a fresh series of requests and may incur another charge. A failed or interrupted job keeps its audio and partial transcript for recovery. History's JSON export includes lecture text but no audio. Lecture text does not affect dictation pace statistics. This flow still needs a physical phone check for long recordings, pause and resume, imported codecs, notification behaviour, local inference and endpoint responses.

## Diagnostics

Murmur keeps a small log on the phone of errors, crashes, why Android ended the previous run, and how long model loads, transcription and cleanup took. It never records transcript text, audio, keys or URL query strings, and it stays on the phone until you tap **About → Share diagnostics log**. **Clear diagnostics log** deletes it. The same lines go to logcat under `Murmur/` tags.

## Existing installs

Android treats `dev.local.murmur` and `ie.semyon.murmur` as separate apps. Version 0.1.7 removes the Home screen data-copy action. Installing it over 0.1.6 keeps the new app's existing settings and history. Data in the old `dev.local.murmur` app stays there; check what you need before removing that app.

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
  "versionCode": 14,
  "versionName": "0.1.13",
  "apkUrl": "https://github.com/semyonfox/murmur/releases/download/v0.1.13/murmur-0.1.13.apk",
  "sha256": "64 lowercase hexadecimal characters from the exact signed APK",
  "sizeBytes": 12345678
}
```

Generate the manifest from the exact signed APK, then upload both files as assets of the same public GitHub Release:

```sh
python3 scripts/create_update_manifest.py path/to/murmur-0.1.13.apk \
  --repository semyonfox/murmur --tag v0.1.13 --version-code 14 --version-name 0.1.13
```

Increase `versionCode` for each release. Use the same application ID and signing certificate as the installed version; changing either prevents an in-place update. New installs use `ie.semyon.murmur`. The current APK is still debug signed; protect a durable release signing key before distributing the first release APK. An existing debug installation cannot be upgraded to a differently signed release. Neither the key nor its passwords belong in this repository. Do not publish the manifest until the APK is uploaded and its SHA-256 and byte size match. The updater checks those values and the APK's package, version and signer before asking Android to install it. It requires a public HTTPS release; private GitHub Releases cannot be read by the app without credentials.

The current updater is for sideloaded builds. A future Google Play build should use Play's in-app update API and omit `REQUEST_INSTALL_PACKAGES` from its manifest.

Use JDK 17 or later and Android SDK Platform 36. The Gradle wrapper is pinned to 8.13 and verifies its distribution checksum.

```bash
git submodule update --init apps/android/native/whisper.cpp
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Install `app/build/outputs/apk/debug/app-debug.apk` on an Android 8.0 or newer device. Dictation into other apps requires Android 13 or newer. Older phones can still record lectures and transcribe files. An earlier Android 14 emulator run verified the popup with a mock STT server. Local Whisper inference, model management, the expanded cleanup providers and lecture flow have compiled and passed JVM tests and lint but still need phone tests. See the [QA record and screenshot](../../docs/android.md#verification-still-needed). A release still needs a signing key, physical-device testing, and Google Play's Accessibility API declaration. No physical Android device is connected to the build machine yet.

## Local speech source and licenses

The Android native runtime is the MIT-licensed [whisper.cpp](https://github.com/ggml-org/whisper.cpp) submodule at `d09f61a708f3487afa956ff578e60eae5e7a233c`. Murmur's JNI adapter is `app/src/main/cpp/whisper_jni.cpp`; the license is retained at `native/whisper.cpp/LICENSE` and packaged in the APK under **Open-source licenses**. The GGML model files are fetched from `ggerganov/whisper.cpp` at Hugging Face revision `5359861c739e955e79d9a303bcbc70fb988958b1`, with fixed byte sizes and SHA-256 checks in `LocalWhisper.kt`. The Tiny file's exact hash and size were checked by downloading it. Model weights have separate licensing and are downloaded only after the user taps **Download**.

The design follows Android's [Accessibility input method API](https://developer.android.com/reference/android/accessibilityservice/InputMethod), [accessibility service guide](https://developer.android.com/guide/topics/ui/accessibility/views/service), and [microphone foreground-service restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start). The default endpoint follows [OpenRouter's STT format](https://openrouter.ai/docs/guides/overview/multimodal/stt).
