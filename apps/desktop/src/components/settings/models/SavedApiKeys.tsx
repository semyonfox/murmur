import React, { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { invoke } from "@tauri-apps/api/core";
import { KeyRound } from "lucide-react";
import { toast } from "sonner";
import { hostOf } from "@/lib/utils/format";
import { useSettings } from "../../../hooks/useSettings";
import { useSettingsStore } from "../../../stores/settingsStore";
import { Button } from "../../ui/Button";
import { SettingsGroup } from "../../ui/SettingsGroup";

// ollama and apple intelligence never take a key
const KEYLESS_PROVIDERS = new Set(["ollama", "apple_intelligence"]);

type SavedKey =
  | { kind: "speech"; id: "speech"; label: string }
  | { kind: "cleanup"; id: string; label: string };

// null while the credential store is being checked
export const useSavedApiKeys = (): SavedKey[] | null => {
  const { settings } = useSettings();
  const apiKeysVersion = useSettingsStore((state) => state.apiKeysVersion);
  const [savedKeys, setSavedKeys] = useState<SavedKey[] | null>(null);

  const sttBaseUrl = settings?.stt_base_url ?? "";
  const usesEndpoint = settings?.stt_source === "endpoint" && !!sttBaseUrl;
  const providers = settings?.post_process_providers ?? [];
  const providerKey = providers.map((provider) => provider.id).join(",");

  useEffect(() => {
    if (!settings) return;
    let active = true;

    const check = async () => {
      const found: SavedKey[] = [];
      // the speech key is stored per endpoint URL, so only the active one can
      // be checked
      if (
        usesEndpoint &&
        (await invoke<boolean>("is_stt_api_key_configured"))
      ) {
        found.push({
          kind: "speech",
          id: "speech",
          label: hostOf(sttBaseUrl),
        });
      }
      const cleanup = await Promise.all(
        providers
          .filter((provider) => !KEYLESS_PROVIDERS.has(provider.id))
          .map(async (provider) => ({
            provider,
            configured: await invoke<boolean>(
              "is_post_process_api_key_configured",
              { providerId: provider.id },
            ),
          })),
      );
      for (const { provider, configured } of cleanup) {
        if (configured) {
          found.push({
            kind: "cleanup",
            id: provider.id,
            label: provider.label,
          });
        }
      }
      if (active) setSavedKeys(found);
    };

    check().catch((error) => {
      if (active) {
        setSavedKeys([]);
        toast.error(String(error));
      }
    });
    return () => {
      active = false;
    };
    // providerKey stands in for the provider list, which is a new array on
    // every settings refresh
  }, [
    settings !== null,
    usesEndpoint,
    sttBaseUrl,
    providerKey,
    apiKeysVersion,
  ]);

  return savedKeys;
};

export const SavedApiKeys: React.FC = () => {
  const { t } = useTranslation();
  const savedKeys = useSavedApiKeys();
  const bumpApiKeysVersion = useSettingsStore(
    (state) => state.bumpApiKeysVersion,
  );
  const [removing, setRemoving] = useState<string | null>(null);

  const remove = async (key: SavedKey) => {
    setRemoving(key.id);
    try {
      if (key.kind === "speech") {
        await invoke<void>("set_stt_api_key", { apiKey: "" });
      } else {
        await invoke<void>("change_post_process_api_key_setting", {
          providerId: key.id,
          apiKey: "",
        });
      }
      bumpApiKeysVersion();
      toast.success(
        t("murmur.keys.removed", {
          name: key.label,
          defaultValue: "Removed the key for {{name}}",
        }),
      );
    } catch (error) {
      toast.error(String(error));
    } finally {
      setRemoving(null);
    }
  };

  return (
    <SettingsGroup
      title={t("murmur.keys.title", { defaultValue: "Saved API keys" })}
      description={t("murmur.keys.description", {
        defaultValue:
          "Keys are kept in your system credential store, never in Murmur's settings file.",
      })}
    >
      {savedKeys === null ? (
        <p className="px-4 py-3 text-sm text-mid-gray">
          {t("murmur.keys.loading", {
            defaultValue: "Checking the credential store…",
          })}
        </p>
      ) : savedKeys.length === 0 ? (
        <p className="px-4 py-3 text-sm text-mid-gray">
          {t("murmur.keys.none", { defaultValue: "No API keys saved." })}
        </p>
      ) : (
        savedKeys.map((key) => (
          <div
            key={`${key.kind}-${key.id}`}
            className="flex min-h-12 items-center justify-between gap-3 px-4 py-2"
          >
            <div className="flex min-w-0 items-center gap-2.5">
              <KeyRound
                size={15}
                className="shrink-0 text-mid-gray"
                aria-hidden="true"
              />
              <div className="min-w-0">
                <p className="truncate text-sm font-medium">{key.label}</p>
                <p className="text-xs text-mid-gray">
                  {key.kind === "speech"
                    ? t("murmur.keys.speech", {
                        defaultValue: "Speech recognition",
                      })
                    : t("murmur.keys.cleanup", {
                        defaultValue: "AI cleanup",
                      })}
                </p>
              </div>
            </div>
            <Button
              variant="danger-ghost"
              size="sm"
              disabled={removing !== null}
              onClick={() => void remove(key)}
            >
              {t("murmur.keys.remove", { defaultValue: "Remove" })}
            </Button>
          </div>
        ))
      )}
    </SettingsGroup>
  );
};
