import React, { useEffect, useState } from "react";
import { Trans, useTranslation } from "react-i18next";
import { commands } from "@/bindings";

import {
  Dropdown,
  SettingContainer,
  SettingsGroup,
  Textarea,
} from "@/components/ui";
import { Button } from "../../ui/Button";
import { Input } from "../../ui/Input";

import { FillerWordRemoval } from "../FillerWordRemoval";
import { PostProcessingToggle } from "../PostProcessingToggle";
import { useSettings } from "../../../hooks/useSettings";
import { CleanupModel } from "./CleanupModel";

const PostProcessingSettingsPromptsComponent: React.FC = () => {
  const { t } = useTranslation();
  const { getSetting, updateSetting, isUpdating, refreshSettings } =
    useSettings();
  const [isCreating, setIsCreating] = useState(false);
  const [draftName, setDraftName] = useState("");
  const [draftText, setDraftText] = useState("");

  const prompts = getSetting("post_process_prompts") || [];
  const selectedPromptId = getSetting("post_process_selected_prompt_id") || "";
  const selectedPrompt =
    prompts.find((prompt) => prompt.id === selectedPromptId) || null;

  useEffect(() => {
    if (isCreating) return;

    if (selectedPrompt) {
      setDraftName(selectedPrompt.name);
      setDraftText(selectedPrompt.prompt);
    } else {
      setDraftName("");
      setDraftText("");
    }
  }, [
    isCreating,
    selectedPromptId,
    selectedPrompt?.name,
    selectedPrompt?.prompt,
  ]);

  const handlePromptSelect = (promptId: string | null) => {
    if (!promptId) return;
    updateSetting("post_process_selected_prompt_id", promptId);
    setIsCreating(false);
  };

  const handleCreatePrompt = async () => {
    if (!draftName.trim() || !draftText.trim()) return;

    try {
      const result = await commands.addPostProcessPrompt(
        draftName.trim(),
        draftText.trim(),
      );
      if (result.status === "ok") {
        await refreshSettings();
        updateSetting("post_process_selected_prompt_id", result.data.id);
        setIsCreating(false);
      }
    } catch (error) {
      console.error("Failed to create prompt:", error);
    }
  };

  const handleUpdatePrompt = async () => {
    if (!selectedPromptId || !draftName.trim() || !draftText.trim()) return;

    try {
      await commands.updatePostProcessPrompt(
        selectedPromptId,
        draftName.trim(),
        draftText.trim(),
      );
      await refreshSettings();
    } catch (error) {
      console.error("Failed to update prompt:", error);
    }
  };

  const handleDeletePrompt = async (promptId: string) => {
    if (!promptId) return;

    try {
      await commands.deletePostProcessPrompt(promptId);
      await refreshSettings();
      setIsCreating(false);
    } catch (error) {
      console.error("Failed to delete prompt:", error);
    }
  };

  const handleCancelCreate = () => {
    setIsCreating(false);
    if (selectedPrompt) {
      setDraftName(selectedPrompt.name);
      setDraftText(selectedPrompt.prompt);
    } else {
      setDraftName("");
      setDraftText("");
    }
  };

  const handleStartCreate = () => {
    setIsCreating(true);
    setDraftName("");
    setDraftText("");
  };

  const hasPrompts = prompts.length > 0;
  const isDirty =
    !!selectedPrompt &&
    (draftName.trim() !== selectedPrompt.name ||
      draftText.trim() !== selectedPrompt.prompt.trim());

  return (
    <SettingContainer
      title={t("settings.postProcessing.prompts.selectedPrompt.title")}
      description={t(
        "settings.postProcessing.prompts.selectedPrompt.description",
      )}
      descriptionMode="tooltip"
      layout="stacked"
      grouped={true}
    >
      <div className="space-y-3">
        <div className="flex gap-2 min-w-0">
          <Dropdown
            selectedValue={selectedPromptId || null}
            options={prompts.map((p) => ({
              value: p.id,
              label: p.name,
            }))}
            onSelect={(value) => handlePromptSelect(value)}
            placeholder={
              prompts.length === 0
                ? t("settings.postProcessing.prompts.noPrompts")
                : t("settings.postProcessing.prompts.selectPrompt")
            }
            disabled={
              isUpdating("post_process_selected_prompt_id") || isCreating
            }
            className="flex-1 min-w-0"
          />
          <Button
            onClick={handleStartCreate}
            variant="secondary"
            size="md"
            disabled={isCreating}
            className="shrink-0"
          >
            {t("settings.postProcessing.prompts.createNew")}
          </Button>
        </div>

        {!isCreating && hasPrompts && selectedPrompt && (
          <div className="space-y-3">
            <div className="space-y-2 flex flex-col">
              <label className="text-sm font-medium">
                {t("settings.postProcessing.prompts.promptLabel")}
              </label>
              <Input
                type="text"
                value={draftName}
                onChange={(e) => setDraftName(e.target.value)}
                placeholder={t(
                  "settings.postProcessing.prompts.promptLabelPlaceholder",
                )}
                variant="compact"
              />
            </div>

            <div className="space-y-2 flex flex-col">
              <label className="text-sm font-medium">
                {t("settings.postProcessing.prompts.promptInstructions")}
              </label>
              <Textarea
                value={draftText}
                onChange={(e) => setDraftText(e.target.value)}
                placeholder={t(
                  "settings.postProcessing.prompts.promptInstructionsPlaceholder",
                )}
              />
              <p className="text-xs text-mid-gray/70">
                <Trans
                  i18nKey="settings.postProcessing.prompts.promptTip"
                  components={{ code: <code /> }}
                />
              </p>
            </div>

            <div className="flex gap-2 pt-2">
              <Button
                onClick={handleUpdatePrompt}
                variant="primary"
                size="md"
                disabled={!draftName.trim() || !draftText.trim() || !isDirty}
              >
                {t("settings.postProcessing.prompts.updatePrompt")}
              </Button>
              <Button
                onClick={() => handleDeletePrompt(selectedPromptId)}
                variant="secondary"
                size="md"
                disabled={!selectedPromptId || prompts.length <= 1}
              >
                {t("settings.postProcessing.prompts.deletePrompt")}
              </Button>
            </div>
          </div>
        )}

        {!isCreating && !selectedPrompt && (
          <div className="p-3 bg-mid-gray/5 rounded-md border border-mid-gray/20">
            <p className="text-sm text-mid-gray">
              {hasPrompts
                ? t("settings.postProcessing.prompts.selectToEdit")
                : t("settings.postProcessing.prompts.createFirst")}
            </p>
          </div>
        )}

        {isCreating && (
          <div className="space-y-3">
            <div className="space-y-2 block flex flex-col">
              <label className="text-sm font-medium text-text">
                {t("settings.postProcessing.prompts.promptLabel")}
              </label>
              <Input
                type="text"
                value={draftName}
                onChange={(e) => setDraftName(e.target.value)}
                placeholder={t(
                  "settings.postProcessing.prompts.promptLabelPlaceholder",
                )}
                variant="compact"
              />
            </div>

            <div className="space-y-2 flex flex-col">
              <label className="text-sm font-medium">
                {t("settings.postProcessing.prompts.promptInstructions")}
              </label>
              <Textarea
                value={draftText}
                onChange={(e) => setDraftText(e.target.value)}
                placeholder={t(
                  "settings.postProcessing.prompts.promptInstructionsPlaceholder",
                )}
              />
              <p className="text-xs text-mid-gray/70">
                <Trans
                  i18nKey="settings.postProcessing.prompts.promptTip"
                  components={{ code: <code /> }}
                />
              </p>
            </div>

            <div className="flex gap-2 pt-2">
              <Button
                onClick={handleCreatePrompt}
                variant="primary"
                size="md"
                disabled={!draftName.trim() || !draftText.trim()}
              >
                {t("settings.postProcessing.prompts.createPrompt")}
              </Button>
              <Button
                onClick={handleCancelCreate}
                variant="secondary"
                size="md"
              >
                {t("settings.postProcessing.prompts.cancel")}
              </Button>
            </div>
          </div>
        )}
      </div>
    </SettingContainer>
  );
};

export const PostProcessingSettingsPrompts = React.memo(
  PostProcessingSettingsPromptsComponent,
);
PostProcessingSettingsPrompts.displayName = "PostProcessingSettingsPrompts";

export const CleanupSettings: React.FC = () => {
  const { t } = useTranslation();
  const { getSetting, updateSetting, isLoading, isUpdating } = useSettings();
  const cleanupEnabled = getSetting("post_process_enabled") ?? false;
  const formality = getSetting("post_process_formality") ?? 3;
  const formalityOptions = [
    {
      value: 1,
      label: t("murmur.cleanup.formality.veryCasual", {
        defaultValue: "Very casual",
      }),
    },
    {
      value: 2,
      label: t("murmur.cleanup.formality.casual", {
        defaultValue: "Casual",
      }),
    },
    {
      value: 3,
      label: t("murmur.cleanup.formality.natural", {
        defaultValue: "Natural",
      }),
    },
    {
      value: 4,
      label: t("murmur.cleanup.formality.professional", {
        defaultValue: "Professional",
      }),
    },
    {
      value: 5,
      label: t("murmur.cleanup.formality.formal", {
        defaultValue: "Formal",
      }),
    },
  ];

  return (
    <div className="w-full space-y-6">
      <SettingsGroup
        title={t("murmur.cleanup.basic.title", {
          defaultValue: "On this device",
        })}
        description={t("murmur.cleanup.basic.description", {
          defaultValue: "Always runs locally, even when AI cleanup is off.",
        })}
      >
        <FillerWordRemoval descriptionMode="tooltip" grouped={true} />
      </SettingsGroup>

      <SettingsGroup
        title={t("murmur.cleanup.title", {
          defaultValue: "AI cleanup",
        })}
        description={t("murmur.cleanup.description", {
          defaultValue:
            "Fixes punctuation and clear errors and splits long dictation into readable sentences, keeping your words. Cloud providers receive the transcript, not the recording.",
        })}
      >
        <PostProcessingToggle descriptionMode="tooltip" grouped={true} />
        <CleanupModel />
        <SettingContainer
          title={t("murmur.cleanup.formality.title", {
            defaultValue: "Tone",
          })}
          description={t("murmur.cleanup.formality.description", {
            defaultValue:
              "Choose punctuation and grammar polish. Every level should keep your own words.",
          })}
          descriptionMode="tooltip"
          layout="stacked"
          grouped
          disabled={!cleanupEnabled}
        >
          <fieldset
            disabled={
              !cleanupEnabled ||
              isLoading ||
              isUpdating("post_process_formality")
            }
            className="grid grid-cols-5 gap-1.5 pb-2 disabled:opacity-50"
          >
            <legend className="sr-only">
              {t("murmur.cleanup.formality.title", {
                defaultValue: "Tone",
              })}
            </legend>
            {formalityOptions.map((option) => (
              <label
                key={option.value}
                className={`flex min-w-0 cursor-pointer items-center justify-center rounded-lg border px-1 py-2 text-center text-xs font-medium transition-colors focus-within:ring-2 focus-within:ring-logo-primary/40 ${
                  formality === option.value
                    ? "border-logo-primary/70 bg-logo-primary/10 text-text"
                    : "border-mid-gray/20 text-text/80 hover:border-mid-gray/45"
                }`}
              >
                <input
                  type="radio"
                  name="post-process-formality"
                  value={option.value}
                  checked={formality === option.value}
                  onChange={() =>
                    updateSetting("post_process_formality", option.value)
                  }
                  className="sr-only"
                />
                {option.label}
              </label>
            ))}
          </fieldset>
        </SettingContainer>
      </SettingsGroup>

      <SettingsGroup
        title={t("murmur.cleanup.instructions.title", {
          defaultValue: "Custom instructions",
        })}
        description={t("murmur.cleanup.instructions.description", {
          defaultValue: "Optional. Tell the cleanup model anything extra.",
        })}
      >
        <PostProcessingSettingsPrompts />
      </SettingsGroup>
    </div>
  );
};
