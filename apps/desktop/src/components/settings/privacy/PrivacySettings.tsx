import React from "react";
import { useTranslation } from "react-i18next";
import { ArrowRight, Cloud, HardDrive } from "lucide-react";
import type { RecordingRetentionPeriod } from "@/bindings";
import { hostOf } from "@/lib/utils/format";
import { useSettings } from "../../../hooks/useSettings";
import type { SidebarSection } from "../../Sidebar";
import { SettingsGroup } from "../../ui/SettingsGroup";
import { AppDataDirectory } from "../AppDataDirectory";
import { useSavedApiKeys } from "../models/SavedApiKeys";
import { useSettingsNavigation } from "../navigation";

const RETENTION_KEYS: Record<RecordingRetentionPeriod, string> = {
  never: "never",
  preserve_limit: "preserveLimit",
  days_3: "days3",
  weeks_2: "weeks2",
  months_3: "months3",
};

interface SummaryRowProps {
  leavesDevice: boolean;
  title: string;
  detail: string;
  target: SidebarSection;
  actionLabel: string;
}

// read-only on purpose: every control lives on one page and this links to it
const SummaryRow: React.FC<SummaryRowProps> = ({
  leavesDevice,
  title,
  detail,
  target,
  actionLabel,
}) => {
  const navigate = useSettingsNavigation();
  const Icon = leavesDevice ? Cloud : HardDrive;

  return (
    <div className="flex min-h-14 items-center gap-3 px-4 py-2.5">
      <Icon
        size={16}
        className={`shrink-0 ${leavesDevice ? "text-warning" : "text-mid-gray"}`}
        aria-hidden="true"
      />
      <div className="min-w-0 flex-1">
        <p className="text-sm font-medium">{title}</p>
        <p className="text-xs leading-relaxed text-mid-gray">{detail}</p>
      </div>
      <button
        type="button"
        onClick={() => navigate(target)}
        className="inline-flex shrink-0 cursor-pointer items-center gap-1 rounded-md px-2 py-1 text-xs font-medium text-logo-primary hover:bg-mid-gray/10"
      >
        {actionLabel}
        <ArrowRight size={12} aria-hidden="true" />
      </button>
    </div>
  );
};

export const PrivacySettings: React.FC = () => {
  const { t } = useTranslation();
  const { settings } = useSettings();
  const savedKeys = useSavedApiKeys();

  const usesEndpoint = settings?.stt_source === "endpoint";
  const cleanupOn = settings?.post_process_enabled ?? false;
  const provider = settings?.post_process_providers?.find(
    (candidate) => candidate.id === settings.post_process_provider_id,
  );
  const cleanupIsLocal =
    provider?.id === "ollama" || provider?.id === "apple_intelligence";
  const cleanupLeaves = cleanupOn && !cleanupIsLocal;
  const retention = settings?.recording_retention_period ?? "never";
  const change = t("murmur.privacy.change", { defaultValue: "Change" });

  return (
    <div className="w-full space-y-6">
      <SettingsGroup
        title={t("murmur.privacy.leaves.title", {
          defaultValue: "What leaves this computer",
        })}
        description={t("murmur.privacy.leaves.description", {
          defaultValue:
            "Your voice and text only go to services you choose. Murmur never switches to one on its own.",
        })}
      >
        <SummaryRow
          leavesDevice={usesEndpoint}
          title={t("murmur.privacy.speech.title", {
            defaultValue: "Your voice",
          })}
          detail={
            usesEndpoint
              ? t("murmur.privacy.speech.cloud", {
                  host: hostOf(settings?.stt_base_url ?? ""),
                  defaultValue:
                    "Each recording is sent to {{host}} to be transcribed.",
                })
              : t("murmur.privacy.speech.local", {
                  defaultValue:
                    "Transcribed on this computer. Recordings are never uploaded.",
                })
          }
          target="models"
          actionLabel={change}
        />
        <SummaryRow
          leavesDevice={cleanupLeaves}
          title={t("murmur.privacy.cleanup.title", {
            defaultValue: "Your transcripts",
          })}
          detail={
            !cleanupOn
              ? t("murmur.privacy.cleanup.off", {
                  defaultValue: "AI cleanup is off, so transcripts stay here.",
                })
              : cleanupIsLocal
                ? t("murmur.privacy.cleanup.local", {
                    provider: provider?.label ?? "",
                    defaultValue:
                      "Cleaned up on this computer by {{provider}}.",
                  })
                : t("murmur.privacy.cleanup.cloud", {
                    provider: provider?.label ?? "",
                    defaultValue:
                      "Transcript text and your dictionary words are sent to {{provider}} for cleanup. Audio is not.",
                  })
          }
          target="cleanup"
          actionLabel={change}
        />
      </SettingsGroup>

      <SettingsGroup
        title={t("murmur.privacy.stored.title", {
          defaultValue: "What stays on this computer",
        })}
      >
        <SummaryRow
          leavesDevice={false}
          title={t("murmur.privacy.history.title", {
            defaultValue: "Recordings and transcripts",
          })}
          detail={t("murmur.privacy.history.detail", {
            period: t(
              `settings.debug.recordingRetention.${RETENTION_KEYS[retention]}`,
              { count: settings?.history_limit ?? 0 },
            ),
            defaultValue:
              "Kept: {{period}}. Starred dictations are always kept.",
          })}
          target="history"
          actionLabel={change}
        />
        <SummaryRow
          leavesDevice={false}
          title={t("murmur.privacy.keys.title", { defaultValue: "API keys" })}
          detail={
            savedKeys === null
              ? t("murmur.keys.loading", {
                  defaultValue: "Checking the credential store…",
                })
              : t("murmur.privacy.keys.detail", {
                  count: savedKeys.length,
                  defaultValue:
                    "{{count}} saved in your system credential store, never in Murmur's settings file.",
                })
          }
          target="models"
          actionLabel={t("murmur.privacy.manage", { defaultValue: "Manage" })}
        />
        <AppDataDirectory descriptionMode="tooltip" grouped={true} />
      </SettingsGroup>
    </div>
  );
};
