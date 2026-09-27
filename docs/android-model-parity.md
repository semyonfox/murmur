# Android and desktop model settings

The two apps use the same choices where a phone can perform the work. Android keeps three speech paths separate: the phone's installed speech service, a downloaded Whisper model, and an explicit endpoint. The installed service does not expose a selectable model and cannot transcribe a saved lecture file.

| Setting or action | Desktop | Android |
| --- | --- | --- |
| Local speech models | Download, select and delete from the desktop catalog | Download, resume, cancel, select and delete four Whisper sizes; size and SHA-256 verified before use |
| Speech endpoint | OpenRouter, OpenAI, Groq and custom URL with model and key | Same choices, with encrypted keys and remembered model IDs per URL |
| Language | Auto or a selected language | Auto or language code; passed to downloaded Whisper, the installed speech service and compatible endpoints |
| Translate to English | Available on models that support it | Available on downloaded multilingual Whisper models |
| Lecture recording and audio import | Uses selected recognition source | Uses the selected downloaded model or endpoint; the installed speech service cannot handle saved audio |
| Local filler removal | Toggle, independent of AI cleanup | Same toggle, independent of AI cleanup; raw transcript retained |
| AI cleanup | Toggle, five formality levels and provider models | Same, with OpenAI-compatible providers and Anthropic Messages; no silent cloud fallback |
| Cleanup providers | Ollama, OpenAI, Z.AI, SiliconFlow, OpenRouter, Anthropic, Groq, Cerebras, AWS Bedrock Mantle, custom; Apple Intelligence on supported Macs | All listed network providers; Apple Intelligence is a macOS feature |
| Provider models and keys | Browse models; save provider-specific model and key | Browse models; save model and encrypted key per endpoint URL; enter an ID manually if discovery fails |
| Instructions | Default and named prompts to create, edit, select and delete | Same operations; transcript remains a separate user message |
| Dictionary | Preferred spelling terms | Same terms, supplied to cleanup as spelling data |

The desktop catalog includes additional native engines and filters. Android's current catalog is four multilingual Whisper models with RAM checks. Adding other phone runtimes needs a device benchmark and a model-license check. The Android APK and JVM tests passed; local inference, large downloads, battery use, long lectures and insertion after transcription still need device tests. No Android device was attached during this change.
