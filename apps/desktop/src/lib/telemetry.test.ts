import assert from "node:assert/strict";
import { test } from "node:test";
import {
  createTelemetry,
  privacyAllowsTelemetry,
  validTelemetryEndpoint,
} from "./telemetry.ts";

const endpoint = "https://stats.example.test/v1/events";
const flush = async () => {
  await new Promise<void>((resolve) => setImmediate(resolve));
};

test("default-off and missing configuration never read privacy or dispatch", () => {
  for (const config of [
    { configured: false, endpoint },
    { configured: true, endpoint: "" },
  ]) {
    const client = createTelemetry({
      ...config,
      allowed: () => {
        throw Error("private fixture");
      },
      transport: () => {
        throw Error("must not dispatch");
      },
    });
    assert.doesNotThrow(() => {
      client.count("screen_view", "settings");
      client.error("storage_failed", "settings");
    });
  }
});

test("endpoint boundary excludes credentials, query, fragments and insecure URLs", () => {
  assert.equal(validTelemetryEndpoint(endpoint), true);
  for (const value of [
    "http://stats.example/v1/events",
    "https://secret@stats.example/v1/events",
    endpoint + "?private=1",
    endpoint + "#private",
    "https://stats.example/other",
  ])
    assert.equal(validTelemetryEndpoint(value), false);
});

test("opt-out and unreadable privacy each suppress count and error", () => {
  for (const allowed of [
    () => false,
    () => {
      throw Error("unreadable preference");
    },
  ]) {
    let requests = 0;
    const client = createTelemetry({
      configured: true,
      endpoint,
      allowed,
      transport: async () => {
        requests++;
        return new Response();
      },
    });
    client.count("app_open", "app");
    client.error("storage_failed", "settings");
    assert.equal(requests, 0);
  }
});

test("GPC/DNT including throwing getters fail closed", () => {
  const original = Object.getOwnPropertyDescriptor(globalThis, "navigator");
  try {
    for (const value of [
      { doNotTrack: "1" },
      { doNotTrack: "yes" },
      { globalPrivacyControl: true },
      {
        get doNotTrack() {
          throw Error("unreadable");
        },
      },
      {
        get globalPrivacyControl() {
          throw Error("unreadable");
        },
      },
    ]) {
      Object.defineProperty(globalThis, "navigator", {
        configurable: true,
        value,
      });
      assert.equal(privacyAllowsTelemetry(), false);
    }
    Object.defineProperty(globalThis, "navigator", {
      configurable: true,
      value: { doNotTrack: "0" },
    });
    assert.equal(privacyAllowsTelemetry(), true);
  } finally {
    if (original) Object.defineProperty(globalThis, "navigator", original);
    else Reflect.deleteProperty(globalThis, "navigator");
  }
});

test("payload is exactly six fixed fields and unknown routes never leak", async () => {
  const requests: RequestInit[] = [];
  const client = createTelemetry({
    configured: true,
    endpoint,
    allowed: () => true,
    transport: async (_, options) => {
      requests.push(options ?? {});
      return new Response();
    },
  });
  Reflect.apply(client.count, null, [
    "screen_view",
    "/secret/transcript-42?token=private",
  ]);
  await flush();
  const request = requests[0];
  assert.equal(request.credentials, "omit");
  assert.equal(request.referrerPolicy, "no-referrer");
  assert.equal(request.redirect, "error");
  assert.equal(typeof request.body, "string");
  const body = JSON.parse(String(request.body));
  assert.deepEqual(body, {
    version: 1,
    app: "murmur",
    kind: "count",
    name: "screen_view",
    surface: "desktop",
    route: "app",
  });
  Reflect.apply(client.error, null, ["private exception text", "settings"]);
  assert.equal(requests.length, 1);
});

test("synchronous and asynchronous transport failures are harmless and recover", async () => {
  for (const sync of [true, false]) {
    let requests = 0;
    const client = createTelemetry({
      configured: true,
      endpoint,
      allowed: () => true,
      transport: () => {
        requests++;
        if (sync) throw Error("private fixture");
        return Promise.reject(Error("private fixture"));
      },
    });
    assert.doesNotThrow(() => client.count("app_open", "app"));
    await flush();
    client.count("screen_view", "settings");
    await flush();
    assert.equal(requests, 2);
  }
});

test("pending transport excludes overlap; timeout aborts its own request", async () => {
  let timeout: (() => void) | undefined;
  let requests = 0;
  const client = createTelemetry({
    configured: true,
    endpoint,
    allowed: () => true,
    schedule: (callback) => {
      timeout = callback;
      return () => {};
    },
    transport: (_, options) => {
      requests++;
      return new Promise<Response>((_, reject) =>
        options?.signal?.addEventListener("abort", () =>
          reject(Error("aborted")),
        ),
      );
    },
  });
  client.count("app_open", "app");
  client.count("screen_view", "settings");
  assert.equal(requests, 1);
  timeout?.();
  await flush();
  client.count("screen_view", "settings");
  assert.equal(requests, 2);
  client.stop();
  await flush();
});

test("throwing timeout/controller setup dispatches nothing and does not strand ownership", async () => {
  for (const setup of ["schedule", "controller"]) {
    let fail = true;
    let requests = 0;
    const client = createTelemetry({
      configured: true,
      endpoint,
      allowed: () => true,
      schedule: () => {
        if (fail && setup === "schedule") throw Error("timer unavailable");
        return () => {};
      },
      controller: () => {
        if (fail && setup === "controller") throw Error("abort unavailable");
        return new AbortController();
      },
      transport: async () => {
        requests++;
        return new Response();
      },
    });
    client.count("app_open", "app");
    assert.equal(requests, 0);
    fail = false;
    client.count("screen_view", "settings");
    await flush();
    assert.equal(requests, 1);
  }
});

test("attempt budget is 20 per minute and 200 for the client lifetime", async () => {
  let now = 60_000;
  let requests = 0;
  const client = createTelemetry({
    configured: true,
    endpoint,
    allowed: () => true,
    now: () => now,
    transport: async () => {
      requests++;
      return new Response();
    },
  });
  for (let minute = 0; minute < 12; minute++) {
    for (let i = 0; i < 25; i++) {
      client.count("screen_view", "settings");
      await flush();
    }
    now += 60_000;
  }
  assert.equal(requests, 200);
});

test("fixed errors are deduplicated for one minute and permission changes are read per send", async () => {
  let allowed = true;
  let now = 60_000;
  let requests = 0;
  const client = createTelemetry({
    configured: true,
    endpoint,
    allowed: () => allowed,
    now: () => now,
    transport: async () => {
      requests++;
      return new Response();
    },
  });
  client.error("storage_failed", "settings");
  await flush();
  client.error("storage_failed", "settings");
  assert.equal(requests, 1);
  now += 60_000;
  allowed = false;
  client.error("storage_failed", "settings");
  assert.equal(requests, 1);
  allowed = true;
  client.error("storage_failed", "settings");
  await flush();
  assert.equal(requests, 2);
});
