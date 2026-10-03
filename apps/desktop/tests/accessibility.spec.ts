import { test, expect } from "@playwright/test";
import { installFixture, sampleHistory, sampleModel } from "./fixtures";
import french from "../src/i18n/locales/fr/translation.json" with { type: "json" };

test.use({ permissions: ["clipboard-read", "clipboard-write"] });

test("content navigation focuses the named main heading and preserves nav focus", async ({
  page,
}) => {
  await installFixture(page, {
    models: [sampleModel],
    settings: { selected_model: sampleModel.id },
  });
  await page.goto("/");
  await expect(page.getByRole("main")).toHaveAccessibleName("Dictation");
  await page
    .getByRole("button", { name: "Change models", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { level: 1, name: "Models", exact: true }),
  ).toBeFocused();
  const privacy = page.getByRole("button", { name: "Privacy", exact: true });
  await privacy.click();
  await expect(privacy).toBeFocused();
  await expect(
    page.getByRole("button", { name: "Change: Your transcripts", exact: true }),
  ).toBeVisible();
});

test("capture status failure offers retry and saved hidden indicator can be enabled", async ({
  page,
}) => {
  await installFixture(page, {
    recordingFailure: true,
    settings: { overlay_style: "none" },
  });
  await page.goto("/");
  await expect(
    page.getByText("Microphone status unavailable", { exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Retry", exact: true }).click();
  const indicator = page.getByRole("checkbox", {
    name: "Show recording indicator",
    exact: true,
  });
  await expect(indicator).not.toBeChecked();
  await indicator.check();
  await expect
    .poll(() =>
      page.evaluate(() =>
        window.__murmurTestCalls
          .filter((x) => x.command === "change_overlay_style_setting")
          .map((x) => x.args),
      ),
    )
    .toContainEqual({ style: "minimal" });
});

test("credential failure offers retry and never claims zero keys", async ({
  page,
}) => {
  await installFixture(page, {
    credentialFailure: true,
    settings: {
      stt_source: "endpoint",
      stt_base_url: "https://speech.example.test/v1/audio/transcriptions",
    },
  });
  await page.goto("/");
  await page.getByRole("button", { name: "Privacy", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText(
    "Credential-store status is unavailable",
  );
  await expect(page.getByText(/0 saved in your system/)).toHaveCount(0);
  await page.getByRole("button", { name: "Retry", exact: true }).click();
});

test("history cancellation restores focus and playback failure keeps recovery", async ({
  page,
}) => {
  await installFixture(page, { history: [sampleHistory] });
  await page.goto("/");
  await page.getByRole("button", { name: "History", exact: true }).click();
  const remove = page.getByRole("button", {
    name: "Delete entry",
    exact: true,
  });
  await remove.click();
  await expect(remove).toBeFocused();
  await page
    .getByRole("button", {
      name: "Copy transcription to clipboard",
      exact: true,
    })
    .click();
  await expect(page.getByRole("status")).toContainText(
    "Copied displayed transcript",
  );
  await expect(
    page.getByRole("slider", { name: "Recording playback position" }),
  ).toHaveAttribute("aria-valuetext", "0:00 of 0:00");
  await page.getByRole("button", { name: "Play", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText(
    "Recording playback is unavailable",
  );
  await expect(
    page.getByText(sampleHistory.post_processed_text!, { exact: true }),
  ).toBeVisible();
});

test("default reporting sends nothing through screen and error paths", async ({
  page,
}) => {
  const requests: string[] = [];
  page.on("request", (request) => {
    if (request.url().includes("/v1/events")) requests.push(request.url());
  });
  await installFixture(page, { historyFailure: "thrown" });
  await page.goto("/");
  await page.getByRole("button", { name: "Privacy", exact: true }).click();
  await expect(
    page.getByRole("checkbox", { name: "Share anonymous usage and errors" }),
  ).toBeDisabled();
  await expect(
    page.getByText(
      "Reporting is not configured in this build. Nothing is sent.",
    ),
  ).toBeVisible();
  await page.getByRole("button", { name: "History", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("Could not load history");
  expect(requests).toEqual([]);
});

test("320px enlarged text and reduced motion preserve privacy controls", async ({
  page,
}, testInfo) => {
  await page.setViewportSize({ width: 320, height: 844 });
  await page.emulateMedia({ reducedMotion: "reduce" });
  await installFixture(page, {
    settings: {
      stt_source: "endpoint",
      stt_base_url:
        "https://a-very-long-synthetic-recognition-host.example.test/v1/audio/transcriptions",
    },
  });
  await page.goto("/");
  await page.getByRole("button", { name: "Privacy", exact: true }).click();
  await page.evaluate(() => {
    document.documentElement.style.fontSize = "30px";
  });
  await expect(page.getByRole("main")).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Change: Your voice", exact: true }),
  ).toBeVisible();
  const fit = await page.evaluate(() => {
    const main = document.querySelector("main");
    return main ? main.scrollWidth <= main.clientWidth + 1 : false;
  });
  expect(fit).toBe(true);
  await page.screenshot({
    path: testInfo.outputPath("privacy-320-enlarged.png"),
  });
  await page.evaluate(() => {
    const probe = document.createElement("div");
    probe.className = "animate-pulse";
    document.body.append(probe);
    const reduced = getComputedStyle(probe).animationName;
    probe.remove();
    if (reduced !== "none") throw Error("motion not reduced");
  });
});

test("shortcut editing opens by keyboard, cancels with Escape and restores focus", async ({
  page,
}) => {
  await installFixture(page, {
    settings: {
      bindings: {
        transcribe: {
          id: "transcribe",
          name: "Dictation",
          description: "Dictation shortcut",
          current_binding: "ctrl+space",
          default_binding: "ctrl+space",
        },
      },
    },
  });
  await page.goto("/");
  const trigger = page.getByRole("button", {
    name: /^Change Dictation shortcut:/,
  });
  await trigger.focus();
  await page.keyboard.press("Enter");
  await expect
    .poll(() =>
      page.evaluate(() =>
        window.__murmurTestCalls.some(
          (x) => x.command === "suspend_all_bindings",
        ),
      ),
    )
    .toBe(true);
  await expect(page.getByText("Press keys...")).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(trigger).toBeFocused();
  expect(
    await page.evaluate(() =>
      window.__murmurTestCalls.filter((x) => x.command === "change_binding"),
    ),
  ).toEqual([]);
});

test("320px doubled text keeps microphone and sound actions within the main panel", async ({
  page,
}) => {
  await installFixture(page);
  await page.setViewportSize({ width: 320, height: 844 });
  await page.goto("/");
  await page.addStyleTag({ content: "html { font-size: 200% !important; }" });
  const main = page.getByRole("main");
  await expect(main).toBeVisible();
  expect(
    await main.evaluate((el) => el.scrollWidth <= el.clientWidth + 1),
  ).toBe(true);
  for (const name of [
    "Reset microphone to default",
    "Preview sound theme: start then stop",
  ]) {
    const control = page.getByRole("button", { name });
    await control.scrollIntoViewIfNeeded();
    const rect = await control.boundingBox();
    expect(rect).not.toBeNull();
    expect(rect!.x + rect!.width).toBeLessThanOrEqual(320);
  }
});

test("native shortcut capture waits for activation release and Escape never saves a binding", async ({
  page,
}) => {
  await installFixture(page, {
    settings: {
      keyboard_implementation: "handy_keys",
      bindings: {
        transcribe: {
          id: "transcribe",
          name: "Dictation",
          description: "",
          current_binding: "ctrl+space",
          default_binding: "ctrl+space",
        },
      },
    },
  });
  await page.goto("/");
  const trigger = page.getByRole("button", {
    name: /^Change Dictation shortcut:/,
  });
  await trigger.focus();
  await page.keyboard.down("Enter");
  expect(
    await page.evaluate(() =>
      window.__murmurTestCalls.filter(
        (x) => x.command === "start_handy_keys_recording",
      ),
    ),
  ).toEqual([]);
  await page.evaluate(() =>
    window.__murmurEmitEvent("handy-keys-event", {
      modifiers: [],
      key: "enter",
      is_key_down: false,
      hotkey_string: "enter",
    }),
  );
  await page.keyboard.up("Enter");
  await expect(page.getByText("Press keys...")).toBeVisible();
  await expect
    .poll(() =>
      page.evaluate(() =>
        window.__murmurTestCalls.some(
          (x) =>
            x.command === "plugin:event|listen" &&
            x.args.event === "handy-keys-event",
        ),
      ),
    )
    .toBe(true);
  await page.evaluate(() =>
    window.__murmurEmitEvent("handy-keys-event", {
      modifiers: [],
      key: "escape",
      is_key_down: true,
      hotkey_string: "escape",
    }),
  );
  await expect(trigger).toBeFocused();
  expect(
    await page.evaluate(() =>
      window.__murmurTestCalls.filter((x) => x.command === "change_binding"),
    ),
  ).toEqual([]);
  expect(
    await page.evaluate(() =>
      window.__murmurTestCalls.some(
        (x) => x.command === "stop_handy_keys_recording",
      ),
    ),
  ).toBe(true);
});

test("permission actions retain the visible translated action and permission name", async ({
  page,
}) => {
  await installFixture(page, {
    platform: "windows",
    settings: { onboarding_completed: false, app_language: "fr" },
  });
  await page.goto("/");
  await expect(
    page.getByRole("button", {
      name: `${french.accessibility.openSettings} ${french.onboarding.permissions.microphone.title}`,
    }),
  ).toBeVisible();
});
