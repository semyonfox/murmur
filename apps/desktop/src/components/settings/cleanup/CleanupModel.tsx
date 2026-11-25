import React from "react";
import { useTranslation } from "react-i18next";
import { RefreshCcw } from "lucide-react";
import { Alert } from "../../ui/Alert";
import { Button } from "../../ui/Button";
import { Input } from "../../ui/Input";
import { ResetButton } from "../../ui/ResetButton";
import { SettingContainer } from "../../ui/SettingContainer";
import { ProviderSelect } from "../PostProcessingSettingsApi/ProviderSelect";
import { BaseUrlField } from "../PostProcessingSettingsApi/BaseUrlField";
import { ApiKeyField } from "../PostProcessingSettingsApi/ApiKeyField";
import { ModelSelect } from "../PostProcessingSettingsApi/ModelSelect";
import type { usePostProcessProviderState } from "../PostProcessingSettingsApi/usePostProcessProviderState";

interface CleanupModelProps {
  state: ReturnType<typeof usePostProcessProviderState>;
  unavailableReasonId?: string;
}

export const CleanupModel: React.FC<CleanupModelProps> = ({
  state,
  unavailableReasonId,
}) => {
  const { t } = useTranslation();
  const modelPlaceholder = state.modelOptions.length
    ? t("settings.postProcessing.api.model.placeholderWithOptions")
    : t("settings.postProcessing.api.model.placeholderNoOptions");

  return (
    <>
      <SettingContainer
        title={t("settings.postProcessing.api.provider.title")}
        description={t("settings.postProcessing.api.provider.description")}
        descriptionMode="tooltip"
        layout="horizontal"
        grouped={true}
      >
        <div className="flex items-center gap-2">
          <ProviderSelect
            options={state.providerOptions}
            value={state.selectedProviderId}
            onChange={state.handleProviderSelect}
            label={t("settings.postProcessing.api.provider.title")}
            placeholder={t("murmur.cleanup.model.none")}
            unavailableReasonId={unavailableReasonId}
          />
        </div>
      </SettingContainer>

      {state.isAppleProvider ? (
        state.appleIntelligenceUnavailable ? (
          <Alert variant="error" contained>
            {t("settings.postProcessing.api.appleIntelligence.unavailable")}
          </Alert>
        ) : null
      ) : (
        <>
          {state.selectedProvider?.allow_base_url_edit && (
            <SettingContainer
              title={t("settings.postProcessing.api.baseUrl.title")}
              description={t("settings.postProcessing.api.baseUrl.description")}
              descriptionMode="tooltip"
              layout="horizontal"
              grouped={true}
            >
              <div className="flex items-center gap-2">
                <BaseUrlField
                  label={t("settings.postProcessing.api.baseUrl.title")}
                  key={state.selectedProviderId}
                  value={state.baseUrl}
                  onBlur={state.handleBaseUrlChange}
                  placeholder={t(
                    "settings.postProcessing.api.baseUrl.placeholder",
                  )}
                  disabled={state.isBaseUrlUpdating}
                  unavailableReasonId={unavailableReasonId}
                  className="min-w-0 flex-1"
                />
              </div>
            </SettingContainer>
          )}

          {state.selectedProviderId !== "ollama" && (
            <SettingContainer
              title={t("settings.postProcessing.api.apiKey.title")}
              description={t("settings.postProcessing.api.apiKey.description")}
              descriptionMode="tooltip"
              layout="horizontal"
              grouped={true}
            >
              <div className="flex flex-col gap-2">
                <div className="flex items-center gap-2">
                  <ApiKeyField
                    label={t("settings.postProcessing.api.apiKey.title")}
                    key={`${state.selectedProviderId}-${state.baseUrl}-${state.apiKeyInputVersion}`}
                    value={state.apiKey}
                    onBlur={state.handleApiKeyChange}
                    placeholder={t(
                      "settings.postProcessing.api.apiKey.placeholder",
                    )}
                    disabled={state.isApiKeyUpdating || state.isBaseUrlUpdating}
                    unavailableReasonId={unavailableReasonId}
                    className="min-w-0 flex-1"
                  />
                  {state.apiKeyConfigured && !unavailableReasonId && (
                    <Button
                      variant="secondary"
                      size="md"
                      disabled={
                        state.isApiKeyUpdating || state.isBaseUrlUpdating
                      }
                      onClick={state.handleApiKeyRemove}
                    >
                      {t("murmur.cleanup.removeKey", {
                        defaultValue: "Remove key",
                      })}
                    </Button>
                  )}
                </div>
                <span className="text-xs text-mid-gray">
                  {state.apiKeyConfigured === null
                    ? t("murmur.cleanup.keyStatusUnknown", {
                        defaultValue: "Checking the system credential store…",
                      })
                    : state.apiKeyConfigured
                      ? t("murmur.cleanup.keyStored", {
                          defaultValue:
                            "Key saved in the system credential store",
                        })
                      : t("murmur.cleanup.keyMissing", {
                          defaultValue: "No key saved",
                        })}
                </span>
              </div>
            </SettingContainer>
          )}
        </>
      )}

      {!state.isAppleProvider && (
        <SettingContainer
          title={t("settings.postProcessing.api.model.title")}
          description={
            state.isCustomProvider
              ? t("settings.postProcessing.api.model.descriptionCustom")
              : t("settings.postProcessing.api.model.descriptionDefault")
          }
          descriptionMode="tooltip"
          layout="stacked"
          grouped={true}
        >
          {unavailableReasonId ? (
            <Input
              aria-label={t("settings.postProcessing.api.model.title")}
              aria-disabled="true"
              aria-describedby={unavailableReasonId}
              value={state.model}
              placeholder={modelPlaceholder}
              readOnly
              className="min-h-10 w-full cursor-not-allowed opacity-60"
            />
          ) : (
            <div className="flex items-center gap-2">
              <ModelSelect
                label={t("settings.postProcessing.api.model.title")}
                value={state.model}
                options={state.modelOptions}
                disabled={state.isModelUpdating}
                isLoading={state.isFetchingModels}
                placeholder={modelPlaceholder}
                onSelect={state.handleModelSelect}
                onCreate={state.handleModelCreate}
                onBlur={() => {}}
                className="min-w-0 flex-1"
              />
              <ResetButton
                onClick={state.handleRefreshModels}
                disabled={state.isFetchingModels}
                ariaLabel={t("settings.postProcessing.api.model.refreshModels")}
                className="flex h-10 w-10 items-center justify-center"
              >
                <RefreshCcw
                  className={`h-4 w-4 ${state.isFetchingModels ? "animate-spin" : ""}`}
                />
              </ResetButton>
            </div>
          )}
        </SettingContainer>
      )}
    </>
  );
};
