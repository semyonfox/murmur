import React from "react";
import { useTranslation } from "react-i18next";
import { SettingsGroup } from "../../ui/SettingsGroup";
import { CustomWords } from "../CustomWords";

export const DictionarySettings: React.FC = () => {
  const { t } = useTranslation();

  return (
    <div className="w-full space-y-6">
      <SettingsGroup
        title={t("murmur.dictionary.title", { defaultValue: "Your words" })}
        description={t("murmur.dictionary.description", {
          defaultValue:
            "Used by on-device recognition and by AI cleanup to spell unusual names and terms.",
        })}
      >
        <CustomWords descriptionMode="tooltip" grouped />
      </SettingsGroup>
    </div>
  );
};
