import React, { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { type } from "@tauri-apps/plugin-os";
import { Cloud, HardDrive } from "lucide-react";
import { commands } from "@/bindings";
import { useOsType } from "@/hooks/useOsType";
import { hostOf, isLoopbackEndpoint } from "@/lib/utils/format";
import { formatKeyCombination } from "@/lib/utils/keyboard";
import { useModelStore } from "@/stores/modelStore";
import { useSettings } from "../../../hooks/useSettings";
import { SettingsGroup } from "../../ui/SettingsGroup";
import { AudioFeedback } from "../AudioFeedback";
import { ChannelSelector } from "../ChannelSelector";
import { MicrophoneSelector } from "../MicrophoneSelector";
import { MuteWhileRecording } from "../MuteWhileRecording";
import { ShortcutActivationSetting } from "../ShortcutActivation";
import { ShortcutInput } from "../ShortcutInput";
import { ShowOverlay } from "../ShowOverlay";
import { SoundPicker } from "../SoundPicker";
import { VoiceActivityDetection } from "../VoiceActivityDetection";
import { useSettingsNavigation } from "../navigation";
import { SpokenLanguage } from "./SpokenLanguage";

const useIsRecording = () => {
  const [isRecording, setIsRecording] = useState<boolean | null>(null);

  useEffect(() => {
    let active = true;

    const refreshRecording = async () => {
      if (document.visibilityState !== "visible") return;
      try {
        const recording = await commands.isRecording();
        if (active) setIsRecording(recording);
      } catch {
        if (active) setIsRecording(null);
      }
    };

    void refreshRecording();
    const timer = window.setInterval(refreshRecording, 1000);
    document.addEventListener("visibilitychange", refreshRecording);

    return () => {
      active = false;
      window.clearInterval(timer);
      document.removeEventListener("visibilitychange", refreshRecording);
    };
  }, []);

  return isRecording;
};

// one glance answers "is it ready" and "where does my voice go"
const StatusCard: React.FC = () => {
  const { t } = useTranslation();
  const { settings } = useSettings();
  const { currentModel, models, loading: modelsLoading } = useModelStore();
  const osType = useOsType();
  const navigate = useSettingsNavigation();
  const isRecording = useIsRecording();

  const shortcut = settings?.bindings?.transcribe?.current_binding;
  const modelName =
    models.find((model) => model.id === currentModel)?.name ?? currentModel;
  const usesEndpoint = settings?.stt_source === "endpoint";
  const cleanupProvider = settings?.post_process_providers?.find(
    (provider) => provider.id === settings.post_process_provider_id,
  );
  const cleanupIsLocal =
    cleanupProvider?.id === "apple_intelligence" ||
    isLoopbackEndpoint(cleanupProvider?.base_url ?? "");
  const needsModel = !usesEndpoint && !currentModel && !modelsLoading;
  const shortcutLabel = shortcut ? formatKeyCombination(shortcut, osType) : "";
  const activation = settings?.shortcut_activation ?? "hold_or_toggle";
  const startInstruction =
    activation === "push_to_talk"
      ? t("murmur.dictation.status.hold", {
          shortcut: shortcutLabel,
          defaultValue: "Hold {{shortcut}} to dictate; release to finish",
        })
      : activation === "toggle"
        ? t("murmur.dictation.status.toggle", {
            shortcut: shortcutLabel,
            defaultValue: "Press {{shortcut}} to start; press again to finish",
          })
        : t("murmur.dictation.status.holdOrToggle", {
            shortcut: shortcutLabel,
            defaultValue:
              "Hold {{shortcut}}, or tap to start and tap again to finish",
          });

  const speechLine = usesEndpoint
    ? t("murmur.dictation.status.speechCloud", {
        host: hostOf(settings?.stt_base_url ?? ""),
        defaultValue: "Audio is sent to {{host}} for transcription",
      })
    : t("murmur.dictation.status.speechLocal", {
        model:
          modelName ||
          t("murmur.dictation.status.noModel", {
            defaultValue: "no model selected",
          }),
        defaultValue: "Audio stays on this device ({{model}})",
      });

  const cleanupLine = !settings?.post_process_enabled
    ? t("murmur.dictation.status.cleanupOff", {
        defaultValue: "AI cleanup is off",
      })
    : cleanupIsLocal
      ? t("murmur.dictation.status.cleanupLocal", {
          provider:
            hostOf(cleanupProvider?.base_url ?? "") ||
            cleanupProvider?.label ||
            "",
          defaultValue: "AI cleanup runs locally with {{provider}}",
        })
      : t("murmur.dictation.status.cleanupCloud", {
          provider:
            hostOf(cleanupProvider?.base_url ?? "") ||
            cleanupProvider?.label ||
            "",
          defaultValue: "Transcript text is sent to {{provider}} for cleanup",
        });

  return (
    <section className="rounded-xl border border-mid-gray/15 bg-surface px-4 py-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div
          className="flex items-center gap-2 text-sm font-medium text-text"
          role="status"
          aria-live="polite"
        >
          <span
            className={`h-2 w-2 rounded-full ${isRecording ? "bg-logo-primary animate-pulse" : "bg-mid-gray/60"}`}
            aria-hidden="true"
          />
          {isRecording
            ? t("murmur.general.status.recording", {
                defaultValue: "Recording now",
              })
            : needsModel
              ? t("murmur.dictation.status.chooseModel", {
                  defaultValue: "Choose a recognition model to start",
                })
              : modelsLoading || !settings || isRecording === null
                ? t("murmur.dictation.status.checking", {
                    defaultValue: "Checking dictation status…",
                  })
                : shortcut
                  ? startInstruction
                  : t("murmur.general.status.idle", {
                      defaultValue: "Not recording",
                    })}
        </div>
        <button
          type="button"
          onClick={() => navigate("models")}
          className="cursor-pointer rounded-md px-1.5 py-0.5 text-xs font-medium text-logo-primary hover:bg-mid-gray/10 max-sm:min-h-11"
        >
          {t("murmur.dictation.status.change", {
            defaultValue: "Change models",
          })}
        </button>
      </div>
      {isRecording && shortcut && (
        <p className="mt-1 text-xs text-mid-gray">
          {activation === "push_to_talk"
            ? t("murmur.dictation.status.release", {
                defaultValue: "Release the shortcut to finish.",
              })
            : activation === "toggle"
              ? t("murmur.dictation.status.pressAgain", {
                  defaultValue: "Press the shortcut again to finish.",
                })
              : t("murmur.dictation.status.releaseOrPress", {
                  defaultValue:
                    "Release if holding, or tap the shortcut again to finish.",
                })}
        </p>
      )}
      <ul className="mt-2 space-y-1 text-xs text-mid-gray">
        <li className="flex items-center gap-2">
          {usesEndpoint ? (
            <Cloud size={13} aria-hidden="true" />
          ) : (
            <HardDrive size={13} aria-hidden="true" />
          )}
          {speechLine}
        </li>
        <li className="flex items-center gap-2">
          {settings?.post_process_enabled && !cleanupIsLocal ? (
            <Cloud size={13} aria-hidden="true" />
          ) : (
            <HardDrive size={13} aria-hidden="true" />
          )}
          {cleanupLine}
        </li>
      </ul>
    </section>
  );
};

export const DictationSettings: React.FC = () => {
  const { t } = useTranslation();
  const { settings } = useSettings();
  const isLinux = type() === "linux";

  return (
    <div className="w-full space-y-6">
      <StatusCard />

      <SettingsGroup
        title={t("murmur.dictation.shortcut.title", {
          defaultValue: "Shortcut",
        })}
      >
        <ShortcutInput shortcutId="transcribe" grouped={true} />
        <ShortcutActivationSetting descriptionMode="tooltip" grouped={true} />
        {/* Cancel shortcut remains hidden on Linux because of dynamic shortcut instability. */}
        {!isLinux && <ShortcutInput shortcutId="cancel" grouped={true} />}
      </SettingsGroup>

      <SettingsGroup
        title={t("murmur.dictation.microphone.title", {
          defaultValue: "Microphone",
        })}
      >
        <MicrophoneSelector descriptionMode="tooltip" grouped={true} />
        <ChannelSelector descriptionMode="tooltip" grouped={true} />
        <MuteWhileRecording descriptionMode="tooltip" grouped={true} />
        <VoiceActivityDetection descriptionMode="tooltip" grouped={true} />
      </SettingsGroup>

      {settings?.stt_source !== "endpoint" && <SpokenLanguage />}

      <SettingsGroup
        title={t("murmur.general.indicator.title", {
          defaultValue: "Recording indicator",
        })}
      >
        <ShowOverlay descriptionMode="tooltip" grouped={true} />
      </SettingsGroup>

      <SettingsGroup
        title={t("murmur.dictation.sounds.title", { defaultValue: "Sounds" })}
      >
        <AudioFeedback descriptionMode="tooltip" grouped={true} />
        <SoundPicker
          label={t("murmur.dictation.sounds.theme", { defaultValue: "Sound" })}
          description={t("murmur.dictation.sounds.themeDescription", {
            defaultValue: "Recording cues use your system output and volume.",
          })}
        />
      </SettingsGroup>
    </div>
  );
};
