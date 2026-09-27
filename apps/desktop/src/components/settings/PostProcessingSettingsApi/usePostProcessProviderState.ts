import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { toast } from "sonner";
import { useSettingsStore } from "../../../stores/settingsStore";
import { useSettings } from "../../../hooks/useSettings";
import { commands, type PostProcessProvider } from "@/bindings";
import type { ModelOption } from "./types";
import type { DropdownOption } from "../../ui/Dropdown";

type PostProcessProviderState = {
  providerOptions: DropdownOption[];
  selectedProviderId: string;
  selectedProvider: PostProcessProvider | undefined;
  isCustomProvider: boolean;
  isAppleProvider: boolean;
  appleIntelligenceUnavailable: boolean;
  baseUrl: string;
  handleBaseUrlChange: (value: string) => void;
  isBaseUrlUpdating: boolean;
  apiKey: string;
  apiKeyConfigured: boolean | null;
  apiKeyInputVersion: number;
  handleApiKeyChange: (value: string) => void;
  handleApiKeyRemove: () => void;
  isApiKeyUpdating: boolean;
  model: string;
  handleModelChange: (value: string) => void;
  modelOptions: ModelOption[];
  isModelUpdating: boolean;
  isFetchingModels: boolean;
  handleProviderSelect: (providerId: string) => void;
  handleModelSelect: (value: string) => void;
  handleModelCreate: (value: string) => void;
  handleRefreshModels: () => void;
};

const APPLE_PROVIDER_ID = "apple_intelligence";

export const usePostProcessProviderState = (): PostProcessProviderState => {
  const {
    settings,
    isUpdating,
    setPostProcessProvider,
    updatePostProcessBaseUrl,
    updatePostProcessModel,
    fetchPostProcessModels,
    postProcessModelOptions,
  } = useSettings();
  const apiKeysVersion = useSettingsStore((state) => state.apiKeysVersion);
  const bumpApiKeysVersion = useSettingsStore(
    (state) => state.bumpApiKeysVersion,
  );

  // Settings are guaranteed to have providers after migration
  const providers = settings?.post_process_providers || [];

  const selectedProviderId = useMemo(() => {
    return settings?.post_process_provider_id || providers[0]?.id || "openai";
  }, [providers, settings?.post_process_provider_id]);

  const selectedProvider = useMemo(() => {
    return (
      providers.find((provider) => provider.id === selectedProviderId) ||
      providers[0]
    );
  }, [providers, selectedProviderId]);

  const isAppleProvider = selectedProvider?.id === APPLE_PROVIDER_ID;
  const [appleIntelligenceUnavailable, setAppleIntelligenceUnavailable] =
    useState(false);
  const [keyStatus, setKeyStatus] = useState<Record<string, boolean | null>>(
    {},
  );
  const [isApiKeyUpdating, setIsApiKeyUpdating] = useState(false);
  const [apiKeyInputVersion, setApiKeyInputVersion] = useState(0);
  const keyUpdateInFlight = useRef(false);
  const keyStatusRequestVersion = useRef<Record<string, number>>({});

  // Use settings directly as single source of truth
  const baseUrl = selectedProvider?.base_url ?? "";
  const apiKey = "";
  const apiKeyConfigured = keyStatus[selectedProviderId] ?? null;
  const model = settings?.post_process_models?.[selectedProviderId] ?? "";

  useEffect(() => {
    if (!settings) return;
    const requestVersion =
      (keyStatusRequestVersion.current[selectedProviderId] ?? 0) + 1;
    keyStatusRequestVersion.current[selectedProviderId] = requestVersion;
    if (selectedProviderId === "ollama" || isAppleProvider) {
      setKeyStatus((current) => ({ ...current, [selectedProviderId]: false }));
      return;
    }

    let active = true;
    void invoke<boolean>("is_post_process_api_key_configured", {
      providerId: selectedProviderId,
    })
      .then((configured) => {
        if (
          active &&
          requestVersion === keyStatusRequestVersion.current[selectedProviderId]
        ) {
          setKeyStatus((current) => ({
            ...current,
            [selectedProviderId]: configured,
          }));
        }
      })
      .catch((error) => {
        if (
          active &&
          requestVersion === keyStatusRequestVersion.current[selectedProviderId]
        ) {
          setKeyStatus((current) => ({
            ...current,
            [selectedProviderId]: null,
          }));
          toast.error(`Could not check API key: ${String(error)}`);
        }
      });

    return () => {
      active = false;
    };
  }, [selectedProviderId, isAppleProvider, settings !== null, apiKeysVersion]);

  const providerOptions = useMemo<DropdownOption[]>(() => {
    return providers.map((provider) => ({
      value: provider.id,
      label: provider.label,
    }));
  }, [providers]);

  const handleProviderSelect = useCallback(
    async (providerId: string) => {
      // Clear error state on any selection attempt (allows dismissing the error)
      setAppleIntelligenceUnavailable(false);

      if (providerId === selectedProviderId) return;

      // Check Apple Intelligence availability before selecting
      if (providerId === APPLE_PROVIDER_ID) {
        const available = await commands.checkAppleIntelligenceAvailable();
        if (!available) {
          setAppleIntelligenceUnavailable(true);
          // Don't return - still set the provider so dropdown shows the selection
          // The backend gracefully handles unavailable Apple Intelligence
        }
      }

      await setPostProcessProvider(providerId);

      // Auto-fetch available models for the new provider so the model dropdown
      // reflects what's actually valid. Without this, a stale model value from
      // a previous provider/base_url can persist and silently 404 at runtime.
      // Skip when the provider isn't configured yet (no API key / empty base URL)
      // to avoid unnecessary backend errors.
      if (providerId !== APPLE_PROVIDER_ID) {
        const provider = providers.find((p) => p.id === providerId);
        const hasBaseUrl = (provider?.base_url ?? "").trim() !== "";
        if (provider?.allow_base_url_edit && hasBaseUrl) {
          void fetchPostProcessModels(providerId);
        } else if (provider && !provider.allow_base_url_edit) {
          try {
            const configured = await invoke<boolean>(
              "is_post_process_api_key_configured",
              { providerId },
            );
            setKeyStatus((current) => ({
              ...current,
              [providerId]: configured,
            }));
            if (configured) void fetchPostProcessModels(providerId);
          } catch (error) {
            toast.error(`Could not check API key: ${String(error)}`);
          }
        }
      }
    },
    [
      selectedProviderId,
      setPostProcessProvider,
      fetchPostProcessModels,
      providers,
    ],
  );

  const handleBaseUrlChange = useCallback(
    (value: string) => {
      if (!selectedProvider?.allow_base_url_edit) {
        return;
      }
      const trimmed = value.trim();
      if (trimmed && trimmed !== baseUrl) {
        void updatePostProcessBaseUrl(selectedProvider.id, trimmed);
      }
    },
    [selectedProvider, baseUrl, updatePostProcessBaseUrl],
  );

  const changeApiKey = useCallback(
    async (value: string) => {
      if (keyUpdateInFlight.current) return;
      keyUpdateInFlight.current = true;
      keyStatusRequestVersion.current[selectedProviderId] =
        (keyStatusRequestVersion.current[selectedProviderId] ?? 0) + 1;
      setIsApiKeyUpdating(true);
      try {
        await invoke<void>("change_post_process_api_key_setting", {
          providerId: selectedProviderId,
          apiKey: value,
        });
        setKeyStatus((current) => ({
          ...current,
          [selectedProviderId]: value.length > 0,
        }));
        setApiKeyInputVersion((version) => version + 1);
        bumpApiKeysVersion();
      } catch (error) {
        toast.error(`Could not update API key: ${String(error)}`);
      } finally {
        keyUpdateInFlight.current = false;
        setIsApiKeyUpdating(false);
      }
    },
    [selectedProviderId, bumpApiKeysVersion],
  );

  const handleApiKeyChange = useCallback(
    (value: string) => {
      const trimmed = value.trim();
      if (trimmed) void changeApiKey(trimmed);
    },
    [changeApiKey],
  );

  const handleApiKeyRemove = useCallback(() => {
    void changeApiKey("");
  }, [changeApiKey]);

  const handleModelChange = useCallback(
    (value: string) => {
      const trimmed = value.trim();
      if (trimmed !== model) {
        void updatePostProcessModel(selectedProviderId, trimmed);
      }
    },
    [model, selectedProviderId, updatePostProcessModel],
  );

  const handleModelSelect = useCallback(
    (value: string) => {
      void updatePostProcessModel(selectedProviderId, value.trim());
    },
    [selectedProviderId, updatePostProcessModel],
  );

  const handleModelCreate = useCallback(
    (value: string) => {
      void updatePostProcessModel(selectedProviderId, value);
    },
    [selectedProviderId, updatePostProcessModel],
  );

  const handleRefreshModels = useCallback(() => {
    if (isAppleProvider) return;
    void fetchPostProcessModels(selectedProviderId);
  }, [fetchPostProcessModels, isAppleProvider, selectedProviderId]);

  const availableModelsRaw = postProcessModelOptions[selectedProviderId] || [];

  const modelOptions = useMemo<ModelOption[]>(() => {
    const seen = new Set<string>();
    const options: ModelOption[] = [];

    const upsert = (value: string | null | undefined) => {
      const trimmed = value?.trim();
      if (!trimmed || seen.has(trimmed)) return;
      seen.add(trimmed);
      options.push({ value: trimmed, label: trimmed });
    };

    // Add available models from API
    for (const candidate of availableModelsRaw) {
      upsert(candidate);
    }

    // Ensure current model is in the list
    upsert(model);

    return options;
  }, [availableModelsRaw, model]);

  const isBaseUrlUpdating = isUpdating(
    `post_process_base_url:${selectedProviderId}`,
  );
  const isModelUpdating = isUpdating(
    `post_process_model:${selectedProviderId}`,
  );
  const isFetchingModels = isUpdating(
    `post_process_models_fetch:${selectedProviderId}`,
  );

  const isCustomProvider = selectedProvider?.allow_base_url_edit ?? false;

  return {
    providerOptions,
    selectedProviderId,
    selectedProvider,
    isCustomProvider,
    isAppleProvider,
    appleIntelligenceUnavailable,
    baseUrl,
    handleBaseUrlChange,
    isBaseUrlUpdating,
    apiKey,
    apiKeyConfigured,
    apiKeyInputVersion,
    handleApiKeyChange,
    handleApiKeyRemove,
    isApiKeyUpdating,
    model,
    handleModelChange,
    modelOptions,
    isModelUpdating,
    isFetchingModels,
    handleProviderSelect,
    handleModelSelect,
    handleModelCreate,
    handleRefreshModels,
  };
};
