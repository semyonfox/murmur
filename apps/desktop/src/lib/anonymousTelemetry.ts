import {
  createTelemetry,
  privacyAllowsTelemetry,
  validTelemetryEndpoint,
} from "./telemetry";

let preferenceBlocked = false;
const preferenceKey = "murmur.anonymousTelemetry";
const endpoint = import.meta.env.VITE_MURMUR_TELEMETRY_ENDPOINT ?? "";
export const telemetryConfigured =
  import.meta.env.VITE_MURMUR_TELEMETRY_ENABLED === "true" &&
  validTelemetryEndpoint(endpoint);

export function telemetryPreference(): boolean {
  try {
    return !preferenceBlocked && localStorage.getItem(preferenceKey) === "true";
  } catch {
    return false;
  }
}

export function setTelemetryPreference(enabled: boolean): boolean {
  if (!enabled) anonymousTelemetry.stop();
  try {
    localStorage.setItem(preferenceKey, String(enabled));
    preferenceBlocked = false;
    return true;
  } catch {
    preferenceBlocked = true;
    return false;
  }
}

export const anonymousTelemetry = createTelemetry({
  configured: telemetryConfigured,
  endpoint,
  allowed: () => telemetryPreference() && privacyAllowsTelemetry(),
});
