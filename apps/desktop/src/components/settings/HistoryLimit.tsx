import React, { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { ask } from "@tauri-apps/plugin-dialog";
import { useSettings } from "../../hooks/useSettings";
import { Input } from "../ui/Input";
import { SettingContainer } from "../ui/SettingContainer";

interface HistoryLimitProps {
  descriptionMode?: "tooltip" | "inline";
  grouped?: boolean;
}

export const HistoryLimit: React.FC<HistoryLimitProps> = ({
  descriptionMode = "inline",
  grouped = false,
}) => {
  const { t } = useTranslation();
  const { getSetting, updateSetting, isUpdating } = useSettings();

  const historyLimit = getSetting("history_limit") ?? 1000;
  const [draft, setDraft] = useState(String(historyLimit));

  useEffect(() => setDraft(String(historyLimit)), [historyLimit]);

  const commit = async () => {
    const value = Number(draft);
    if (!Number.isInteger(value) || value < 0 || value > 1_000_000) {
      setDraft(String(historyLimit));
      return;
    }
    if (value === historyLimit) return;
    if (value < historyLimit) {
      const confirmed = await ask(
        t("murmur.history.limit.confirmMessage", {
          defaultValue:
            "This smaller limit will permanently delete older unsaved recordings and transcripts. Continue?",
        }),
        {
          title: t("murmur.history.limit.confirmTitle", {
            defaultValue: "Reduce history limit",
          }),
          kind: "warning",
        },
      );
      if (!confirmed) {
        setDraft(String(historyLimit));
        return;
      }
    }
    try {
      await updateSetting("history_limit", value);
    } catch {
      setDraft(String(historyLimit));
    }
  };

  return (
    <SettingContainer
      title={t("settings.debug.historyLimit.title")}
      description={t("settings.debug.historyLimit.description")}
      descriptionMode={descriptionMode}
      grouped={grouped}
      layout="horizontal"
    >
      <div className="flex items-center space-x-2">
        <Input
          type="number"
          aria-label={t("settings.debug.historyLimit.title")}
          min="0"
          max="1000000"
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
          onBlur={() => void commit()}
          onKeyDown={(event) => {
            if (event.key === "Enter") event.currentTarget.blur();
          }}
          disabled={isUpdating("history_limit")}
          className="w-20"
        />
        <span className="text-sm text-text">
          {t("settings.debug.historyLimit.entries")}
        </span>
      </div>
    </SettingContainer>
  );
};
