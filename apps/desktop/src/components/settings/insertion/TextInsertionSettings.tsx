import React from "react";
import { useTranslation } from "react-i18next";
import { SettingsGroup } from "../../ui/SettingsGroup";
import { AppendTrailingSpace } from "../AppendTrailingSpace";
import { AutoSubmit } from "../AutoSubmit";
import { ClipboardHandlingSetting } from "../ClipboardHandling";
import { PasteMethodSetting } from "../PasteMethod";
import { TypingToolSetting } from "../TypingTool";

export const TextInsertionSettings: React.FC = () => {
  const { t } = useTranslation();

  return (
    <div className="w-full space-y-6">
      <SettingsGroup
        title={t("murmur.insertion.method.title", {
          defaultValue: "Inserting text",
        })}
        description={t("murmur.insertion.method.description", {
          defaultValue:
            "If transcripts appear in History but not in your app, try another method here.",
        })}
      >
        <PasteMethodSetting descriptionMode="tooltip" grouped={true} />
        <TypingToolSetting descriptionMode="tooltip" grouped={true} />
        <ClipboardHandlingSetting descriptionMode="tooltip" grouped={true} />
      </SettingsGroup>

      <SettingsGroup
        title={t("murmur.insertion.after.title", {
          defaultValue: "After inserting",
        })}
      >
        <AppendTrailingSpace descriptionMode="tooltip" grouped={true} />
        <AutoSubmit descriptionMode="tooltip" grouped={true} />
      </SettingsGroup>
    </div>
  );
};
