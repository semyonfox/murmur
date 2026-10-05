import { expect, test } from "@playwright/test";
import { installFixture } from "./fixtures";

test.describe("Murmur settings", () => {
  test.beforeEach(async ({ page }) => {
    await installFixture(page);

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
      page.getByRole("button", { name: /: Marimba$/, exact: false }),
    ).toBeVisible();
    await expect(page.getByText("Play sounds on", { exact: true })).toHaveCount(
      0,
    );
    await expect(page.getByText("Volume", { exact: true })).toHaveCount(0);

    await page
      .getByRole("button", { name: /: Marimba$/, exact: false })
      .click();
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

    await page.getByRole("button", { name: /: Pop$/ }).click();
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
    const position = stats.locator('[aria-live="polite"]');
    await expect(position).toHaveText("Stat 1 of 4");
    await expect(stats.locator('[data-stat-dot="0"]')).toHaveAttribute(
      "data-active",
      "true",
    );
    await expect(stats.getByRole("button", { name: "Next stat" })).toHaveCount(
      0,
    );
    const carousel = stats.getByRole("region", {
      name: "Dictation stat cards",
    });
    const bounds = await carousel.boundingBox();
    expect(bounds).not.toBeNull();
    if (!bounds) return;
    await page.mouse.move(
      bounds.x + bounds.width * 0.8,
      bounds.y + bounds.height / 2,
    );
    await page.mouse.down();
    await page.mouse.move(
      bounds.x + bounds.width * 0.1,
      bounds.y + bounds.height / 2,
      { steps: 10 },
    );
    await page.mouse.up();
    await expect(position).toHaveText("Stat 2 of 4");
    await expect(stats.locator('[data-stat-dot="1"]')).toHaveAttribute(
      "data-active",
      "true",
    );
    await carousel.focus();
    await page.keyboard.press("ArrowRight");
    await expect(position).toHaveText("Stat 3 of 4");
    await page.keyboard.press("ArrowRight");
    await expect(position).toHaveText("Stat 4 of 4");
    await page.keyboard.press("ArrowLeft");
    await expect(position).toHaveText("Stat 3 of 4");
    await page.keyboard.press("ArrowLeft");
    await page.keyboard.press("ArrowLeft");
    await expect(position).toHaveText("Stat 1 of 4");

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

test("first launch offers an endpoint after permissions", async ({ page }) => {
  await installFixture(page, {
    settings: { onboarding_completed: false },
  });

  await page.setViewportSize({ width: 680, height: 570 });
  await page.goto("/");
  await expect(
    page.getByRole("button", { name: "Use a speech endpoint" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Use a speech endpoint" }).click();
  await expect(
    page.getByRole("heading", { name: "Models", exact: true }),
  ).toBeVisible();
  await expect
    .poll(() =>
      page.evaluate(() =>
        window.__murmurTestCalls.some(
          (call) => call.command === "complete_onboarding",
        ),
      ),
    )
    .toBe(true);
});
