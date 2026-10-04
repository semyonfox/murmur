import React, { useCallback, useEffect, useRef, useState } from "react";
import { convertFileSrc } from "@tauri-apps/api/core";
import { readFile } from "@tauri-apps/plugin-fs";
import {
  Archive,
  Check,
  Copy,
  FolderOpen,
  RotateCcw,
  Star,
  Trash2,
} from "lucide-react";
import { ask, save } from "@tauri-apps/plugin-dialog";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { anonymousTelemetry } from "@/lib/anonymousTelemetry";
import {
  commands,
  events,
  type HistoryEntry,
  type HistoryUpdatePayload,
} from "@/bindings";
import { useOsType } from "@/hooks/useOsType";
import { formatDate, formatDateTime } from "@/utils/dateFormat";
import { AudioPlayer, AudioPlayerGroup } from "../../ui/AudioPlayer";
import { Button } from "../../ui/Button";
import { copyToClipboard } from "./clipboard";
import { RecordingRetentionPeriodSelector } from "../RecordingRetentionPeriod";
import { HistoryLimit } from "../HistoryLimit";
import { SettingsGroup } from "../../ui/SettingsGroup";
import { useSettings } from "../../../hooks/useSettings";

const IconButton: React.FC<{
  onClick: () => void;
  title: string;
  disabled?: boolean;
  active?: boolean;
  children: React.ReactNode;
}> = ({ onClick, title, disabled, active, children }) => (
  <button
    onClick={onClick}
    disabled={disabled}
    className={`p-1.5 rounded-md flex items-center justify-center transition-colors cursor-pointer disabled:cursor-not-allowed disabled:text-text/20 focus-visible:ring-2 focus-visible:ring-logo-primary max-sm:min-h-11 max-sm:min-w-11 ${
      active
        ? "text-logo-primary hover:text-logo-primary/80"
        : "text-text/50 hover:text-logo-primary"
    }`}
    title={title}
    aria-label={title}
  >
    {children}
  </button>
);

const PAGE_SIZE = 30;

interface OpenRecordingsButtonProps {
  onClick: () => void;
  label: string;
}

const OpenRecordingsButton: React.FC<OpenRecordingsButtonProps> = ({
  onClick,
  label,
}) => (
  <Button
    onClick={onClick}
    variant="secondary"
    size="sm"
    className="flex items-center gap-2"
    title={label}
  >
    <FolderOpen className="w-4 h-4" />
    <span>{label}</span>
  </Button>
);

export const HistorySettings: React.FC = () => {
  const { t, i18n } = useTranslation();
  const { getSetting } = useSettings();
  const osType = useOsType();
  const [entries, setEntries] = useState<HistoryEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [hasMore, setHasMore] = useState(true);
  const [exporting, setExporting] = useState(false);
  const [loadError, setLoadError] = useState(false);
  const sentinelRef = useRef<HTMLDivElement>(null);
  const entriesRef = useRef<HistoryEntry[]>([]);
  const loadingRef = useRef(false);

  // Keep ref in sync for use in IntersectionObserver callback
  useEffect(() => {
    entriesRef.current = entries;
  }, [entries]);

  const loadPage = useCallback(async (cursor?: number) => {
    const isFirstPage = cursor === undefined;
    if (loadingRef.current) return;
    loadingRef.current = true;
    const restoringFocus =
      document.activeElement?.hasAttribute("data-history-retry");

    if (isFirstPage) setLoading(true);

    try {
      const result = await commands.getHistoryEntries(
        cursor ?? null,
        PAGE_SIZE,
      );
      if (result.status === "ok") {
        const { entries: newEntries, has_more } = result.data;
        setEntries((prev) =>
          isFirstPage ? newEntries : [...prev, ...newEntries],
        );
        setHasMore(has_more);
        setLoadError(false);
        if (restoringFocus)
          requestAnimationFrame(() =>
            document.getElementById("page-heading")?.focus(),
          );
      } else {
        setLoadError(true);
        anonymousTelemetry.error("storage_failed", "settings");
      }
    } catch (error) {
      console.error("Failed to load history entries:", error);
      setLoadError(true);
      anonymousTelemetry.error("storage_failed", "settings");
    } finally {
      setLoading(false);
      loadingRef.current = false;
    }
  }, []);

  // Initial load
  useEffect(() => {
    loadPage();
  }, [loadPage]);

  // Infinite scroll via IntersectionObserver
  useEffect(() => {
    if (loading || loadError) return;

    const sentinel = sentinelRef.current;
    if (!sentinel || !hasMore) return;

    const observer = new IntersectionObserver(
      (observerEntries) => {
        const first = observerEntries[0];
        if (first.isIntersecting) {
          const lastEntry = entriesRef.current[entriesRef.current.length - 1];
          if (lastEntry) {
            loadPage(lastEntry.id);
          }
        }
      },
      { threshold: 0 },
    );

    observer.observe(sentinel);
    return () => observer.disconnect();
  }, [loading, loadError, hasMore, loadPage]);

  // Listen for new entries added from the transcription pipeline
  useEffect(() => {
    const unlisten = events.historyUpdatePayload.listen((event) => {
      const payload: HistoryUpdatePayload = event.payload;
      if (payload.action === "added") {
        setEntries((prev) => [payload.entry, ...prev]);
      } else if (payload.action === "updated") {
        setEntries((prev) =>
          prev.map((e) => (e.id === payload.entry.id ? payload.entry : e)),
        );
      }
      // "deleted" and "toggled" are handled by optimistic updates only,
      // so we intentionally ignore them here to avoid double-mutation.
    });

    return () => {
      unlisten.then((fn) => fn());
    };
  }, []);

  const toggleSaved = async (id: number) => {
    // Optimistic update
    setEntries((prev) =>
      prev.map((e) => (e.id === id ? { ...e, saved: !e.saved } : e)),
    );
    try {
      const result = await commands.toggleHistoryEntrySaved(id);
      if (result.status !== "ok") {
        toast.error(
          t("murmur.history.saveError", {
            defaultValue:
              "Could not change whether this dictation is kept. Try again.",
          }),
        );
        // Revert on failure
        setEntries((prev) =>
          prev.map((e) => (e.id === id ? { ...e, saved: !e.saved } : e)),
        );
      }
    } catch (error) {
      console.error("Failed to toggle saved status:", error);
      toast.error(
        t("murmur.history.saveError", {
          defaultValue:
            "Could not change whether this dictation is kept. Try again.",
        }),
      );
      // Revert on failure
      setEntries((prev) =>
        prev.map((e) => (e.id === id ? { ...e, saved: !e.saved } : e)),
      );
    }
  };

  const getAudioUrl = useCallback(
    async (fileName: string) => {
      try {
        const result = await commands.getAudioFilePath(fileName);
        if (result.status === "ok") {
          if (osType === "linux") {
            const fileData = await readFile(result.data);
            const blob = new Blob([fileData], { type: "audio/wav" });
            return URL.createObjectURL(blob);
          }
          return convertFileSrc(result.data, "asset");
        }
        return null;
      } catch (error) {
        console.error("Failed to get audio file path:", error);
        return null;
      }
    },
    [osType],
  );

  const deleteAudioEntry = async (id: number) => {
    const result = await commands.deleteHistoryEntry(id);
    if (result.status !== "ok") throw new Error(String(result.error));
    setEntries((prev) => prev.filter((e) => e.id !== id));
  };

  const retryHistoryEntry = async (id: number) => {
    const result = await commands.retryHistoryEntryTranscription(id);
    if (result.status !== "ok") {
      throw new Error(String(result.error));
    }
  };

  const openRecordingsFolder = async () => {
    try {
      const result = await commands.openRecordingsFolder();
      if (result.status !== "ok") {
        throw new Error(String(result.error));
      }
    } catch (error) {
      console.error("Failed to open recordings folder:", error);
      toast.error(
        t("murmur.history.folderError", {
          defaultValue:
            "Could not open the recordings folder. Your transcripts are still available here.",
        }),
      );
    }
  };

  const exportArchive = async () => {
    const date = new Date().toISOString().slice(0, 10);
    try {
      const destination = await save({
        defaultPath: `murmur-history-${date}.tar.gz`,
        filters: [{ name: "Compressed archive", extensions: ["gz"] }],
      });
      if (!destination) return;
      setExporting(true);
      const result = await commands.exportHistoryArchive(destination);
      if (result.status === "error") throw new Error(String(result.error));
      if (result.data.audio_missing > 0) {
        toast.warning(
          t("murmur.history.archive.partial", {
            count: result.data.entries,
            missing: result.data.audio_missing,
            defaultValue:
              "Archived {{count}} dictations, but {{missing}} recordings were already missing.",
          }),
        );
      } else {
        toast.success(
          t("murmur.history.archive.complete", {
            count: result.data.entries,
            defaultValue:
              "Archived {{count}} dictations. Originals are unchanged.",
          }),
        );
      }
    } catch (error) {
      toast.error(
        error instanceof Error
          ? error.message
          : t("murmur.history.archive.error", {
              defaultValue: "Could not export history.",
            }),
      );
    } finally {
      setExporting(false);
    }
  };

  let content: React.ReactNode;

  if (loading) {
    content = (
      <div role="status" className="px-4 py-3 text-center text-text/60">
        {t("settings.history.loading")}
      </div>
    );
  } else if (entries.length === 0 && !loadError) {
    content = (
      <div className="px-4 py-3 text-center text-text/60">
        {t("settings.history.empty")}
      </div>
    );
  } else {
    content = (
      <>
        <AudioPlayerGroup>
          <div>
            {entries.map((entry, index) => {
              const day = new Date(entry.timestamp * 1000).toDateString();
              const previousDay =
                index > 0
                  ? new Date(entries[index - 1].timestamp * 1000).toDateString()
                  : null;
              return (
                <React.Fragment key={entry.id}>
                  {day !== previousDay && (
                    <h3 className="border-y border-mid-gray/15 bg-mid-gray/5 px-4 py-2 text-sm font-semibold text-text first:border-t-0">
                      {formatDate(String(entry.timestamp), i18n.language)}
                    </h3>
                  )}
                  <div className="border-b border-mid-gray/15 last:border-b-0">
                    <HistoryEntryComponent
                      entry={entry}
                      onToggleSaved={() => toggleSaved(entry.id)}
                      onCopyText={copyToClipboard}
                      getAudioUrl={getAudioUrl}
                      deleteAudio={deleteAudioEntry}
                      retryTranscription={retryHistoryEntry}
                    />
                  </div>
                </React.Fragment>
              );
            })}
          </div>
        </AudioPlayerGroup>
        {/* Sentinel for infinite scroll */}
        <div ref={sentinelRef} className="h-1" />
      </>
    );
  }

  return (
    <div className="w-full space-y-6">
      <div className="space-y-2">
        <div className="px-4 flex flex-wrap gap-2 items-center justify-between">
          <div>
            <h2 className="text-xs font-medium text-mid-gray uppercase tracking-wide">
              {t("settings.history.title")}
            </h2>
          </div>
          <OpenRecordingsButton
            onClick={openRecordingsFolder}
            label={t("settings.history.openFolder")}
          />
        </div>
        <div className="bg-surface border border-mid-gray/15 rounded-xl overflow-visible">
          {content}
          {loadError && (
            <div className="px-4 py-3">
              <p role="alert" className="mb-2 text-sm text-text">
                {t("murmur.history.loadError", {
                  defaultValue:
                    "Could not load history. Your saved dictations have not been removed.",
                })}
              </p>
              <Button
                variant="secondary"
                size="sm"
                data-history-retry
                aria-busy={loading}
                onClick={() =>
                  void loadPage(
                    entries.length ? entries[entries.length - 1].id : undefined,
                  )
                }
              >
                {t("murmur.history.retryLoad", {
                  defaultValue: "Retry loading",
                })}
              </Button>
            </div>
          )}
        </div>
      </div>

      <SettingsGroup
        title={t("murmur.history.storage.title", {
          defaultValue: "Storage and archive",
        })}
      >
        <p className="px-4 pt-3 text-xs leading-relaxed text-mid-gray">
          {t("murmur.history.storage.description", {
            defaultValue:
              "Choose how long to keep recordings and transcripts. Export a compressed copy with both raw and cleaned text whenever you want a long-term archive.",
          })}
        </p>
        <RecordingRetentionPeriodSelector descriptionMode="tooltip" grouped />
        {getSetting("recording_retention_period") === "preserve_limit" && (
          <HistoryLimit descriptionMode="tooltip" grouped />
        )}
        <div className="border-t border-mid-gray/20 px-4 py-3">
          <Button
            onClick={() => void exportArchive()}
            variant="secondary"
            size="sm"
            disabled={exporting}
            className="inline-flex items-center gap-2"
          >
            <Archive className="h-4 w-4" aria-hidden="true" />
            {exporting
              ? t("murmur.history.archive.working", {
                  defaultValue: "Creating archive…",
                })
              : t("murmur.history.archive.action", {
                  defaultValue: "Export compressed archive",
                })}
          </Button>
          <p className="mt-2 text-xs text-mid-gray">
            {t("murmur.history.archive.note", {
              defaultValue:
                "Lossless compression keeps the original WAV audio. The archive is not encrypted, and exporting never deletes local history.",
            })}
          </p>
        </div>
      </SettingsGroup>
    </div>
  );
};

interface HistoryEntryProps {
  entry: HistoryEntry;
  onToggleSaved: () => void;
  onCopyText: (text: string) => Promise<boolean>;
  getAudioUrl: (fileName: string) => Promise<string | null>;
  deleteAudio: (id: number) => Promise<void>;
  retryTranscription: (id: number) => Promise<void>;
}

const HistoryEntryComponent: React.FC<HistoryEntryProps> = ({
  entry,
  onToggleSaved,
  onCopyText,
  getAudioUrl,
  deleteAudio,
  retryTranscription,
}) => {
  const { t, i18n } = useTranslation();
  const [showCopied, setShowCopied] = useState(false);
  const [retrying, setRetrying] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [showRaw, setShowRaw] = useState(false);

  const cleanedText = entry.post_processed_text?.trim()
    ? entry.post_processed_text
    : null;
  const displayText = showRaw
    ? entry.transcription_text
    : (cleanedText ?? entry.transcription_text);
  const hasTranscription = displayText.trim().length > 0;
  const busy = retrying || deleting;

  const handleLoadAudio = useCallback(
    () => getAudioUrl(entry.file_name),
    [getAudioUrl, entry.file_name],
  );

  const [actionStatus, setActionStatus] = useState("");
  const confirmationPending = useRef(false);

  const handleCopyText = async () => {
    if (!hasTranscription) {
      return;
    }

    const copied = await onCopyText(displayText);
    if (!copied) {
      toast.error(t("settings.history.copyError"));
      return;
    }

    setActionStatus(
      t("murmur.history.copied", {
        defaultValue: "Copied displayed transcript.",
      }),
    );
    setShowCopied(true);
    setTimeout(() => setShowCopied(false), 2000);
  };

  const handleDeleteEntry = async () => {
    if (confirmationPending.current) return;
    confirmationPending.current = true;
    const previousFocus =
      document.activeElement instanceof HTMLElement
        ? document.activeElement
        : null;
    try {
      const confirmed = await ask(
        t("murmur.history.deleteMessage", {
          defaultValue:
            "This permanently removes the raw and cleaned text and its recording.",
        }),
        {
          title: t("murmur.history.deleteTitle", {
            defaultValue: "Delete dictation?",
          }),
          kind: "warning",
          okLabel: t("common.delete", { defaultValue: "Delete" }),
          cancelLabel: t("common.cancel", { defaultValue: "Cancel" }),
        },
      );
      if (!confirmed) return;
      setDeleting(true);
      await deleteAudio(entry.id);
    } catch (error) {
      console.error("Failed to delete entry:", error);
      toast.error(t("settings.history.deleteError"));
    } finally {
      setDeleting(false);
      confirmationPending.current = false;
      requestAnimationFrame(() => {
        if (previousFocus?.isConnected) previousFocus.focus();
        else
          (
            document.querySelector<HTMLButtonElement>(
              "[data-history-entry] button",
            ) ?? document.getElementById("page-heading")
          )?.focus();
      });
    }
  };

  const handleRetranscribe = async () => {
    try {
      setRetrying(true);
      setActionStatus(t("settings.history.transcribing"));
      await retryTranscription(entry.id);
      setActionStatus(
        t("murmur.history.retryFinished", {
          defaultValue:
            "Transcription request finished. Check this entry for the result.",
        }),
      );
    } catch (error) {
      console.error("Failed to re-transcribe:", error);
      setActionStatus(t("settings.history.retranscribeError"));
      toast.error(t("settings.history.retranscribeError"));
    } finally {
      setRetrying(false);
    }
  };

  const formattedDate = formatDateTime(String(entry.timestamp), i18n.language);

  return (
    <div data-history-entry className="px-4 py-2 pb-5 flex flex-col gap-3">
      <p role="status" className="sr-only">
        {actionStatus}
      </p>
      <div className="flex flex-wrap gap-2 justify-between items-center">
        <p className="text-sm font-medium">{formattedDate}</p>
        <div className="flex items-center">
          <IconButton
            onClick={handleCopyText}
            disabled={!hasTranscription || busy}
            title={t("settings.history.copyToClipboard")}
          >
            {showCopied ? (
              <Check width={16} height={16} />
            ) : (
              <Copy width={16} height={16} />
            )}
          </IconButton>
          <IconButton
            onClick={onToggleSaved}
            disabled={busy}
            active={entry.saved}
            title={
              entry.saved
                ? t("settings.history.unsave")
                : t("settings.history.save")
            }
          >
            <Star
              width={16}
              height={16}
              fill={entry.saved ? "currentColor" : "none"}
            />
          </IconButton>
          <IconButton
            onClick={handleRetranscribe}
            disabled={busy}
            title={t("settings.history.retranscribe")}
          >
            <RotateCcw
              width={16}
              height={16}
              style={
                retrying
                  ? { animation: "spin 1s linear infinite reverse" }
                  : undefined
              }
            />
          </IconButton>
          <IconButton
            onClick={handleDeleteEntry}
            disabled={busy}
            title={t("settings.history.delete")}
          >
            <Trash2 width={16} height={16} />
          </IconButton>
        </div>
      </div>

      {cleanedText ? (
        <div className="flex items-center gap-3 text-xs">
          <span className="text-mid-gray">
            {showRaw
              ? t("murmur.history.raw", { defaultValue: "Raw transcript" })
              : t("murmur.history.cleaned", {
                  defaultValue: "Cleaned transcript",
                })}
          </span>
          <button
            type="button"
            disabled={busy}
            aria-pressed={showRaw}
            onClick={() => {
              setShowRaw((raw) => !raw);
              setShowCopied(false);
            }}
            className="rounded-md px-2 py-1 text-logo-primary hover:bg-mid-gray/10 cursor-pointer focus-visible:ring-2 focus-visible:ring-logo-primary max-sm:min-h-11"
          >
            {showRaw
              ? t("murmur.history.showCleaned", {
                  defaultValue: "Show cleaned text",
                })
              : t("murmur.history.showRaw", { defaultValue: "Show raw text" })}
          </button>
        </div>
      ) : entry.post_process_requested && hasTranscription ? (
        <p className="text-xs text-mid-gray">
          {t("murmur.history.rawFallback", {
            defaultValue:
              "Cleanup is unavailable for this entry. The raw transcript is preserved below.",
          })}
        </p>
      ) : null}

      <p
        className={`italic text-sm pb-2 ${
          retrying
            ? ""
            : hasTranscription
              ? "text-text/90 select-text cursor-text whitespace-pre-wrap break-words"
              : "text-text/40"
        }`}
        style={
          retrying
            ? { animation: "transcribe-pulse 3s ease-in-out infinite" }
            : undefined
        }
      >
        {retrying && (
          <style>{`
            @keyframes transcribe-pulse {
              0%, 100% { color: color-mix(in srgb, var(--color-text) 40%, transparent); }
              50% { color: color-mix(in srgb, var(--color-text) 90%, transparent); }
            }
          `}</style>
        )}
        {retrying
          ? t("settings.history.transcribing")
          : hasTranscription
            ? displayText
            : t("settings.history.transcriptionFailed")}
      </p>

      <AudioPlayer onLoadRequest={handleLoadAudio} className="w-full" />
    </div>
  );
};
