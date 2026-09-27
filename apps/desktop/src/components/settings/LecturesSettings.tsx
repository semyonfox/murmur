import { useEffect, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import { open } from "@tauri-apps/plugin-dialog";
import { toast } from "sonner";
import { FileAudio, Mic, Square } from "lucide-react";
import { useTranslation } from "react-i18next";
import { Button } from "../ui/Button";

export default function LecturesSettings() {
  const { t } = useTranslation();
  const [recording, setRecording] = useState(false);
  const [busy, setBusy] = useState(false);
  const [started, setStarted] = useState<number | null>(null);
  const [progress, setProgress] = useState<{
    completed: number;
    total: number;
  } | null>(null);

  useEffect(() => {
    const unlisten = listen<{ id: number; completed: number; total: number }>(
      "lecture-progress",
      (event) => {
        setProgress(event.payload);
      },
    );
    return () => {
      unlisten.then((stop) => stop());
    };
  }, []);

  useEffect(() => {
    invoke<number | null>("get_active_lecture")
      .then((id) => {
        setRecording(id !== null);
        if (id !== null) setStarted(Date.now());
      })
      .catch((error) => toast.error(String(error)));
  }, []);

  const start = async () => {
    setBusy(true);
    try {
      await invoke<number>("start_lecture_recording");
      setStarted(Date.now());
      setRecording(true);
    } catch (error) {
      toast.error(String(error));
    } finally {
      setBusy(false);
    }
  };

  const finish = async () => {
    setBusy(true);
    setRecording(false);
    try {
      await invoke<number>("finish_lecture_recording");
      toast.success(
        t("murmur.lectures.saved", {
          defaultValue: "Lecture transcript saved in History",
        }),
      );
    } catch (error) {
      toast.error(
        t("murmur.lectures.retry", {
          defaultValue:
            "Recording saved. Transcription needs a retry: {{error}}",
          error: String(error),
        }),
      );
    } finally {
      setBusy(false);
      setStarted(null);
      setProgress(null);
    }
  };

  const importAudio = async () => {
    const file = await open({
      multiple: false,
      filters: [
        {
          name: "Audio",
          extensions: ["wav", "mp3", "m4a", "mp4", "flac", "ogg"],
        },
      ],
    });
    if (typeof file !== "string") return;
    setBusy(true);
    try {
      await invoke<number>("import_lecture_audio", { source: file });
      toast.success(
        t("murmur.lectures.imported", {
          defaultValue: "Audio transcript saved in History",
        }),
      );
    } catch (error) {
      toast.error(
        t("murmur.lectures.importFailed", {
          defaultValue: "Import or transcription failed: {{error}}",
          error: String(error),
        }),
      );
    } finally {
      setBusy(false);
      setProgress(null);
    }
  };

  return (
    <div className="mx-auto max-w-2xl space-y-6">
      <div className="rounded-xl border border-mid-gray/20 bg-mid-gray/5 p-6">
        <h2 className="text-lg font-semibold text-text">
          {t("murmur.lectures.recordTitle", {
            defaultValue: "Record a lecture",
          })}
        </h2>
        <p className="mt-2 text-sm text-text/70">
          {t("murmur.lectures.recordDescription", {
            defaultValue:
              "Capture from your microphone. The audio is saved as you record, then transcribed when you finish.",
          })}
        </p>
        <div className="mt-5 flex items-center gap-3">
          {recording ? (
            <Button
              onClick={finish}
              disabled={busy}
              className="flex items-center gap-2"
            >
              <Square className="h-4 w-4" />{" "}
              {t("murmur.lectures.finish", {
                defaultValue: "Finish and transcribe",
              })}
            </Button>
          ) : (
            <Button
              onClick={start}
              disabled={busy}
              className="flex items-center gap-2"
            >
              <Mic className="h-4 w-4" />{" "}
              {t("murmur.lectures.record", { defaultValue: "Record lecture" })}
            </Button>
          )}
          {recording && (
            <span className="text-sm text-text/70">
              {t("murmur.lectures.recordingSince", {
                defaultValue: "Recording since {{time}}",
                time: started
                  ? new Date(started).toLocaleTimeString()
                  : t("murmur.lectures.earlier", { defaultValue: "earlier" }),
              })}
            </span>
          )}
          {busy && !recording && (
            <span className="text-sm text-text/70">
              {progress
                ? t("murmur.lectures.progress", {
                    defaultValue:
                      "Transcribing {{completed}} of {{total}} parts",
                    completed: progress.completed,
                    total: progress.total,
                  })
                : t("murmur.lectures.working", { defaultValue: "Working…" })}
            </span>
          )}
        </div>
      </div>
      <div className="rounded-xl border border-mid-gray/20 bg-mid-gray/5 p-6">
        <h2 className="text-lg font-semibold text-text">
          {t("murmur.lectures.importTitle", {
            defaultValue: "Transcribe an audio file",
          })}
        </h2>
        <p className="mt-2 text-sm text-text/70">
          {t("murmur.lectures.importDescription", {
            defaultValue:
              "Choose a recording to add it to History. Murmur uses your selected speech and cleanup providers.",
          })}
        </p>
        <Button
          variant="secondary"
          onClick={importAudio}
          disabled={busy || recording}
          className="mt-5 flex items-center gap-2"
        >
          <FileAudio className="h-4 w-4" />{" "}
          {t("murmur.lectures.choose", { defaultValue: "Choose audio file" })}
        </Button>
      </div>
      <p className="text-sm text-text/60">
        {t("murmur.lectures.privacy", {
          defaultValue:
            "Raw audio and transcripts follow your History retention settings. Cloud providers receive audio only when you select an endpoint speech mode.",
        })}
      </p>
    </div>
  );
}
