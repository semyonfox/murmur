import React, { useState } from "react";
import { useTranslation } from "react-i18next";
import { ArrowRight } from "lucide-react";
import { useSettings } from "../../../hooks/useSettings";
import { Button } from "../../ui/Button";
import { SettingsGroup } from "../../ui/SettingsGroup";
import { useSettingsNavigation } from "../navigation";
import { LocalModels } from "./LocalModels";
import { SavedApiKeys } from "./SavedApiKeys";
import { SttSourceSettings } from "./SttSourceSettings";

export const ModelsSettings: React.FC = () => {
  const { t } = useTranslation();
  const { settings } = useSettings();
  const navigate = useSettingsNavigation();
  const [draftSource, setDraftSource] = useState<"local" | "endpoint">(
    settings?.stt_source ?? "local",
  );
  const provider = settings?.post_process_providers?.find(
    (candidate) => candidate.id === settings.post_process_provider_id,
  );
  const providerModel = provider
    ? settings?.post_process_models?.[provider.id]
    : undefined;

  return (
    <div className="w-full space-y-6">
      <SttSourceSettings onDraftSourceChange={setDraftSource} />

      {draftSource === "local" && (
        <div className="space-y-2">
          <h2 className="px-4 text-xs font-medium uppercase tracking-wide text-mid-gray">
            {t("murmur.models.local.title", {
              defaultValue: "On-device models",
            })}
          </h2>
          <LocalModels />
        </div>
      )}

      {/* cleanup is configured on its own page; this row keeps it findable
          for people who think of it as "a model" */}
      <SettingsGroup
        title={t("murmur.models.cleanup.title", {
          defaultValue: "Cleanup model",
        })}
      >
        <div className="flex min-h-12 items-center justify-between gap-3 px-4 py-2">
          <div className="min-w-0">
            <p className="truncate text-sm font-medium">
              {provider
                ? [provider.label, providerModel].filter(Boolean).join(" · ")
                : t("murmur.cleanup.model.none", {
                    defaultValue: "No provider chosen",
                  })}
            </p>
            <p className="text-xs text-mid-gray">
              {settings?.post_process_enabled
                ? t("murmur.models.cleanup.on", {
                    defaultValue: "AI cleanup is on",
                  })
                : t("murmur.models.cleanup.off", {
                    defaultValue: "AI cleanup is off",
                  })}
            </p>
          </div>
          <Button
            variant="secondary"
            size="sm"
            onClick={() => navigate("cleanup")}
            className="inline-flex shrink-0 items-center gap-1.5"
          >
            {t("murmur.models.cleanup.change", {
              defaultValue: "Change in Cleanup",
            })}
            <ArrowRight size={13} aria-hidden="true" />
          </Button>
        </div>
      </SettingsGroup>

      <SavedApiKeys />
    </div>
  );
};
