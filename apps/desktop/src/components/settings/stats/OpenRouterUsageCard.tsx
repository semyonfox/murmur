import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { RefreshCw } from "lucide-react";
import { useTranslation } from "react-i18next";
import { z } from "zod";
import { Button } from "../../ui/Button";

const usageSchema = z.object({
  key_count: z.number().int().nonnegative(),
  usage: z.number().finite().nonnegative(),
  usage_daily: z.number().finite().nonnegative(),
  usage_weekly: z.number().finite().nonnegative(),
  usage_monthly: z.number().finite().nonnegative(),
  byok_usage: z.number().finite().nonnegative(),
  byok_usage_daily: z.number().finite().nonnegative(),
  byok_usage_weekly: z.number().finite().nonnegative(),
  byok_usage_monthly: z.number().finite().nonnegative(),
});

type Usage = z.infer<typeof usageSchema>;

export function OpenRouterUsageCard() {
  const { t, i18n } = useTranslation();
  const [usage, setUsage] = useState<Usage | null>(null);
  const [error, setError] = useState(false);
  const [loading, setLoading] = useState(true);
  const requestRef = useRef(0);
  const currency = useMemo(
    () =>
      new Intl.NumberFormat(i18n.language, {
        style: "currency",
        currency: "USD",
        minimumFractionDigits: 2,
        maximumFractionDigits: 6,
      }),
    [i18n.language],
  );

  const loadUsage = useCallback(async () => {
    const request = ++requestRef.current;
    setLoading(true);
    try {
      const response = await invoke<unknown>("get_openrouter_key_usage");
      const parsed = usageSchema.parse(response);
      if (request === requestRef.current) {
        setUsage(parsed);
        setError(false);
      }
    } catch {
      if (request === requestRef.current) setError(true);
    } finally {
      if (request === requestRef.current) setLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadUsage();
    return () => {
      requestRef.current += 1;
    };
  }, [loadUsage]);

  const money = (amount: number) =>
    amount > 0 && amount < 0.000001 ? "<$0.000001" : currency.format(amount);

  return (
    <section
      aria-busy={loading}
      aria-labelledby="openrouter-usage-heading"
      className="overflow-hidden rounded-xl border border-mid-gray/15 bg-surface"
    >
      <div className="flex items-start justify-between gap-3 border-b border-mid-gray/20 px-4 py-4 sm:px-5">
        <div>
          <h2
            id="openrouter-usage-heading"
            className="text-base font-semibold text-text"
          >
            {t("murmur.stats.providerCost", { defaultValue: "Provider cost" })}
          </h2>
          <p className="mt-1 text-xs text-mid-gray">
            {t("murmur.stats.openrouterKeys", {
              defaultValue: "OpenRouter usage for your configured keys",
            })}
          </p>
        </div>
        <Button
          variant="secondary"
          size="sm"
          onClick={() => void loadUsage()}
          disabled={loading}
          className="inline-flex items-center gap-2"
        >
          <RefreshCw
            className={`h-3.5 w-3.5 ${loading ? "animate-spin" : ""}`}
            aria-hidden="true"
          />
          {t("murmur.stats.refresh", { defaultValue: "Refresh" })}
        </Button>
      </div>

      {usage && error && (
        <p
          role="status"
          className="border-b border-mid-gray/20 px-4 py-2 text-xs text-mid-gray sm:px-5"
        >
          {t("murmur.stats.costStale", {
            defaultValue:
              "Could not refresh provider usage. These figures may be out of date.",
          })}
        </p>
      )}

      {!usage && loading && (
        <p role="status" className="px-4 py-5 text-sm text-mid-gray sm:px-5">
          {t("murmur.stats.costLoading", {
            defaultValue: "Checking OpenRouter usage…",
          })}
        </p>
      )}
      {!usage && !loading && error && (
        <p role="alert" className="px-4 py-5 text-sm text-mid-gray sm:px-5">
          {t("murmur.stats.costUnavailable", {
            defaultValue:
              "OpenRouter usage is unavailable. Check your connection and key, then retry.",
          })}
        </p>
      )}
      {usage?.key_count === 0 && (
        <p className="px-4 py-5 text-sm text-mid-gray sm:px-5">
          {t("murmur.stats.noOpenrouterKey", {
            defaultValue:
              "Add an OpenRouter speech key in Models or a cleanup key in Cleanup to see its usage here.",
          })}
        </p>
      )}
      {usage && usage.key_count > 0 && (
        <>
          <div className="grid grid-cols-3 divide-x divide-mid-gray/15">
            {[
              {
                label: t("murmur.stats.costWeek", {
                  defaultValue: "This week",
                }),
                value: usage.usage_weekly,
              },
              {
                label: t("murmur.stats.costMonth", {
                  defaultValue: "This month",
                }),
                value: usage.usage_monthly,
              },
              {
                label: t("murmur.stats.costAll", { defaultValue: "All time" }),
                value: usage.usage,
              },
            ].map(({ label, value }) => (
              <div key={label} className="min-w-0 px-3 py-4 sm:px-5">
                <p className="text-xs text-mid-gray">{label}</p>
                <p className="mt-1 break-words text-lg font-semibold tabular-nums text-text">
                  {money(value)}
                </p>
              </div>
            ))}
          </div>
          <div className="space-y-2 border-t border-mid-gray/20 px-4 py-3 text-xs leading-relaxed text-mid-gray sm:px-5">
            {usage.byok_usage_monthly > 0 && (
              <p>
                {t("murmur.stats.byokValue", {
                  value: money(usage.byok_usage_monthly),
                  defaultValue:
                    "OpenRouter also reports {{value}} in BYOK model usage this month. Your provider bills that separately.",
                })}
              </p>
            )}
            <p>
              {t("murmur.stats.costScope", {
                count: usage.key_count,
                defaultValue:
                  "These totals cover {{count}} configured OpenRouter key(s), including use outside Murmur. Local models and direct provider keys are not counted.",
              })}
            </p>
          </div>
        </>
      )}
    </section>
  );
}
