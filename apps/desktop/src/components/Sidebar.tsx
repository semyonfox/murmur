import React from "react";
import { useTranslation } from "react-i18next";
import {
  BookA,
  ChartNoAxesCombined,
  Cpu,
  FlaskConical,
  History,
  NotebookPen,
  Info,
  Mic,
  ShieldCheck,
  SlidersHorizontal,
  Sparkles,
  TextCursorInput,
  type LucideIcon,
} from "lucide-react";
import type { AppSettings } from "@/bindings";
import HandyTextLogo from "./icons/HandyTextLogo";
import LecturesSettings from "./settings/LecturesSettings";
import { useSettings } from "../hooks/useSettings";
import {
  AboutSettings,
  AppPreferences,
  CleanupSettings,
  DebugSettings,
  DictationSettings,
  DictionarySettings,
  HistorySettings,
  ModelsSettings,
  PrivacySettings,
  StatsSettings,
  TextInsertionSettings,
} from "./settings";

export type SidebarSection = keyof typeof SECTIONS_CONFIG;

interface SectionConfig {
  labelKey: string;
  descriptionKey: string;
  icon: LucideIcon;
  component: React.ComponentType;
  // sections sharing a group sit together; a gap separates groups
  group: "setup" | "records" | "app";
  enabled: (settings: AppSettings | null) => boolean;
}

export const SECTIONS_CONFIG = {
  dictation: {
    labelKey: "murmur.nav.dictation",
    descriptionKey: "murmur.pages.dictation.description",
    icon: Mic,
    component: DictationSettings,
    group: "setup",
    enabled: () => true,
  },
  lectures: {
    labelKey: "murmur.nav.lectures",
    descriptionKey: "murmur.pages.lectures.description",
    icon: NotebookPen,
    component: LecturesSettings,
    group: "setup",
    enabled: () => true,
  },
  models: {
    labelKey: "sidebar.models",
    descriptionKey: "murmur.pages.models.description",
    icon: Cpu,
    component: ModelsSettings,
    group: "setup",
    enabled: () => true,
  },
  cleanup: {
    labelKey: "sidebar.postProcessing",
    descriptionKey: "murmur.pages.cleanup.description",
    icon: Sparkles,
    component: CleanupSettings,
    group: "setup",
    enabled: () => true,
  },
  dictionary: {
    labelKey: "murmur.nav.dictionary",
    descriptionKey: "murmur.pages.dictionary.description",
    icon: BookA,
    component: DictionarySettings,
    group: "setup",
    enabled: () => true,
  },
  insertion: {
    labelKey: "murmur.nav.insertion",
    descriptionKey: "murmur.pages.insertion.description",
    icon: TextCursorInput,
    component: TextInsertionSettings,
    group: "setup",
    enabled: () => true,
  },
  stats: {
    labelKey: "murmur.nav.stats",
    descriptionKey: "murmur.pages.stats.description",
    icon: ChartNoAxesCombined,
    component: StatsSettings,
    group: "records",
    enabled: () => true,
  },
  history: {
    labelKey: "sidebar.history",
    descriptionKey: "murmur.pages.history.description",
    icon: History,
    component: HistorySettings,
    group: "records",
    enabled: () => true,
  },
  privacy: {
    labelKey: "murmur.nav.privacy",
    descriptionKey: "murmur.pages.privacy.description",
    icon: ShieldCheck,
    component: PrivacySettings,
    group: "records",
    enabled: () => true,
  },
  app: {
    labelKey: "murmur.nav.app",
    descriptionKey: "murmur.pages.app.description",
    icon: SlidersHorizontal,
    component: AppPreferences,
    group: "app",
    enabled: () => true,
  },
  about: {
    labelKey: "sidebar.about",
    descriptionKey: "murmur.pages.about.description",
    icon: Info,
    component: AboutSettings,
    group: "app",
    enabled: () => true,
  },
  debug: {
    labelKey: "sidebar.debug",
    descriptionKey: "murmur.pages.debug.description",
    icon: FlaskConical,
    component: DebugSettings,
    group: "app",
    enabled: (settings) => settings?.debug_mode ?? false,
  },
} as const satisfies Record<string, SectionConfig>;

interface SidebarProps {
  activeSection: SidebarSection;
  onSectionChange: (section: SidebarSection) => void;
}

export const Sidebar: React.FC<SidebarProps> = ({
  activeSection,
  onSectionChange,
}) => {
  const { t } = useTranslation();
  const { settings } = useSettings();

  const availableSections = (
    Object.keys(SECTIONS_CONFIG) as SidebarSection[]
  ).filter((id) => SECTIONS_CONFIG[id].enabled(settings));

  return (
    <nav
      aria-label={t("murmur.nav.label", { defaultValue: "Settings" })}
      className="flex h-full w-44 shrink-0 flex-col overflow-y-auto border-e border-line/9 px-2 max-sm:h-auto max-sm:w-full max-sm:border-e-0 max-sm:border-b"
    >
      <HandyTextLogo width={104} className="mx-2 mt-4 mb-5 max-sm:hidden" />
      <ul className="flex flex-col gap-0.5 pb-3 max-sm:flex-row max-sm:overflow-x-auto max-sm:py-2">
        {availableSections.map((id, index) => {
          const section = SECTIONS_CONFIG[id];
          const Icon = section.icon;
          const isActive = activeSection === id;
          const previous = availableSections[index - 1];
          const startsGroup =
            previous !== undefined &&
            SECTIONS_CONFIG[previous].group !== section.group;
          const label = t(section.labelKey);

          return (
            <li
              key={id}
              className={`shrink-0 ${startsGroup ? "mt-3 max-sm:mt-0" : ""}`}
            >
              <button
                type="button"
                aria-current={isActive ? "page" : undefined}
                onClick={() => onSectionChange(id)}
                className={`flex w-full cursor-pointer items-center gap-2.5 rounded-lg px-2.5 py-1.5 text-start text-sm transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-logo-primary/40 max-sm:min-h-11 ${
                  isActive
                    ? "bg-mid-gray/15 font-medium text-text"
                    : "text-text/75 hover:bg-mid-gray/10 hover:text-text"
                }`}
              >
                <Icon
                  size={17}
                  className={`shrink-0 ${isActive ? "text-logo-primary" : ""}`}
                  aria-hidden="true"
                />
                <span className="truncate" title={label}>
                  {label}
                </span>
              </button>
            </li>
          );
        })}
      </ul>
    </nav>
  );
};
