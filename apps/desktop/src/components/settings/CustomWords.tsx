import React, { useState } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { useSettings } from "../../hooks/useSettings";
import { commands } from "@/bindings";
import { Input } from "../ui/Input";
import { Button } from "../ui/Button";
import { SettingContainer } from "../ui/SettingContainer";

interface CustomWordsProps {
  descriptionMode?: "inline" | "tooltip";
  grouped?: boolean;
}

const normalizeCustomWord = (word: string) => word.replace(/\s+/g, " ").trim();

export const CustomWords: React.FC<CustomWordsProps> = React.memo(
  ({ descriptionMode = "tooltip", grouped = false }) => {
    const { t } = useTranslation();
    const { getSetting, isLoading, isUpdating, refreshSettings } =
      useSettings();
    const [newWord, setNewWord] = useState("");
    const [search, setSearch] = useState("");
    const [isSaving, setIsSaving] = useState(false);
    const customWords = getSetting("custom_words") || [];
    const visibleWords = customWords.filter((word) =>
      word.toLocaleLowerCase().includes(search.trim().toLocaleLowerCase()),
    );
    const normalizedWord = normalizeCustomWord(newWord);
    const isDictionaryUpdating =
      isLoading || isSaving || isUpdating("custom_words");

    const saveCustomWords = async (words: string[]) => {
      setIsSaving(true);

      try {
        const result = await commands.updateCustomWords(words);
        if (result.status === "error") {
          throw new Error(String(result.error));
        }

        await refreshSettings();
        return true;
      } catch (error) {
        console.error("Failed to save custom dictionary:", error);
        toast.error(
          t("murmur.dictionary.saveError", {
            defaultValue: "Could not save this dictionary entry.",
          }),
        );
        return false;
      } finally {
        setIsSaving(false);
      }
    };

    const handleAddWord = async () => {
      if (normalizedWord && normalizedWord.length <= 50) {
        if (
          customWords.some(
            (word) =>
              word.toLocaleLowerCase() === normalizedWord.toLocaleLowerCase(),
          )
        ) {
          toast.error(
            t("settings.advanced.customWords.duplicate", {
              word: normalizedWord,
            }),
          );
          return;
        }
        if (await saveCustomWords([...customWords, normalizedWord])) {
          setNewWord("");
        }
      }
    };

    const handleRemoveWord = async (wordToRemove: string) => {
      await saveCustomWords(
        customWords.filter((word) => word !== wordToRemove),
      );
    };

    const handleKeyPress = (e: React.KeyboardEvent) => {
      if (e.key === "Enter") {
        e.preventDefault();
        handleAddWord();
      }
    };

    return (
      <>
        <SettingContainer
          title={t("settings.advanced.customWords.title")}
          description={t("settings.advanced.customWords.description")}
          descriptionMode={descriptionMode}
          grouped={grouped}
        >
          <div className="flex items-center gap-2">
            <Input
              type="text"
              className="min-w-0 flex-1"
              value={newWord}
              onChange={(e) => setNewWord(e.target.value)}
              onKeyDown={handleKeyPress}
              placeholder={t("settings.advanced.customWords.placeholder")}
              aria-label={t("settings.advanced.customWords.title")}
              maxLength={50}
              variant="compact"
              disabled={isDictionaryUpdating}
            />
            <Button
              onClick={() => void handleAddWord()}
              disabled={
                !normalizedWord ||
                normalizedWord.length > 50 ||
                isDictionaryUpdating
              }
              variant="primary"
              size="md"
            >
              {t("settings.advanced.customWords.add")}
            </Button>
          </div>
        </SettingContainer>
        {customWords.length > 0 && (
          <div
            className={`px-4 p-3 ${grouped ? "" : "rounded-lg border border-mid-gray/20"} space-y-3`}
          >
            <Input
              type="search"
              value={search}
              onChange={(event) => setSearch(event.target.value)}
              placeholder={t("murmur.dictionary.search", {
                defaultValue: "Search saved words",
              })}
              aria-label={t("murmur.dictionary.search", {
                defaultValue: "Search saved words",
              })}
              variant="compact"
            />
            <p className="text-xs text-mid-gray">
              {t("murmur.dictionary.count", {
                count: customWords.length,
                defaultValue: "{{count}} terms saved",
              })}
            </p>
            <div className="flex flex-wrap gap-1.5">
              {visibleWords.map((word) => (
                <Button
                  key={word}
                  onClick={() => void handleRemoveWord(word)}
                  disabled={isDictionaryUpdating}
                  variant="secondary"
                  size="sm"
                  className="inline-flex items-center gap-1 cursor-pointer"
                  aria-label={t("settings.advanced.customWords.remove", {
                    word,
                  })}
                >
                  <span>{word}</span>
                  <svg
                    className="w-3 h-3"
                    fill="none"
                    stroke="currentColor"
                    viewBox="0 0 24 24"
                    aria-hidden="true"
                  >
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      strokeWidth={2}
                      d="M6 18L18 6M6 6l12 12"
                    />
                  </svg>
                </Button>
              ))}
              {visibleWords.length === 0 && (
                <p className="py-2 text-sm text-mid-gray">
                  {t("murmur.dictionary.noMatches", {
                    defaultValue: "No matching words.",
                  })}
                </p>
              )}
            </div>
          </div>
        )}
      </>
    );
  },
);
