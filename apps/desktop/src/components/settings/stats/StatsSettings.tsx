import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import { invoke } from "@tauri-apps/api/core";
import { RefreshCw } from "lucide-react";
import { useTranslation } from "react-i18next";
import { z } from "zod";
import { events, type HistoryStats } from "@/bindings";
import { Button } from "../../ui/Button";
import { OpenRouterUsageCard } from "./OpenRouterUsageCard";

const historyStatsSchema = z.object({
  total_words: z.number().int().nonnegative(),
  transcription_count: z.number().int().nonnegative(),
  recordings_with_duration: z.number().int().nonnegative(),
  recording_seconds: z.number().finite().nonnegative(),
  recorded_wpm: z.number().finite().nonnegative().nullable(),
  this_week_words: z.number().int().nonnegative(),
  previous_week_words: z.number().int().nonnegative(),
  week_over_week_percent: z.number().finite().nullable(),
  weeks: z.array(
    z.object({
      week_start: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
      words: z.number().int().nonnegative(),
      recording_seconds: z.number().finite().nonnegative(),
      recorded_wpm: z.number().finite().nonnegative().nullable(),
    }),
  ),
});

interface DisplayWeek {
  date: Date;
  weekStart: string;
  words: number;
  recordedWpm: number | null;
}

function recentWeeks(stats: HistoryStats): DisplayWeek[] {
  const statsByWeek = new Map(
    stats.weeks.map((week) => [week.week_start, week]),
  );
  const now = new Date();
  const monday = new Date(now.getFullYear(), now.getMonth(), now.getDate(), 12);
  monday.setDate(monday.getDate() - ((monday.getDay() + 6) % 7));

  return Array.from({ length: 8 }, (_, index) => {
    const date = new Date(monday);
    date.setDate(date.getDate() - (7 - index) * 7);
    const weekStart = `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(date.getDate()).padStart(2, "0")}`;
    const week = statsByWeek.get(weekStart);

    return {
      date,
      weekStart,
      words: week?.words ?? 0,
      recordedWpm: week?.recorded_wpm ?? null,
    };
  });
}

export const StatsSettings: React.FC = () => {
  const { t, i18n } = useTranslation();
  const [stats, setStats] = useState<HistoryStats | null>(null);
  const [error, setError] = useState(false);
  const [isLoading, setIsLoading] = useState(true);
  const statsRequestRef = useRef(0);

  const loadStats = useCallback(async () => {
    const request = ++statsRequestRef.current;
    setIsLoading(true);

    try {
      const response = await invoke<HistoryStats>("get_history_stats");
      const result = historyStatsSchema.parse(response);
      if (request === statsRequestRef.current) {
        setStats(result);
        setError(false);
      }
    } catch (loadError) {
      console.error("Failed to load history statistics:", loadError);
      if (request === statsRequestRef.current) setError(true);
    } finally {
      if (request === statsRequestRef.current) setIsLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadStats();
    return () => {
      statsRequestRef.current += 1;
    };
  }, [loadStats]);

  useEffect(() => {
    const unlisten = events.historyUpdatePayload.listen(() => {
      void loadStats();
    });

    return () => {
      void unlisten.then((stopListening) => stopListening());
    };
  }, [loadStats]);

  const number = useMemo(
    () => new Intl.NumberFormat(i18n.language),
    [i18n.language],
  );
  const compactNumber = useMemo(
    () =>
      new Intl.NumberFormat(i18n.language, {
        notation: "compact",
        maximumFractionDigits: 1,
      }),
    [i18n.language],
  );
  const weeks = stats ? recentWeeks(stats) : [];
  const highestWeek = Math.max(1, ...weeks.map((week) => week.words));
  const hasAudio = (stats?.recordings_with_duration ?? 0) > 0;
  const trend = stats?.week_over_week_percent;
  const trendLabel =
    trend == null
      ? t("murmur.history.stats.noPreviousWeek", {
          defaultValue: "No previous week to compare",
        })
      : new Intl.NumberFormat(i18n.language, {
          style: "percent",
          maximumFractionDigits: 0,
          signDisplay: trend === 0 ? "auto" : "always",
        }).format(trend / 100);

  return (
    <div className="w-full space-y-6">
      <section
        aria-busy={isLoading}
        aria-labelledby="stats-heading"
        className="overflow-hidden rounded-xl border border-mid-gray/15 bg-surface"
      >
        <div className="flex flex-wrap items-start justify-between gap-3 border-b border-mid-gray/20 px-4 py-4 sm:px-5">
          <div>
            <h2
              id="stats-heading"
              className="text-base font-semibold text-text"
            >
              {t("murmur.stats.title", {
                defaultValue: "Your dictation stats",
              })}
            </h2>
            <p className="mt-1 text-xs leading-relaxed text-mid-gray">
              {t("murmur.history.stats.retainedOnly", {
                defaultValue:
                  "Based on retained history. Deleted dictations are not counted.",
              })}
            </p>
          </div>
          <Button
            variant="secondary"
            size="sm"
            onClick={() => void loadStats()}
            disabled={isLoading}
            className="inline-flex items-center gap-2"
          >
            <RefreshCw
              className={`h-3.5 w-3.5 ${isLoading ? "animate-spin" : ""}`}
              aria-hidden="true"
            />
            {t("murmur.stats.refresh", { defaultValue: "Refresh" })}
          </Button>
        </div>

        {stats && error && (
          <p
            role="status"
            className="border-b border-mid-gray/20 px-4 py-2 text-xs text-mid-gray sm:px-5"
          >
            {t("murmur.history.stats.stale", {
              defaultValue:
                "Could not refresh statistics. These figures may be out of date.",
            })}
          </p>
        )}

        {!stats && isLoading && (
          <div className="space-y-5 px-4 py-5 sm:px-5" role="status">
            <p className="text-sm text-mid-gray">
              {t("murmur.history.stats.loading", {
                defaultValue: "Loading statistics…",
              })}
            </p>
            <div className="grid grid-cols-2 gap-px overflow-hidden rounded-lg bg-mid-gray/15 sm:grid-cols-4">
              {Array.from({ length: 4 }, (_, index) => (
                <div key={index} className="bg-surface px-4 py-4">
                  <div className="h-3 w-20 animate-pulse rounded bg-mid-gray/15" />
                  <div className="mt-3 h-7 w-16 animate-pulse rounded bg-mid-gray/15" />
                </div>
              ))}
            </div>
          </div>
        )}

        {!stats && !isLoading && error && (
          <div className="px-4 py-8 text-center sm:px-5" role="alert">
            <p className="text-sm text-text">
              {t("murmur.history.stats.unavailable", {
                defaultValue: "Statistics are unavailable right now.",
              })}
            </p>
            <p className="mt-1 text-xs text-mid-gray">
              {t("murmur.stats.unavailableHelp", {
                defaultValue:
                  "Try again after Murmur has finished starting up.",
              })}
            </p>
          </div>
        )}

        {stats && (
          <>
            <div className="grid grid-cols-2 divide-x divide-y divide-mid-gray/15 sm:grid-cols-4 sm:divide-y-0">
              <StatCard
                label={t("murmur.history.stats.totalWords", {
                  defaultValue: "Retained words",
                })}
                value={number.format(stats.total_words)}
              />
              <StatCard
                label={t("murmur.history.stats.thisWeek", {
                  defaultValue: "This week",
                })}
                value={number.format(stats.this_week_words)}
              />
              <StatCard
                label={t("murmur.history.stats.recordedPace", {
                  defaultValue: "Recorded pace",
                })}
                value={
                  stats.recorded_wpm == null
                    ? "—"
                    : `${number.format(Math.round(stats.recorded_wpm))} ${t("murmur.history.stats.wpm", { defaultValue: "wpm" })}`
                }
                unavailable={!hasAudio}
                unavailableLabel={t("murmur.stats.noAudioShort", {
                  defaultValue: "No retained audio",
                })}
              />
              <StatCard
                label={t("murmur.stats.recordedMinutes", {
                  defaultValue: "Recorded minutes",
                })}
                value={
                  hasAudio
                    ? `${new Intl.NumberFormat(i18n.language, { maximumFractionDigits: 1 }).format(stats.recording_seconds / 60)} ${t("murmur.history.stats.minutes", { defaultValue: "min" })}`
                    : "—"
                }
                unavailable={!hasAudio}
                unavailableLabel={t("murmur.stats.noAudioShort", {
                  defaultValue: "No retained audio",
                })}
              />
            </div>

            <div className="border-t border-mid-gray/20 px-4 pb-4 pt-4 sm:px-5">
              <div className="flex flex-wrap items-baseline justify-between gap-2">
                <h3 className="text-sm font-medium text-text">
                  {t("murmur.history.stats.weeklyWords", {
                    defaultValue: "Words each week",
                  })}
                </h3>
                <p className="text-xs tabular-nums text-mid-gray">
                  {trendLabel}
                  {trend != null &&
                    ` ${t("murmur.history.stats.vsPrevious", {
                      defaultValue: "vs last full week",
                    })}`}
                </p>
              </div>

              <ul
                className="mt-4 grid h-36 grid-cols-8 items-end gap-2 pb-2"
                aria-label={t("murmur.history.stats.weeklyWords", {
                  defaultValue: "Words each week",
                })}
              >
                {weeks.map((week, index) => {
                  const dateLabel = new Intl.DateTimeFormat(i18n.language, {
                    month: "short",
                    day: "numeric",
                  }).format(week.date);
                  const wordsLabel = t("murmur.history.stats.weekBar", {
                    date: dateLabel,
                    count: week.words,
                    defaultValue: "{{date}}: {{count}} words",
                  });

                  return (
                    <li
                      key={week.weekStart}
                      className="flex min-w-0 flex-col items-center gap-1.5"
                      aria-label={wordsLabel}
                      title={wordsLabel}
                    >
                      <span className="text-[10px] tabular-nums text-mid-gray">
                        {week.words > 0 ? compactNumber.format(week.words) : ""}
                      </span>
                      <div className="flex h-[68px] w-full items-end rounded-sm bg-mid-gray/10">
                        <div
                          className={`w-full rounded-sm ${week.words === 0 ? "bg-mid-gray/30" : index === 7 ? "bg-logo-primary" : "bg-logo-primary/55"}`}
                          style={{
                            height: `${week.words === 0 ? 3 : Math.max(6, Math.round((week.words / highestWeek) * 68))}px`,
                          }}
                          aria-hidden="true"
                        />
                      </div>
                      <span className="truncate text-[10px] text-mid-gray">
                        {dateLabel}
                      </span>
                    </li>
                  );
                })}
              </ul>

              <div className="mt-4 border-t border-mid-gray/20 pt-3">
                <h3 className="text-sm font-medium text-text">
                  {t("murmur.history.stats.weeklyPace", {
                    defaultValue: "Recorded pace each week",
                  })}
                </h3>
                <div className="mt-2 grid grid-cols-8 gap-2 text-center tabular-nums">
                  {weeks.map((week) => {
                    const dateLabel = new Intl.DateTimeFormat(i18n.language, {
                      month: "short",
                      day: "numeric",
                    }).format(week.date);
                    const paceLabel =
                      week.recordedWpm == null
                        ? t("murmur.history.stats.weekPaceUnavailable", {
                            date: dateLabel,
                            defaultValue: "{{date}}: no recording data",
                          })
                        : t("murmur.history.stats.weekPaceValue", {
                            date: dateLabel,
                            wpm: number.format(Math.round(week.recordedWpm)),
                            defaultValue: "{{date}}: {{wpm}} words per minute",
                          });

                    return (
                      <div
                        key={week.weekStart}
                        className="min-w-0 text-[10px] text-mid-gray"
                        aria-label={paceLabel}
                        title={paceLabel}
                      >
                        {week.recordedWpm == null
                          ? "—"
                          : number.format(Math.round(week.recordedWpm))}
                      </div>
                    );
                  })}
                </div>
              </div>

              <p className="mt-4 text-[11px] leading-relaxed text-mid-gray">
                {hasAudio
                  ? t("murmur.history.stats.method", {
                      covered: stats.recordings_with_duration,
                      count: stats.transcription_count,
                      defaultValue:
                        "Pace uses {{covered}} of {{count}} recordings with audio and includes pauses. This week is still in progress.",
                    })
                  : t("murmur.stats.noAudio", {
                      defaultValue:
                        "No retained recordings with audio are available, so recorded pace and minutes cannot be calculated. This week is still in progress.",
                    })}
              </p>
            </div>
          </>
        )}
      </section>

      <OpenRouterUsageCard />
    </div>
  );
};

interface StatCardProps {
  label: string;
  value: string;
  unavailable?: boolean;
  unavailableLabel?: string;
}

const StatCard: React.FC<StatCardProps> = ({
  label,
  value,
  unavailable,
  unavailableLabel,
}) => (
  <div className="px-4 py-4 sm:px-5">
    <p className="text-xs text-mid-gray">{label}</p>
    <p className="mt-1 text-2xl font-semibold tracking-tight tabular-nums text-text">
      {value}
    </p>
    {unavailable && unavailableLabel && (
      <p className="mt-1 text-[11px] leading-tight text-mid-gray">
        {unavailableLabel}
      </p>
    )}
  </div>
);
