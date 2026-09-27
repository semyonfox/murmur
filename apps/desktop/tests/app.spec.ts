import { expect, test } from "@playwright/test";

const settings = {
  onboarding_completed: true,
  app_language: "en",
  theme: "dark",
  custom_words: [],
  audio_feedback: false,
  sound_theme: "marimba",
  debug_mode: false,
  selected_microphone: "Default",
  selected_output_device: "Default",
  post_process_enabled: false,
};

test.describe("Murmur settings", () => {
  test.beforeEach(async ({ page }) => {
    await page.addInitScript((initialSettings) => {
      const state = { settings: { ...initialSettings } };
      const calls: Array<{ command: string; args: unknown }> = [];
      const callbacks = new Map<number, (event: unknown) => void>();
      let nextCallbackId = 1;

      Object.assign(window, {
        __murmurTestCalls: calls,
        __TAURI_OS_PLUGIN_INTERNALS__: {
          platform: "linux",
          os_type: "linux",
          family: "unix",
          arch: "x86_64",
          eol: "\n",
          exe_extension: "",
        },
        __TAURI_EVENT_PLUGIN_INTERNALS__: {
          unregisterListener: (id: number) => callbacks.delete(id),
        },
        __TAURI_INTERNALS__: {
          metadata: {
            currentWindow: { label: "main" },
            currentWebview: { windowLabel: "main", label: "main" },
          },
          transformCallback: (callback: (event: unknown) => void) => {
            const id = nextCallbackId++;
            callbacks.set(id, callback);
            return id;
          },
          unregisterCallback: (id: number) => callbacks.delete(id),
          invoke: async (
            command: string,
            args: Record<string, unknown> = {},
          ) => {
            calls.push({ command, args });

            if (command === "plugin:event|listen") return args.handler;
            if (command === "plugin:event|unlisten") return null;
            if (command === "plugin:os|locale") return "en-US";

            switch (command) {
              case "get_app_settings":
              case "get_default_settings":
                return { ...state.settings };
              case "update_custom_words":
                state.settings.custom_words = args.words as string[];
                return null;
              case "get_available_models":
                return [];
              case "get_current_model":
                return "";
              case "get_available_microphones":
              case "get_available_output_devices":
                return [];
              case "check_custom_sounds":
                return { start: false, stop: false };
              case "is_update_checks_locked":
                return false;
              case "get_history_stats":
                return {
                  total_words: 1250,
                  transcription_count: 14,
                  recordings_with_duration: 10,
                  recording_seconds: 120,
                  recorded_wpm: 125,
                  this_week_words: 340,
                  previous_week_words: 250,
                  week_over_week_percent: 36,
                  weeks: [],
                };
              case "get_openrouter_key_usage":
                return {
                  key_count: 1,
                  usage: 0.25,
                  usage_daily: 0.01,
                  usage_weekly: 0.041,
                  usage_monthly: 0.11,
                  byok_usage: 0,
                  byok_usage_daily: 0,
                  byok_usage_weekly: 0,
                  byok_usage_monthly: 0,
                };
              default:
                return null;
            }
          },
        },
      });
    }, settings);

    await page.goto("/");
    await expect(
      page.getByRole("heading", { name: "Dictation" }),
    ).toBeVisible();
  });

  test("saves, shows, and removes a custom dictionary term", async ({
    page,
  }) => {
    await page.getByRole("button", { name: "Dictionary", exact: true }).click();
    await expect(
      page.getByRole("heading", { name: "Dictionary", exact: true }),
    ).toBeVisible();

    const input = page.getByRole("textbox", { name: "Add a word" });
    await input.fill("  PostgreSQL  ");
    await input.press("Enter");

    await expect(page.getByText("1 terms saved")).toBeVisible();
    await expect(
      page.getByRole("button", { name: "Remove PostgreSQL" }),
    ).toBeVisible();
    await expect
      .poll(async () =>
        page.evaluate(() =>
          (
            window as unknown as {
              __murmurTestCalls: Array<{
                command: string;
                args: { words?: string[] };
              }>;
            }
          ).__murmurTestCalls
            .filter((call) => call.command === "update_custom_words")
            .map((call) => call.args.words),
        ),
      )
      .toEqual([["PostgreSQL"]]);

    await page.getByRole("button", { name: "Remove PostgreSQL" }).click();
    await expect(page.getByText("1 terms saved")).toHaveCount(0);
    await expect
      .poll(async () =>
        page.evaluate(() =>
          (
            window as unknown as {
              __murmurTestCalls: Array<{
                command: string;
                args: { words?: string[] };
              }>;
            }
          ).__murmurTestCalls
            .filter((call) => call.command === "update_custom_words")
            .map((call) => call.args.words),
        ),
      )
      .toEqual([["PostgreSQL"], []]);
  });

  test("plays bundled tones through system audio without app-specific controls", async ({
    page,
  }) => {
    await expect(
      page.getByRole("button", { name: "Marimba", exact: true }),
    ).toBeVisible();
    await expect(page.getByText("Play sounds on", { exact: true })).toHaveCount(
      0,
    );
    await expect(page.getByText("Volume", { exact: true })).toHaveCount(0);

    await page.getByRole("button", { name: "Marimba", exact: true }).click();
    await page.getByRole("button", { name: "Pop", exact: true }).click();

    await expect(page.getByText("Play sounds on", { exact: true })).toHaveCount(
      0,
    );
    await expect(page.getByText("Volume", { exact: true })).toHaveCount(0);
    await expect
      .poll(async () =>
        page.evaluate(() =>
          (
            window as unknown as {
              __murmurTestCalls: Array<{
                command: string;
                args: { theme?: string };
              }>;
            }
          ).__murmurTestCalls
            .filter((call) => call.command === "change_sound_theme_setting")
            .map((call) => call.args.theme),
        ),
      )
      .toEqual(["pop"]);

    await page.getByRole("button", { name: "Pop", exact: true }).click();
    await page.getByRole("button", { name: "Marimba", exact: true }).click();

    await expect(page.getByText("Play sounds on", { exact: true })).toHaveCount(
      0,
    );
    await expect(page.getByText("Volume", { exact: true })).toHaveCount(0);
    await expect
      .poll(async () =>
        page.evaluate(() =>
          (
            window as unknown as {
              __murmurTestCalls: Array<{
                command: string;
                args: { theme?: string };
              }>;
            }
          ).__murmurTestCalls
            .filter((call) => call.command === "change_sound_theme_setting")
            .map((call) => call.args.theme),
        ),
      )
      .toEqual(["pop", "marimba"]);
  });

  test("shows retained dictation and configured OpenRouter usage on Stats", async ({
    page,
  }) => {
    await page.getByRole("button", { name: "Stats", exact: true }).click();

    const stats = page.locator('section[aria-labelledby="stats-heading"]');
    await expect(stats.getByText("Retained words")).toBeVisible();
    await expect(stats.getByText("1,250", { exact: true })).toBeVisible();
    await expect(stats.getByText("125 wpm", { exact: true })).toBeVisible();
    await expect(stats.getByText("2 min", { exact: true })).toBeVisible();

    const usage = page.locator(
      'section[aria-labelledby="openrouter-usage-heading"]',
    );
    await expect(usage.getByText("Provider cost")).toBeVisible();
    await expect(usage.getByText("$0.041", { exact: true })).toBeVisible();
    await expect(
      usage.getByText("including use outside Murmur", { exact: false }),
    ).toBeVisible();

    await stats.getByRole("button", { name: "Refresh" }).click();
    await expect
      .poll(async () =>
        page.evaluate(
          () =>
            (
              window as unknown as {
                __murmurTestCalls: Array<{ command: string }>;
              }
            ).__murmurTestCalls.filter(
              (call) => call.command === "get_history_stats",
            ).length,
        ),
      )
      .toBeGreaterThanOrEqual(2);
  });
});
