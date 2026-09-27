import React from "react";
import { useTranslation } from "react-i18next";
import { useSettings } from "../../../hooks/useSettings";
import { SettingsGroup } from "../../ui/SettingsGroup";
import { AccelerationSelector } from "../AccelerationSelector";
import { AppLanguageSelector } from "../AppLanguageSelector";
import { AutostartToggle } from "../AutostartToggle";
import { ExperimentalToggle } from "../ExperimentalToggle";
import { LazyStreamClose } from "../LazyStreamClose";
import { ModelUnloadTimeoutSetting } from "../ModelUnloadTimeout";
import { ShowTrayIcon } from "../ShowTrayIcon";
import { StartHidden } from "../StartHidden";
import { ThemeSelector } from "../ThemeSelector";
import { VadBackendSelector } from "../VadBackendSelector";

export const AppPreferences: React.FC = () => {
  const { t } = useTranslation();
  const { getSetting } = useSettings();
  const experimentalEnabled = getSetting("experimental_enabled") || false;

  return (
    <div className="w-full space-y-6">
      <SettingsGroup
        title={t("murmur.app.appearance.title", {
          defaultValue: "Appearance",
        })}
      >
        <ThemeSelector descriptionMode="tooltip" grouped={true} />
        <AppLanguageSelector descriptionMode="tooltip" grouped={true} />
      </SettingsGroup>

      <SettingsGroup
        title={t("murmur.app.startup.title", {
          defaultValue: "Startup and tray",
        })}
      >
        <AutostartToggle descriptionMode="tooltip" grouped={true} />
        <StartHidden descriptionMode="tooltip" grouped={true} />
        <ShowTrayIcon descriptionMode="tooltip" grouped={true} />
      </SettingsGroup>

      <SettingsGroup
        title={t("murmur.app.performance.title", {
          defaultValue: "Performance",
        })}
      >
        <ModelUnloadTimeoutSetting descriptionMode="tooltip" grouped={true} />
        <ExperimentalToggle descriptionMode="tooltip" grouped={true} />
      </SettingsGroup>

      {experimentalEnabled && (
        <SettingsGroup title={t("settings.advanced.groups.experimental")}>
          <AccelerationSelector descriptionMode="tooltip" grouped={true} />
          <LazyStreamClose descriptionMode="tooltip" grouped={true} />
          <VadBackendSelector descriptionMode="tooltip" grouped={true} />
        </SettingsGroup>
      )}
    </div>
  );
};
