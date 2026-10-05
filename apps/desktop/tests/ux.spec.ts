import { expect, test, type Page } from "@playwright/test";
import { installFixture, sampleHistory, sampleModel } from "./fixtures";

test.use({ permissions: ["clipboard-read", "clipboard-write"] });

async function commandsCalled(page: Page, command: string) {
  return page.evaluate((name) => {
    return window.__murmurTestCalls
      .filter((call) => call.command === name)
      .map((call) => call.args);
  }, command);
}

async function openHistory(page: Page) {
  await page.goto("/");
  await page.getByRole("button", { name: "History", exact: true }).click();
}

test("fresh local setup directs users to Models without a download", async ({
  page,
}, testInfo) => {
  await installFixture(page, {
    settings: {
      onboarding_completed: false,
      bindings: {
        transcribe: {
          id: "transcribe",
          name: "Dictation shortcut",
          description: "",
          default_binding: "ctrl+space",
          current_binding: "ctrl+space",
        },
      },
    },
  });
  await page.goto("/");
  await expect(
    page.getByRole("button", { name: "Use a speech endpoint" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Use a speech endpoint" }).click();
  await expect(
    page.getByRole("heading", { name: "Models", exact: true }),
  ).toBeVisible();
  expect(await commandsCalled(page, "download_model")).toEqual([]);
  await page.screenshot({ path: testInfo.outputPath("first-use-models.png") });
});

test("ready dictation explains the saved hold activation", async ({
  page,
}, testInfo) => {
  await installFixture(page, {
    models: [sampleModel],
    settings: {
      selected_model: sampleModel.id,
      shortcut_activation: "push_to_talk",
      bindings: {
        transcribe: {
          id: "transcribe",
          name: "Dictation shortcut",
          description: "",
          default_binding: "ctrl+space",
          current_binding: "ctrl+space",
        },
      },
    },
  });
  await page.goto("/");
  await expect(
    page.getByText("Hold Ctrl + Space to dictate; release to finish"),
  ).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath("desktop-dictation.png") });
});

test("browsing a catalog card does not download until its named action", async ({
  page,
}) => {
  await installFixture(page, {
    models: [
      {
        ...sampleModel,
        is_downloaded: false,
        source: {
          HuggingFace: { repo_id: "fixture/catalog", revision: "synthetic" },
        },
      },
    ],
  });
  await page.goto("/");
  await page.getByRole("button", { name: "Models", exact: true }).click();
  await page
    .getByRole("button", { name: "Browse 1 models to download" })
    .click();
  await page
    .getByRole("heading", { name: "Whisper Tiny", exact: true })
    .click();
  expect(await commandsCalled(page, "download_model")).toEqual([]);
  await page
    .getByRole("button", { name: "Download Whisper Tiny", exact: true })
    .click();
  await expect
    .poll(() => commandsCalled(page, "download_model"))
    .toHaveLength(1);
});

test("remote Ollama is described as sending text in Dictation and Privacy", async ({
  page,
}) => {
  await installFixture(page, {
    settings: {
      post_process_enabled: true,
      post_process_provider_id: "ollama",
      post_process_providers: [
        {
          id: "ollama",
          label: "Ollama",
          base_url: "https://cleanup.example.invalid/v1",
        },
      ],
    },
  });
  await page.goto("/");
  await expect(page.getByText(/Transcript text is sent to/)).toBeVisible();
  await expect(page.getByText(/cleanup runs locally/)).toHaveCount(0);
  await page.getByRole("button", { name: "Privacy", exact: true }).click();
  await expect(
    page.getByText(/Transcript text and your dictionary words are sent to/),
  ).toBeVisible();
  await expect(page.getByText(/Cleaned up on this computer/)).toHaveCount(0);
});

test("history displays and copies each transcript form", async ({
  page,
}, testInfo) => {
  await installFixture(page, { history: [sampleHistory] });
  await openHistory(page);
  await expect(
    page.getByText(sampleHistory.post_processed_text!, { exact: true }),
  ).toBeVisible();
  await page
    .getByRole("button", { name: "Copy transcription to clipboard" })
    .click();
  await expect
    .poll(() => page.evaluate(() => navigator.clipboard.readText()))
    .toBe(sampleHistory.post_processed_text);
  await page.getByRole("button", { name: "Show raw text" }).click();
  await expect(
    page.getByText(sampleHistory.transcription_text, { exact: true }),
  ).toBeVisible();
  await page
    .getByRole("button", { name: "Copy transcription to clipboard" })
    .click();
  await expect
    .poll(() => page.evaluate(() => navigator.clipboard.readText()))
    .toBe(sampleHistory.transcription_text);
  await page.screenshot({
    path: testInfo.outputPath("history-raw-recovery.png"),
  });
});

test("history cancellation retains the entry and never invokes deletion", async ({
  page,
}) => {
  await installFixture(page, { history: [sampleHistory] });
  await openHistory(page);
  await page.getByRole("button", { name: "Delete entry" }).click();
  await expect
    .poll(() => commandsCalled(page, "plugin:dialog|ask"))
    .toHaveLength(1);
  expect((await commandsCalled(page, "plugin:dialog|ask"))[0]).toMatchObject({
    yesButtonLabel: "Delete",
    noButtonLabel: "Cancel",
  });
  expect(await commandsCalled(page, "delete_history_entry")).toEqual([]);
  await expect(
    page.getByText(sampleHistory.post_processed_text!, { exact: true }),
  ).toBeVisible();
});

test("confirmed deletion removes the entry after success", async ({ page }) => {
  await installFixture(page, { history: [sampleHistory], confirmDelete: true });
  await openHistory(page);
  await page.getByRole("button", { name: "Delete entry" }).click();
  await expect
    .poll(() => commandsCalled(page, "delete_history_entry"))
    .toEqual([{ id: 1 }]);
  await expect(page.getByText("No transcriptions yet")).toBeVisible();
});

test("failed deletion preserves recovery text and reports the error", async ({
  page,
}) => {
  await installFixture(page, {
    history: [sampleHistory],
    confirmDelete: true,
    deleteFailure: true,
  });
  await openHistory(page);
  await page.getByRole("button", { name: "Delete entry" }).click();
  await expect(
    page.getByText("Failed to delete entry. Please try again."),
  ).toBeVisible();
  await expect(
    page.getByText(sampleHistory.post_processed_text!, { exact: true }),
  ).toBeVisible();
});

for (const failure of ["returned", "thrown"] as const) {
  test(`history ${failure} failure offers retry instead of an empty state`, async ({
    page,
  }, testInfo) => {
    await installFixture(page, { historyFailure: failure });
    await openHistory(page);
    await expect(page.getByRole("alert")).toContainText(
      "Could not load history",
    );
    await expect(page.getByText("No transcriptions yet")).toHaveCount(0);
    await page.getByRole("button", { name: "Retry loading" }).click();
    await expect
      .poll(() => commandsCalled(page, "get_history_entries"))
      .toHaveLength(2);
    await page.screenshot({ path: testInfo.outputPath("history-error.png") });
  });
}

test("loading is announced before the successful empty state", async ({
  page,
}) => {
  await installFixture(page, { historyDelayMs: 500 });
  await openHistory(page);
  await expect(page.getByRole("status")).toContainText("Loading");
  await expect(page.getByText("No transcriptions yet")).toBeVisible();
});

test("named switches and retention keyboard dismissal preserve focus", async ({
  page,
}) => {
  await installFixture(page);
  await page.goto("/");
  await expect(
    page.getByRole("checkbox", { name: "Start and stop sounds" }),
  ).toBeAttached();
  await page.getByRole("button", { name: "History", exact: true }).click();
  const retention = page.getByRole("button", {
    name: "Keep recordings and transcripts: Forever",
  });
  await retention.focus();
  await page.keyboard.press("Enter");
  await expect(retention).toHaveAttribute("aria-expanded", "true");
  await page.keyboard.press("Tab");
  await page.keyboard.press("Escape");
  await expect(retention).toBeFocused();
  await expect(retention).toHaveAttribute("aria-expanded", "false");
  await page.keyboard.press("Enter");
  await page.keyboard.press("Tab");
  await page.keyboard.press("Enter");
  await expect(retention).toBeFocused();
});

test("count-based history retention names its numeric limit", async ({
  page,
}) => {
  await installFixture(page, {
    settings: {
      recording_retention_period: "preserve_limit",
      history_limit: 1000,
    },
  });
  await openHistory(page);
  await expect(
    page.getByRole("spinbutton", { name: "Recordings to keep" }),
  ).toHaveValue("1000");
});

test("permission polling failure restores an actionable request button", async ({
  page,
}) => {
  await installFixture(page, {
    settings: { onboarding_completed: false },
    platform: "windows",
    permissionPollingFailure: true,
  });
  await page.goto("/");
  const request = page.getByRole("button", {
    name: /Open.*Settings|Grant.*Microphone/i,
  });
  await request.click();
  await expect(
    page.getByText(/Enable microphone access in system settings/),
  ).toBeVisible();
  await expect(
    page.getByText(/Enable microphone access in system settings/),
  ).toHaveCount(0, { timeout: 6000 });
  await expect(request).toBeVisible({ timeout: 6000 });
  await request.click();
  await expect
    .poll(() => commandsCalled(page, "open_microphone_privacy_settings"))
    .toHaveLength(2);
});

test("denied permission keeps system settings reachable while polling", async ({
  page,
}, testInfo) => {
  await installFixture(page, {
    settings: { onboarding_completed: false },
    platform: "windows",
  });
  await page.goto("/");
  const settingsButton = page.getByRole("button", { name: /Open.*settings/i });
  await settingsButton.click();
  await expect(
    page.getByText(/Enable microphone access in system settings/),
  ).toBeVisible();
  await expect
    .poll(
      async () =>
        (await commandsCalled(page, "get_windows_microphone_permission_status"))
          .length,
    )
    .toBeGreaterThanOrEqual(3);
  await expect(settingsButton).toBeVisible();
  await settingsButton.click();
  await expect
    .poll(() => commandsCalled(page, "open_microphone_privacy_settings"))
    .toHaveLength(2);
  await page.screenshot({
    path: testInfo.outputPath("permission-recovery.png"),
  });
});

test("narrow controls fit and every navigation destination stays reachable", async ({
  page,
}, testInfo) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await installFixture(page, { history: [sampleHistory] });
  await page.goto("/");
  const overflowing = await page
    .locator("button")
    .evaluateAll((buttons) =>
      buttons
        .filter(
          (button) =>
            !button.closest("nav") &&
            button.getBoundingClientRect().right > innerWidth + 1,
        )
        .map((button) => button.textContent),
    );
  expect(overflowing).toEqual([]);
  await page.screenshot({ path: testInfo.outputPath("narrow-dictation.png") });
  await page.getByRole("button", { name: "About", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "About", exact: true, level: 1 }),
  ).toBeVisible();
  await page.getByRole("button", { name: "History", exact: true }).click();
  await expect(
    page.getByText(sampleHistory.post_processed_text!, { exact: true }),
  ).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath("narrow-history.png") });
});
