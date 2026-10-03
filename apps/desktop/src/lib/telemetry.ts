export type TelemetryRoute = "app" | "home" | "settings" | "onboarding";
export type CountName =
  "app_open" | "screen_view" | "action_completed" | "action_failed";
export type ErrorName =
  "storage_failed" | "permission_failed" | "media_failed" | "request_failed";

interface Options {
  configured: boolean;
  endpoint: string;
  allowed: () => boolean;
  transport?: typeof fetch;
  now?: () => number;
  schedule?: (callback: () => void) => () => void;
  controller?: () => AbortController;
}

const routes = new Set(["app", "home", "settings", "onboarding"]);
const counts = new Set([
  "app_open",
  "screen_view",
  "action_completed",
  "action_failed",
]);
const errors = new Set([
  "storage_failed",
  "permission_failed",
  "media_failed",
  "request_failed",
]);

export function validTelemetryEndpoint(value: string): boolean {
  try {
    const url = new URL(value);
    return (
      url.protocol === "https:" &&
      url.pathname === "/v1/events" &&
      !url.username &&
      !url.password &&
      !url.search &&
      !url.hash
    );
  } catch {
    return false;
  }
}

export function privacyAllowsTelemetry(): boolean {
  try {
    const privacyNavigator: Navigator & { globalPrivacyControl?: boolean } =
      navigator;
    return (
      privacyNavigator.globalPrivacyControl !== true &&
      privacyNavigator.doNotTrack !== "1" &&
      privacyNavigator.doNotTrack !== "yes"
    );
  } catch {
    return false;
  }
}

export function createTelemetry(options: Options) {
  let inFlight = false;
  let activeController: AbortController | null = null;
  let lifetime = 0;
  let minuteStart = 0;
  let minuteCount = 0;
  const lastError = new Map<string, number>();

  const send = (kind: "count" | "error", name: string, route: string) => {
    if (
      !options.configured ||
      !validTelemetryEndpoint(options.endpoint) ||
      inFlight
    )
      return;
    let cancelTimer: (() => void) | undefined;
    try {
      if (!options.allowed() || !(kind === "count" ? counts : errors).has(name))
        return;
      const category = routes.has(route) ? route : "app";
      const now = (options.now ?? Date.now)();
      if (!Number.isFinite(now) || now < minuteStart || lifetime >= 200) return;
      if (now - minuteStart >= 60_000) {
        minuteStart = now;
        minuteCount = 0;
      }
      const errorKey = `${name}:${category}`;
      if (
        minuteCount >= 20 ||
        (kind === "error" &&
          now - (lastError.get(errorKey) ?? -Infinity) < 60_000)
      )
        return;
      const controller = (
        options.controller ?? (() => new AbortController())
      )();
      cancelTimer = options.schedule
        ? options.schedule(() => controller.abort())
        : (() => {
            const timer = setTimeout(() => controller.abort(), 2000);
            return () => clearTimeout(timer);
          })();
      inFlight = true;
      activeController = controller;
      lifetime += 1;
      minuteCount += 1;
      if (kind === "error") lastError.set(errorKey, now);
      const body = JSON.stringify({
        version: 1,
        app: "murmur",
        kind,
        name,
        surface: "desktop",
        route: category,
      });
      const pending = (options.transport ?? fetch)(options.endpoint, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body,
        credentials: "omit",
        referrerPolicy: "no-referrer",
        redirect: "error",
        cache: "no-store",
        signal: controller.signal,
      });
      void pending
        .catch(() => {})
        .finally(() => {
          try {
            cancelTimer?.();
          } catch {
            /* optional transport cleanup must not affect the app */
          }
          if (activeController === controller) {
            activeController = null;
            inFlight = false;
          }
        });
    } catch {
      try {
        activeController?.abort();
        cancelTimer?.();
      } catch {
        /* optional reporting stays contained */
      }
      activeController = null;
      inFlight = false;
    }
  };

  return {
    count: (name: CountName, route: TelemetryRoute) =>
      send("count", name, route),
    error: (name: ErrorName, route: TelemetryRoute) =>
      send("error", name, route),
    stop: () => {
      try {
        activeController?.abort();
      } catch {
        /* no reporting error reaches the UI */
      }
    },
  };
}
