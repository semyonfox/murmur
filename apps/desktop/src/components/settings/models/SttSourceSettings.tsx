import React, { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { invoke } from "@tauri-apps/api/core";
import { Cloud, HardDrive } from "lucide-react";
import { toast } from "sonner";
import { Button } from "../../ui/Button";
import { Input } from "../../ui/Input";
import { SettingsGroup } from "../../ui/SettingsGroup";
import { useSettings } from "../../../hooks/useSettings";
import { useSettingsStore } from "../../../stores/settingsStore";

type SttSource = "local" | "endpoint";
type KeyStatus = "loading" | "configured" | "missing" | "error";
type SttConfig = { source: SttSource; baseUrl: string; model: string };

// common services, so people with a key don't need to know base URLs
const SPEECH_PRESETS = [
  {
    id: "openai",
    label: "OpenAI",
    baseUrl: "https://api.openai.com/v1",
    model: "whisper-1",
  },
  {
    id: "groq",
    label: "Groq",
    baseUrl: "https://api.groq.com/openai/v1",
    model: "whisper-large-v3-turbo",
  },
  {
    id: "openrouter",
    label: "OpenRouter",
    baseUrl: "https://openrouter.ai/api/v1",
    model: "openai/whisper-large-v3-turbo",
  },
] as const;

interface SttSourceSettingsProps {
  // lets the page show on-device models for the choice being edited, not
  // only the saved one
  onDraftSourceChange?: (source: SttSource) => void;
}

export const SttSourceSettings: React.FC<SttSourceSettingsProps> = ({
  onDraftSourceChange,
}) => {
  const { t } = useTranslation();
  const { settings, refreshSettings } = useSettings();
  const [source, setSource] = useState<SttSource>("local");
  const [baseUrl, setBaseUrl] = useState("");
  const [model, setModel] = useState("");
  const [isSaving, setIsSaving] = useState(false);
  const [pendingConfig, setPendingConfig] = useState<SttConfig | null>(null);
  const [apiKey, setApiKey] = useState("");
  const [keyStatus, setKeyStatus] = useState<KeyStatus>("loading");
  const [keyLookupVersion, setKeyLookupVersion] = useState(0);
  const [isKeySaving, setIsKeySaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const apiKeysVersion = useSettingsStore((state) => state.apiKeysVersion);
  const bumpApiKeysVersion = useSettingsStore(
    (state) => state.bumpApiKeysVersion,
  );

  useEffect(() => {
    if (!settings) return;
    setSource(settings.stt_source ?? "local");
    setBaseUrl(settings.stt_base_url ?? "");
    setModel(settings.stt_model ?? "");
  }, [settings?.stt_source, settings?.stt_base_url, settings?.stt_model]);

  useEffect(() => {
    setApiKey("");
  }, [settings?.stt_source, settings?.stt_base_url]);

  useEffect(() => {
    onDraftSourceChange?.(source);
  }, [source, onDraftSourceChange]);

  const savedSource: SttSource = settings?.stt_source ?? "local";
  const activePreset = SPEECH_PRESETS.find(
    (preset) => preset.baseUrl === baseUrl.trim().replace(/\/+$/, ""),
  );
  const inUseBadge = (
    <span className="ms-auto shrink-0 self-start rounded-full bg-mid-gray/15 px-2 py-0.5 text-[11px] font-medium text-text/80">
      {t("murmur.stt.inUse", { defaultValue: "In use" })}
    </span>
  );

  useEffect(() => {
    if (settings?.stt_source !== "endpoint" || !settings.stt_base_url) {
      setKeyStatus("missing");
      return;
    }
    let active = true;
    setKeyStatus("loading");
    void invoke<boolean>("is_stt_api_key_configured")
      .then((configured) => {
        if (active) setKeyStatus(configured ? "configured" : "missing");
      })
      .catch((cause) => {
        if (active) {
          setKeyStatus("error");
          toast.error(String(cause));
        }
      });
    return () => {
      active = false;
    };
  }, [
    settings?.stt_source,
    settings?.stt_base_url,
    keyLookupVersion,
    apiKeysVersion,
  ]);

  const isDirty =
    settings !== null &&
    (source !== (settings.stt_source ?? "local") ||
      baseUrl.trim() !== (settings.stt_base_url ?? "") ||
      model.trim() !== (settings.stt_model ?? ""));
  // while the URL or model is unsaved, a typed key is saved with them on
  // submit, because keys are stored against the saved endpoint URL
  const canEditKey =
    source === "endpoint" && !isSaving && !isKeySaving && !pendingConfig;
  const canSaveKeyAlone =
    canEditKey && settings?.stt_source === "endpoint" && !isDirty;

  const saveKey = async () => {
    if (!canSaveKeyAlone || !apiKey.trim()) return;
    setIsKeySaving(true);
    try {
      await invoke<void>("set_stt_api_key", { apiKey: apiKey.trim() });
      setApiKey("");
      setKeyStatus("loading");
      bumpApiKeysVersion();
      toast.success(
        t("murmur.stt.keySaved", { defaultValue: "Speech API key saved" }),
      );
    } catch (cause) {
      toast.error(String(cause));
    } finally {
      setIsKeySaving(false);
    }
  };

  const removeKey = async () => {
    if (!canSaveKeyAlone) return;
    setIsKeySaving(true);
    try {
      await invoke<void>("set_stt_api_key", { apiKey: "" });
      setApiKey("");
      setKeyStatus("loading");
      bumpApiKeysVersion();
      toast.success(
        t("murmur.stt.keyRemoved", { defaultValue: "Speech API key removed" }),
      );
    } catch (cause) {
      toast.error(String(cause));
    } finally {
      setIsKeySaving(false);
    }
  };

  const refreshSavedConfig = async (expected: SttConfig) => {
    await refreshSettings();
    const current = useSettingsStore.getState().settings;
    if (
      current?.stt_source !== expected.source ||
      current.stt_base_url !== expected.baseUrl ||
      current.stt_model !== expected.model
    ) {
      throw new Error("Saved speech settings could not be reloaded");
    }
  };

  const retryRefresh = async () => {
    if (!pendingConfig || isSaving) return;
    setIsSaving(true);
    try {
      await refreshSavedConfig(pendingConfig);
      setPendingConfig(null);
      setError(null);
      toast.success(
        t("murmur.stt.saved", { defaultValue: "Speech source saved" }),
      );
    } catch {
      setError(
        t("murmur.stt.refreshFailed", {
          defaultValue:
            "Speech source was saved, but settings could not be reloaded. Retry loading settings.",
        }),
      );
    } finally {
      setIsSaving(false);
    }
  };

  const save = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!settings || !isDirty || isSaving || isKeySaving || pendingConfig)
      return;

    const nextConfig: SttConfig = {
      source,
      baseUrl: baseUrl.trim(),
      model: model.trim(),
    };
    const pendingKey = source === "endpoint" ? apiKey.trim() : "";
    setError(null);
    setIsSaving(true);
    try {
      await invoke<void>("set_stt_config", nextConfig);
    } catch (cause) {
      setError(String(cause));
      setIsSaving(false);
      return;
    }

    setPendingConfig(nextConfig);
    try {
      await refreshSavedConfig(nextConfig);
      setPendingConfig(null);
      if (pendingKey) {
        try {
          await invoke<void>("set_stt_api_key", { apiKey: pendingKey });
          setApiKey("");
          bumpApiKeysVersion();
        } catch (cause) {
          toast.error(String(cause));
        }
      }
      toast.success(
        t("murmur.stt.saved", { defaultValue: "Speech source saved" }),
      );
    } catch {
      setError(
        t("murmur.stt.refreshFailed", {
          defaultValue:
            "Speech source was saved, but settings could not be reloaded. Retry loading settings.",
        }),
      );
    } finally {
      setIsSaving(false);
    }
  };

  return (
    <SettingsGroup
      title={t("murmur.stt.title", { defaultValue: "Speech recognition" })}
      description={t("murmur.stt.description", {
        defaultValue: "Choose where your speech is turned into text.",
      })}
    >
      <form onSubmit={save} className="space-y-4 p-4">
        <fieldset
          disabled={!settings || isSaving || isKeySaving || !!pendingConfig}
        >
          <legend className="mb-2 text-sm font-medium text-text">
            {t("murmur.stt.source", { defaultValue: "Transcription source" })}
          </legend>
          <div className="grid gap-2 sm:grid-cols-2">
            <label
              className={`flex cursor-pointer gap-3 rounded-xl border p-3 transition-colors focus-within:ring-2 focus-within:ring-logo-primary/40 ${
                source === "local"
                  ? "border-logo-primary/60 bg-mid-gray/10"
                  : "border-mid-gray/20 hover:border-mid-gray/45"
              }`}
            >
              <input
                type="radio"
                name="stt-source"
                value="local"
                checked={source === "local"}
                onChange={() => {
                  setSource("local");
                  setApiKey("");
                  setError(null);
                }}
                className="sr-only"
              />
              <HardDrive
                size={19}
                className="mt-0.5 shrink-0"
                aria-hidden="true"
              />
              <span>
                <span className="block text-sm font-semibold text-text">
                  {t("murmur.stt.local.title", {
                    defaultValue: "On this device",
                  })}
                </span>
                <span className="mt-0.5 block text-xs leading-relaxed text-mid-gray">
                  {t("murmur.stt.local.description", {
                    defaultValue:
                      "Pick or download a model below. Audio never leaves this computer.",
                  })}
                </span>
              </span>
              {savedSource === "local" && inUseBadge}
            </label>
            <label
              className={`flex cursor-pointer gap-3 rounded-xl border p-3 transition-colors focus-within:ring-2 focus-within:ring-logo-primary/40 ${
                source === "endpoint"
                  ? "border-logo-primary/60 bg-mid-gray/10"
                  : "border-mid-gray/20 hover:border-mid-gray/45"
              }`}
            >
              <input
                type="radio"
                name="stt-source"
                value="endpoint"
                checked={source === "endpoint"}
                onChange={() => {
                  setSource("endpoint");
                  setApiKey("");
                  setError(null);
                }}
                className="sr-only"
              />
              <Cloud size={19} className="mt-0.5 shrink-0" aria-hidden="true" />
              <span>
                <span className="block text-sm font-semibold text-text">
                  {t("murmur.stt.endpoint.title", {
                    defaultValue: "Cloud API or your own server",
                  })}
                </span>
                <span className="mt-0.5 block text-xs leading-relaxed text-mid-gray">
                  {t("murmur.stt.endpoint.description", {
                    defaultValue:
                      "OpenAI, Groq or any OpenAI-compatible service. Needs an API key unless it runs locally.",
                  })}
                </span>
              </span>
              {savedSource === "endpoint" && inUseBadge}
            </label>
          </div>
        </fieldset>

        {source === "endpoint" && (
          <div className="space-y-3 rounded-xl border border-mid-gray/20 bg-mid-gray/5 p-4">
            <fieldset
              className="space-y-1.5"
              disabled={isSaving || isKeySaving || !!pendingConfig}
            >
              <legend className="mb-1.5 text-sm font-medium">
                {t("murmur.stt.service", { defaultValue: "Service" })}
              </legend>
              <div className="flex flex-wrap gap-1.5">
                {SPEECH_PRESETS.map((preset) => (
                  <button
                    key={preset.id}
                    type="button"
                    aria-pressed={activePreset?.id === preset.id}
                    onClick={() => {
                      setBaseUrl(preset.baseUrl);
                      setModel(preset.model);
                      setApiKey("");
                      setError(null);
                    }}
                    className={`cursor-pointer rounded-lg border px-3 py-1.5 text-sm transition-colors ${
                      activePreset?.id === preset.id
                        ? "border-logo-primary/60 bg-background font-medium text-text"
                        : "border-mid-gray/25 text-text/80 hover:border-mid-gray/50"
                    }`}
                  >
                    {preset.label}
                  </button>
                ))}
                <span className="self-center ps-1 text-xs text-mid-gray">
                  {t("murmur.stt.presetOther", {
                    defaultValue: "or enter any compatible server below",
                  })}
                </span>
              </div>
            </fieldset>
            <div className="space-y-1">
              <label
                htmlFor="stt-base-url"
                className="block text-sm font-medium"
              >
                {t("murmur.stt.baseUrl", { defaultValue: "API base URL" })}
              </label>
              <Input
                id="stt-base-url"
                type="url"
                value={baseUrl}
                onChange={(event) => {
                  setBaseUrl(event.target.value);
                  setApiKey("");
                }}
                placeholder="https://api.groq.com/openai/v1"
                className="w-full font-normal"
                spellCheck={false}
                required
                disabled={isSaving || isKeySaving || !!pendingConfig}
              />
              <p className="text-xs leading-relaxed text-mid-gray">
                {t("murmur.stt.baseUrlHelp", {
                  defaultValue:
                    "For a server on this computer, try http://127.0.0.1:8000/v1. A full /audio/transcriptions URL also works.",
                })}
              </p>
            </div>
            <div className="space-y-1">
              <label htmlFor="stt-model" className="block text-sm font-medium">
                {t("murmur.stt.model", { defaultValue: "Model ID" })}
              </label>
              <Input
                id="stt-model"
                type="text"
                value={model}
                onChange={(event) => setModel(event.target.value)}
                placeholder="whisper-large-v3-turbo"
                className="w-full font-normal"
                spellCheck={false}
                required
                maxLength={256}
                disabled={isSaving || isKeySaving || !!pendingConfig}
              />
              <p className="text-xs text-mid-gray">
                {t("murmur.stt.modelHelp", {
                  defaultValue:
                    "Use the model name your speech server accepts.",
                })}
              </p>
            </div>
            <div className="space-y-2 border-t border-mid-gray/20 pt-3">
              <label
                htmlFor="stt-api-key"
                className="block text-sm font-medium"
              >
                {t("murmur.stt.apiKey", { defaultValue: "API key" })}
              </label>
              <div className="flex flex-wrap items-center gap-2">
                <Input
                  id="stt-api-key"
                  type="password"
                  value={apiKey}
                  onChange={(event) => setApiKey(event.target.value)}
                  placeholder={t("murmur.stt.apiKeyPlaceholder", {
                    defaultValue: "Paste your key",
                  })}
                  autoComplete="off"
                  spellCheck={false}
                  disabled={!canEditKey}
                  className="min-w-[220px] flex-1"
                />
                {!isDirty && (
                  <Button
                    type="button"
                    variant="secondary"
                    disabled={!canSaveKeyAlone || !apiKey.trim()}
                    onClick={saveKey}
                  >
                    {t("murmur.stt.saveKey", { defaultValue: "Save key" })}
                  </Button>
                )}
                {keyStatus === "configured" && !isDirty && (
                  <Button
                    type="button"
                    variant="secondary"
                    disabled={!canSaveKeyAlone}
                    onClick={removeKey}
                  >
                    {t("murmur.stt.removeKey", { defaultValue: "Remove" })}
                  </Button>
                )}
              </div>
              <p className="text-xs leading-relaxed text-mid-gray">
                {isDirty
                  ? t("murmur.stt.keySavedWithSource", {
                      defaultValue:
                        "Your key is saved with the URL and model when you press Save.",
                    })
                  : keyStatus === "configured"
                    ? t("murmur.stt.keyStored", {
                        defaultValue:
                          "A key is saved for this endpoint in the system credential store.",
                      })
                    : keyStatus === "missing"
                      ? t("murmur.stt.noKey", {
                          defaultValue:
                            "No key saved. Leave it empty only for a server that does not need one.",
                        })
                      : keyStatus === "error"
                        ? t("murmur.stt.keyStatusError", {
                            defaultValue:
                              "Could not check the system credential store. Retry below.",
                          })
                        : t("murmur.stt.keyStatusUnknown", {
                            defaultValue:
                              "Checking the system credential store…",
                          })}
              </p>
              {keyStatus === "error" && !isDirty && (
                <Button
                  type="button"
                  variant="secondary"
                  disabled={isKeySaving || isSaving || !!pendingConfig}
                  onClick={() => setKeyLookupVersion((version) => version + 1)}
                >
                  {t("murmur.stt.retryKeyCheck", {
                    defaultValue: "Retry key check",
                  })}
                </Button>
              )}
            </div>
          </div>
        )}

        {(source === "endpoint" || isDirty || !!error || !!pendingConfig) && (
          <div className="space-y-3 border-t border-mid-gray/20 pt-3">
            {source === "endpoint" && (
              <p className="text-xs leading-relaxed text-mid-gray">
                {t("murmur.stt.endpoint.notice", {
                  defaultValue:
                    "Each recording is sent to this service, which may charge for usage. Murmur never switches to another service on its own.",
                })}
              </p>
            )}
            {error && (
              <p role="alert" className="text-sm text-error">
                {error}
              </p>
            )}
            <div className="flex justify-end gap-2">
              {pendingConfig && (
                <Button
                  type="button"
                  variant="secondary"
                  disabled={isSaving}
                  onClick={retryRefresh}
                >
                  {t("murmur.stt.retryRefresh", {
                    defaultValue: "Retry loading settings",
                  })}
                </Button>
              )}
              {(isDirty || isSaving) && (
                <Button
                  type="submit"
                  disabled={
                    !settings ||
                    !isDirty ||
                    isSaving ||
                    isKeySaving ||
                    !!pendingConfig
                  }
                >
                  {isSaving
                    ? t("murmur.stt.saving", { defaultValue: "Saving…" })
                    : t("murmur.stt.save", { defaultValue: "Save" })}
                </Button>
              )}
            </div>
          </div>
        )}
      </form>
    </SettingsGroup>
  );
};
