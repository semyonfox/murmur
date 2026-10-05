import type { Page } from "@playwright/test";
import type { AppSettings, HistoryEntry, ModelInfo } from "../src/bindings";

declare global {
  interface Window {
    __murmurEmitEvent: (event: string, payload: unknown) => void;
    __murmurTestCalls: Array<{
      command: string;
      args: Record<string, unknown>;
    }>;
  }
}

export const sampleHistory: HistoryEntry = {
  id: 1,
  file_name: "synthetic-fixture.wav",
  timestamp: 1791021600,
  saved: false,
  title: "",
  transcription_text: "um send the sample agenda to Alex tomorrow",
  post_processed_text: "Send the sample agenda to Alex tomorrow.",
  post_process_prompt: null,
  post_process_requested: true,
};

export const sampleModel: ModelInfo = {
  id: "fixture-tiny",
  name: "Whisper Tiny",
  description: "Synthetic available model",
  filename: "fixture.bin",
  source: "Local",
  size_mb: 75,
  is_downloaded: true,
  is_downloading: false,
  partial_size: 0,
  is_directory: false,
  engine_type: "TranscribeCpp",
  accuracy_score: 0.7,
  speed_score: 0.9,
  supports_translation: true,
  is_recommended: false,
  supported_languages: ["en"],
  supports_language_selection: true,
  is_custom: false,
  supports_streaming: false,
  supports_language_detection: true,
};

interface FixtureOptions {
  settings?: AppSettings;
  history?: HistoryEntry[];
  models?: ModelInfo[];
  historyFailure?: "returned" | "thrown";
  historyDelayMs?: number;
  deleteFailure?: boolean;
  confirmDelete?: boolean;
  platform?: "linux" | "windows";
  permissionPollingFailure?: boolean;
  recordingFailure?: boolean;
  credentialFailure?: boolean;
}

export async function installFixture(page: Page, options: FixtureOptions = {}) {
  await page.addInitScript((fixture: FixtureOptions) => {
    const settings: AppSettings = {
      onboarding_completed: true,
      app_language: "en",
      theme: "dark",
      custom_words: [],
      audio_feedback: false,
      sound_theme: "marimba",
      debug_mode: false,
      selected_microphone: "Default",
      selected_output_device: "Default",
      post_process_enabled: false,
      post_process_providers: [],
      recording_retention_period: "never",
      ...fixture.settings,
    };
    const calls: Array<{ command: string; args: Record<string, unknown> }> = [];
    const callbacks = new Map<number, (event: unknown) => void>();
    const listeners = new Map<string, Set<number>>();
    let nextCallbackId = 1;
    let openedPrivacySettings = false;
    let history = fixture.history ?? [];

    Object.assign(window, {
      __murmurTestCalls: calls,
      __murmurEmitEvent: (event: string, payload: unknown) => {
        for (const id of listeners.get(event) ?? [])
          callbacks.get(id)?.({ event, id, payload });
      },
      __TAURI_OS_PLUGIN_INTERNALS__: {
        platform: fixture.platform ?? "linux",
        os_type: fixture.platform ?? "linux",
        family: fixture.platform === "windows" ? "windows" : "unix",
        arch: "x86_64",
        eol: "\n",
        exe_extension: "",
      },
      __TAURI_EVENT_PLUGIN_INTERNALS__: {
        unregisterListener: (id: number) => callbacks.delete(id),
      },
      __TAURI_INTERNALS__: {
        metadata: {
          currentWindow: { label: "main" },
          currentWebview: { windowLabel: "main", label: "main" },
        },
        transformCallback: (callback: (event: unknown) => void) => {
          const id = nextCallbackId++;
          callbacks.set(id, callback);
          return id;
        },
        unregisterCallback: (id: number) => callbacks.delete(id),
        convertFileSrc: (path: string) => path,
        invoke: async (command: string, args: Record<string, unknown> = {}) => {
          calls.push({ command, args });
          switch (command) {
            case "plugin:event|listen": {
              if (
                typeof args.event === "string" &&
                typeof args.handler === "number"
              ) {
                const handlers = listeners.get(args.event) ?? new Set<number>();
                handlers.add(args.handler);
                listeners.set(args.event, handlers);
              }
              return args.handler;
            }
            case "plugin:os|locale":
              return "en-US";
            case "plugin:app|version":
              return "0.1.0";
            case "get_app_settings":
            case "get_default_settings":
              return { ...settings };
            case "complete_onboarding":
              settings.onboarding_completed = true;
              return null;
            case "update_custom_words": {
              const words = args.words;
              if (
                !Array.isArray(words) ||
                !words.every((word: unknown) => typeof word === "string")
              )
                throw new Error("Invalid words");
              settings.custom_words = words;
              return null;
            }
            case "get_available_models":
              return fixture.models ?? [];
            case "get_current_model":
              return settings.selected_model ?? "";
            case "get_available_microphones":
            case "get_available_output_devices":
              return [];
            case "get_microphone_channels":
              return 1;
            case "check_custom_sounds":
              return { start: false, stop: false };
            case "is_recording":
              if (fixture.recordingFailure)
                throw new Error("Synthetic capture unavailable");
              return false;
            case "is_update_checks_locked":
            case "is_stt_api_key_configured":
            case "is_post_process_api_key_configured":
              if (fixture.credentialFailure)
                throw new Error("Synthetic credential unavailable");
              return false;
            case "get_windows_microphone_permission_status":
              if (fixture.permissionPollingFailure && openedPrivacySettings)
                throw new Error("Synthetic permission check failure");
              return {
                supported: true,
                overall_access: "denied",
                device_access: "denied",
                app_access: "denied",
                desktop_app_access: "denied",
              };
            case "open_microphone_privacy_settings":
              openedPrivacySettings = true;
              return null;
            case "get_history_entries":
              if (fixture.historyDelayMs)
                await new Promise((resolve) =>
                  setTimeout(resolve, fixture.historyDelayMs),
                );
              if (fixture.historyFailure === "returned")
                throw "Synthetic history error";
              if (fixture.historyFailure === "thrown")
                throw new Error("Synthetic history error");
              return { entries: history, has_more: false };
            case "plugin:dialog|ask":
              return fixture.confirmDelete ?? false;
            case "delete_history_entry":
              if (fixture.deleteFailure) throw "Synthetic deletion error";
              history = history.filter((entry) => entry.id !== args.id);
              return null;
            case "get_history_stats":
              return {
                total_words: 1250,
                transcription_count: 14,
                recordings_with_duration: 10,
                recording_seconds: 120,
                recorded_wpm: 125,
                this_week_words: 340,
                previous_week_words: 250,
                week_over_week_percent: 36,
                weeks: [],
              };
            case "get_openrouter_key_usage":
              return {
                key_count: 1,
                usage: 0.25,
                usage_daily: 0.01,
                usage_weekly: 0.041,
                usage_monthly: 0.11,
                byok_usage: 0,
                byok_usage_daily: 0,
                byok_usage_weekly: 0,
                byok_usage_monthly: 0,
              };
            default:
              return null;
          }
        },
      },
    });
  }, options);
}
