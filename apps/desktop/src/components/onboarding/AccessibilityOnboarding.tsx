import { useEffect, useState, useCallback, useRef } from "react";
import { useTranslation } from "react-i18next";
import { platform } from "@tauri-apps/plugin-os";
import {
  checkAccessibilityPermission,
  requestAccessibilityPermission,
  checkMicrophonePermission,
  requestMicrophonePermission,
} from "tauri-plugin-macos-permissions-api";
import { toast } from "sonner";
import { commands } from "@/bindings";
import { useSettingsStore } from "@/stores/settingsStore";
import HandyTextLogo from "../icons/HandyTextLogo";
import { Keyboard, Mic, Check, Loader2 } from "lucide-react";

interface AccessibilityOnboardingProps {
  onComplete: () => void;
  preview?: boolean;
}

type PermissionStatus = "checking" | "needed" | "waiting" | "granted";
type PermissionPlatform = "macos" | "windows" | "other";

interface PermissionsState {
  accessibility: PermissionStatus;
  microphone: PermissionStatus;
}

const AccessibilityOnboarding: React.FC<AccessibilityOnboardingProps> = ({
  onComplete,
  preview = false,
}) => {
  const { t } = useTranslation();
  const refreshAudioDevices = useSettingsStore(
    (state) => state.refreshAudioDevices,
  );
  const refreshOutputDevices = useSettingsStore(
    (state) => state.refreshOutputDevices,
  );
  const [permissionPlatform, setPermissionPlatform] =
    useState<PermissionPlatform | null>(null);
  const [permissions, setPermissions] = useState<PermissionsState>({
    accessibility: "checking",
    microphone: "checking",
  });
  const pollingRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const timeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const errorCountRef = useRef<number>(0);
  const MAX_POLLING_ERRORS = 3;
  const [checkFailed, setCheckFailed] = useState(false);

  const isMacOS = permissionPlatform === "macos";
  const isWindows = permissionPlatform === "windows";
  const showMicrophonePermission = isMacOS || isWindows;
  const showAccessibilityPermission = isMacOS;

  const allGranted = isMacOS
    ? permissions.accessibility === "granted" &&
      permissions.microphone === "granted"
    : isWindows
      ? permissions.microphone === "granted"
      : true;

  const completeOnboarding = useCallback(async () => {
    await Promise.all([refreshAudioDevices(), refreshOutputDevices()]);
    timeoutRef.current = setTimeout(() => onComplete(), 300);
  }, [onComplete, refreshAudioDevices, refreshOutputDevices]);

  const hasWindowsMicrophoneAccess = useCallback(async (): Promise<boolean> => {
    const microphoneStatus =
      await commands.getWindowsMicrophonePermissionStatus();

    if (!microphoneStatus.supported) {
      return true;
    }

    return microphoneStatus.overall_access !== "denied";
  }, []);

  // Check platform and permission status on mount
  useEffect(() => {
    const currentPlatform = platform();
    const nextPlatform: PermissionPlatform =
      currentPlatform === "macos"
        ? "macos"
        : currentPlatform === "windows"
          ? "windows"
          : "other";

    setPermissionPlatform(nextPlatform);

    // Debug previews are intentionally inert: show the permission request UI
    // without checking or changing operating-system permissions.
    if (preview) {
      setPermissions({
        accessibility: nextPlatform === "macos" ? "needed" : "granted",
        microphone: nextPlatform === "other" ? "granted" : "needed",
      });
      return;
    }

    // Skip immediately on unsupported platforms
    if (nextPlatform === "other") {
      onComplete();
      return;
    }

    const checkInitial = async () => {
      if (nextPlatform === "macos") {
        try {
          const [accessibilityGranted, microphoneGranted] = await Promise.all([
            checkAccessibilityPermission(),
            checkMicrophonePermission(),
          ]);

          // If accessibility is granted, initialize Enigo and shortcuts
          if (accessibilityGranted) {
            try {
              await Promise.all([
                commands.initializeEnigo(),
                commands.initializeShortcuts(),
              ]);
            } catch (e) {
              console.warn("Failed to initialize after permission grant:", e);
            }
          }

          const newState: PermissionsState = {
            accessibility: accessibilityGranted ? "granted" : "needed",
            microphone: microphoneGranted ? "granted" : "needed",
          };

          setPermissions(newState);

          if (accessibilityGranted && microphoneGranted) {
            await completeOnboarding();
          }
        } catch (error) {
          console.error("Failed to check macOS permissions:", error);
          setCheckFailed(true);
          toast.error(t("onboarding.permissions.errors.checkFailed"));
          setPermissions({
            accessibility: "needed",
            microphone: "needed",
          });
        }

        return;
      }

      try {
        const microphoneGranted = await hasWindowsMicrophoneAccess();

        setPermissions({
          accessibility: "granted",
          microphone: microphoneGranted ? "granted" : "needed",
        });

        if (microphoneGranted) {
          await completeOnboarding();
        }
      } catch (error) {
        console.warn("Failed to check Windows microphone permissions:", error);
        setPermissions({
          accessibility: "granted",
          microphone: "granted",
        });
        await completeOnboarding();
      }
    };

    checkInitial();
  }, [completeOnboarding, hasWindowsMicrophoneAccess, onComplete, preview, t]);

  // Polling for permissions after user clicks a button
  const startPolling = useCallback(() => {
    if (pollingRef.current || permissionPlatform === null) return;
    errorCountRef.current = 0;
    setCheckFailed(false);

    pollingRef.current = setInterval(async () => {
      try {
        if (permissionPlatform === "windows") {
          const microphoneGranted = await hasWindowsMicrophoneAccess();

          if (microphoneGranted) {
            setPermissions((prev) => ({ ...prev, microphone: "granted" }));

            if (pollingRef.current) {
              clearInterval(pollingRef.current);
              pollingRef.current = null;
            }

            await completeOnboarding();
          }

          errorCountRef.current = 0;
          return;
        }

        const [accessibilityGranted, microphoneGranted] = await Promise.all([
          checkAccessibilityPermission(),
          checkMicrophonePermission(),
        ]);

        setPermissions((prev) => {
          const newState = { ...prev };

          if (accessibilityGranted && prev.accessibility !== "granted") {
            newState.accessibility = "granted";
            // Initialize Enigo and shortcuts when accessibility is granted
            Promise.all([
              commands.initializeEnigo(),
              commands.initializeShortcuts(),
            ]).catch((e) => {
              console.warn("Failed to initialize after permission grant:", e);
            });
          }

          if (microphoneGranted && prev.microphone !== "granted") {
            newState.microphone = "granted";
          }

          return newState;
        });

        // If both granted, stop polling, refresh audio devices, and proceed
        if (accessibilityGranted && microphoneGranted) {
          if (pollingRef.current) {
            clearInterval(pollingRef.current);
            pollingRef.current = null;
          }
          await completeOnboarding();
        }

        // Reset error count on success
        errorCountRef.current = 0;
      } catch (error) {
        console.error("Error checking permissions:", error);
        errorCountRef.current += 1;

        if (errorCountRef.current >= MAX_POLLING_ERRORS) {
          setCheckFailed(true);
          // Stop polling after too many consecutive errors
          if (pollingRef.current) {
            clearInterval(pollingRef.current);
            pollingRef.current = null;
          }
          setPermissions((prev) => ({
            accessibility:
              prev.accessibility === "granted" ? "granted" : "needed",
            microphone: prev.microphone === "granted" ? "granted" : "needed",
          }));
          toast.error(t("onboarding.permissions.errors.checkFailed"));
        }
      }
    }, 1000);
  }, [completeOnboarding, hasWindowsMicrophoneAccess, permissionPlatform, t]);

  // Cleanup polling and timeouts on unmount
  useEffect(() => {
    return () => {
      if (pollingRef.current) {
        clearInterval(pollingRef.current);
      }
      if (timeoutRef.current) {
        clearTimeout(timeoutRef.current);
      }
    };
  }, []);

  const handleGrantAccessibility = async () => {
    if (preview) return;

    try {
      if (permissions.accessibility === "waiting") {
        const granted = await checkAccessibilityPermission();
        if (granted) {
          setPermissions((prev) => ({ ...prev, accessibility: "granted" }));
          if (permissions.microphone === "granted") await completeOnboarding();
        }
        return;
      }
      await requestAccessibilityPermission();
      setPermissions((prev) => ({ ...prev, accessibility: "waiting" }));
      startPolling();
    } catch (error) {
      console.error("Failed to request accessibility permission:", error);
      toast.error(t("onboarding.permissions.errors.requestFailed"));
    }
  };

  const handleGrantMicrophone = async () => {
    if (preview) return;

    try {
      if (isWindows) {
        await commands.openMicrophonePrivacySettings();
      } else if (permissions.microphone === "waiting") {
        const granted = await checkMicrophonePermission();
        if (granted) {
          setPermissions((prev) => ({ ...prev, microphone: "granted" }));
          if (permissions.accessibility === "granted")
            await completeOnboarding();
        }
        return;
      } else {
        await requestMicrophonePermission();
      }

      setPermissions((prev) => ({ ...prev, microphone: "waiting" }));
      startPolling();
    } catch (error) {
      console.error("Failed to request microphone permission:", error);
      toast.error(t("onboarding.permissions.errors.requestFailed"));
    }
  };

  const isChecking =
    permissionPlatform === null ||
    (isMacOS &&
      permissions.accessibility === "checking" &&
      permissions.microphone === "checking") ||
    (isWindows && permissions.microphone === "checking");

  // Still checking platform/initial permissions
  if (isChecking) {
    return (
      <main
        role="status"
        className="h-screen w-full flex items-center justify-center gap-3"
      >
        <Loader2
          aria-hidden="true"
          className="w-8 h-8 animate-spin text-text/50"
        />
        <span>
          {t("murmur.onboarding.checking", {
            defaultValue: "Checking permissions…",
          })}
        </span>
      </main>
    );
  }

  // All permissions granted - show success briefly
  if (allGranted) {
    return (
      <main
        role="status"
        className="h-screen w-full flex flex-col items-center justify-center gap-4"
      >
        <div className="p-4 rounded-full bg-emerald-500/20">
          <Check className="w-12 h-12 text-emerald-400" />
        </div>
        <p className="text-lg font-medium text-text">
          {t("onboarding.permissions.allGranted")}
        </p>
      </main>
    );
  }

  // Show permissions request screen
  return (
    <main
      aria-labelledby="permissions-heading"
      className="min-h-screen w-full flex flex-col p-6 gap-6 items-center justify-center"
    >
      <div className="flex flex-col items-center gap-2">
        <HandyTextLogo width={200} />
      </div>

      <div className="max-w-md w-full flex flex-col items-center gap-4">
        <div className="text-center mb-2">
          <h1
            id="permissions-heading"
            className="text-xl font-semibold text-text mb-2"
          >
            {t("onboarding.permissions.title")}
          </h1>
          <p className="text-text/70">
            {t("onboarding.permissions.description")}
          </p>
        </div>

        {checkFailed && (
          <p role="alert" className="text-sm">
            {t("murmur.onboarding.checkFailed", {
              defaultValue:
                "Permission checks failed. Use the permission action below to retry. Your system settings have not been changed.",
            })}
          </p>
        )}
        {/* Microphone Permission Card */}
        {showMicrophonePermission && (
          <div className="w-full p-4 rounded-lg bg-white/5 border border-mid-gray/20">
            <div className="flex items-center gap-4">
              <div className="p-3 rounded-full bg-logo-primary/20 shrink-0">
                <Mic className="w-6 h-6 text-logo-primary" />
              </div>
              <div className="flex-1 min-w-0">
                <h2 id="microphone-title" className="font-medium text-text">
                  {t("onboarding.permissions.microphone.title")}
                </h2>
                <p
                  id="microphone-description"
                  className="text-sm text-text/60 mb-3"
                >
                  {t("onboarding.permissions.microphone.description")}
                </p>
                {permissions.microphone === "granted" ? (
                  <div
                    role="status"
                    className="flex items-center gap-2 text-emerald-400 text-sm"
                  >
                    <Check className="w-4 h-4" />
                    {t("onboarding.permissions.granted")}
                  </div>
                ) : (
                  <div className="space-y-3">
                    {permissions.microphone === "waiting" && (
                      <p role="status" className="text-sm text-text/60">
                        {t("murmur.onboarding.microphoneWaiting", {
                          defaultValue:
                            "Enable microphone access in system settings, then return here. Murmur will check again automatically.",
                        })}
                      </p>
                    )}
                    <button
                      aria-labelledby="microphone-action microphone-title"
                      aria-describedby="microphone-description"
                      onClick={handleGrantMicrophone}
                      className="px-4 py-2 rounded-lg bg-logo-primary hover:bg-logo-primary/90 text-white text-sm font-medium transition-colors"
                    >
                      <span id="microphone-action">
                        {isWindows
                          ? t("accessibility.openSettings")
                          : permissions.microphone === "waiting"
                            ? t("murmur.onboarding.recheck", {
                                defaultValue: "Check again",
                              })
                            : t("onboarding.permissions.grant")}
                      </span>
                    </button>
                  </div>
                )}
              </div>
            </div>
          </div>
        )}

        {/* Accessibility Permission Card */}
        {showAccessibilityPermission && (
          <div className="w-full p-4 rounded-lg bg-white/5 border border-mid-gray/20">
            <div className="flex items-center gap-4">
              <div className="p-3 rounded-full bg-logo-primary/20 shrink-0">
                <Keyboard className="w-6 h-6 text-logo-primary" />
              </div>
              <div className="flex-1 min-w-0">
                <h2 id="accessibility-title" className="font-medium text-text">
                  {t("onboarding.permissions.accessibility.title")}
                </h2>
                <p
                  id="accessibility-description"
                  className="text-sm text-text/60 mb-3"
                >
                  {t("onboarding.permissions.accessibility.description")}
                </p>
                {permissions.accessibility === "granted" ? (
                  <div
                    role="status"
                    className="flex items-center gap-2 text-emerald-400 text-sm"
                  >
                    <Check className="w-4 h-4" />
                    {t("onboarding.permissions.granted")}
                  </div>
                ) : (
                  <div className="space-y-3">
                    {permissions.accessibility === "waiting" && (
                      <p role="status" className="text-sm text-text/60">
                        {t("murmur.onboarding.accessibilityWaiting", {
                          defaultValue:
                            "Enable Murmur in system accessibility settings, then return here. Murmur will check again automatically.",
                        })}
                      </p>
                    )}
                    <button
                      aria-labelledby="accessibility-action accessibility-title"
                      aria-describedby="accessibility-description"
                      onClick={handleGrantAccessibility}
                      className="px-4 py-2 rounded-lg bg-logo-primary hover:bg-logo-primary/90 text-white text-sm font-medium transition-colors"
                    >
                      <span id="accessibility-action">
                        {permissions.accessibility === "waiting"
                          ? t("murmur.onboarding.recheck", {
                              defaultValue: "Check again",
                            })
                          : t("onboarding.permissions.grant")}
                      </span>
                    </button>
                  </div>
                )}
              </div>
            </div>
          </div>
        )}
      </div>
    </main>
  );
};

export default AccessibilityOnboarding;
