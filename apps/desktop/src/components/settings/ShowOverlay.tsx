import React from "react";
import { useTranslation } from "react-i18next";
import { Dropdown } from "../ui/Dropdown";
import { SettingContainer } from "../ui/SettingContainer";
import { useSettings } from "../../hooks/useSettings";
import type { OverlayPosition } from "@/bindings";

interface ShowOverlayProps {
  descriptionMode?: "inline" | "tooltip";
  grouped?: boolean;
}

export const ShowOverlay: React.FC<ShowOverlayProps> = React.memo(
  ({ descriptionMode = "tooltip", grouped = false }) => {
    const { t } = useTranslation();
    const { getSetting, updateSetting, isUpdating } = useSettings();

    const positionOptions = [
      {
        value: "bottom",
        label: t("settings.advanced.overlay.position.options.bottom"),
      },
      {
        value: "top",
        label: t("settings.advanced.overlay.position.options.top"),
      },
    ];

    // Only "top" and "bottom" are selectable; anything else (empty, or a legacy
    // "none" from before the position was retired) falls back to "bottom".
    const selectedPosition: OverlayPosition =
      getSetting("overlay_position") === "top" ? "top" : "bottom";

    return (
      <>
        <SettingContainer
          title={t("murmur.dictation.indicator.visibility", {
            defaultValue: "Show recording indicator",
          })}
          description={t("murmur.dictation.indicator.description", {
            defaultValue:
              "A visible alternative to sound cues. Availability depends on the desktop and its permissions.",
          })}
          descriptionMode="inline"
          grouped={grouped}
        >
          <input
            type="checkbox"
            aria-label={t("murmur.dictation.indicator.visibility", {
              defaultValue: "Show recording indicator",
            })}
            checked={getSetting("overlay_style") !== "none"}
            disabled={isUpdating("overlay_style")}
            onChange={(event) =>
              updateSetting(
                "overlay_style",
                event.target.checked ? "minimal" : "none",
              )
            }
            className="size-6 accent-logo-primary"
          />
        </SettingContainer>
        <SettingContainer
          title={t("settings.advanced.overlay.position.title")}
          description={t("settings.advanced.overlay.position.description")}
          descriptionMode={descriptionMode}
          grouped={grouped}
        >
          <Dropdown
            label={t("settings.advanced.overlay.position.title")}
            options={positionOptions}
            selectedValue={selectedPosition}
            onSelect={(value) =>
              updateSetting("overlay_position", value as OverlayPosition)
            }
            disabled={isUpdating("overlay_position")}
          />
        </SettingContainer>
      </>
    );
  },
);
