import assert from "node:assert/strict";
import { DatabaseSync } from "node:sqlite";
import test from "node:test";

import worker from "../src/index.ts";

const installId = "00000000-0000-4000-8000-000000000001";

class FakeD1Database {
  preparedSql = "";
  statements = [];
  runValues = [];

  prepare(sql) {
    this.preparedSql = sql;
    return {
      bind: (...values) => ({
        values,
        run: async () => {
          this.runValues.push(values);
          return { success: true };
        },
      }),
    };
  }

  async batch(statements) {
    this.statements = statements;
    return statements.map(() => ({ success: true }));
  }
}

async function postEvents(body, env = {}) {
  const db = new FakeD1Database();
  const request = new Request("https://sync-md-onboarding-analytics.example/v1/events", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body),
  });

  const response = await worker.fetch(request, { DB: db, ...env });
  const json = await response.json();
  return { db, response, json };
}

async function deleteEvents(headers = {}, env = {}) {
  const db = new FakeD1Database();
  const request = new Request("https://sync-md-onboarding-analytics.example/v1/installations/current", {
    method: "DELETE",
    headers,
  });
  const response = await worker.fetch(request, { DB: db, ...env });
  return { db, response, json: response.status === 204 ? undefined : await response.json() };
}

function baseProperties(extra = {}) {
  return {
    appVersion: "1.7.0",
    buildNumber: "2026053001",
    platform: "ios",
    ...extra,
  };
}

test("accepts onboarding events and stores onboardingStep in payload_json", async () => {
  const events = [
    ["00000000-0000-4000-8000-000000000101", "sync_onboarding_started", "welcome"],
    ["00000000-0000-4000-8000-000000000102", "sync_onboarding_step_viewed", "account_choice"],
    ["00000000-0000-4000-8000-000000000103", "sync_onboarding_auth_completed", "github_sign_in"],
    ["00000000-0000-4000-8000-000000000104", "sync_onboarding_save_location_selected", "save_location"],
    ["00000000-0000-4000-8000-000000000105", "sync_onboarding_completed", "ready"],
  ].map(([eventId, eventName, onboardingStep]) => ({
    eventId,
    eventName,
    properties: baseProperties({
      onboardingStep,
      authMethod: eventName === "sync_onboarding_auth_completed" ? "github_oauth" : undefined,
      authOutcome: eventName === "sync_onboarding_auth_completed" ? "succeeded" : undefined,
      saveLocationPreference: eventName === "sync_onboarding_save_location_selected" ? "custom_folder" : undefined,
    }),
  }));

  const { db, response, json } = await postEvents({ installId, events });

  assert.equal(response.status, 200);
  assert.deepEqual(json, { ok: true, accepted: events.length });
  assert.match(db.preparedSql, /onboarding_step/);
  assert.equal(db.statements.length, events.length);

  const payloadJson = db.statements[3].values.at(-1);
  assert.equal(JSON.parse(payloadJson).properties.onboardingStep, "save_location");
});

test("rejects onboardingStep values outside the coarse allowlist", async () => {
  const { response, json } = await postEvents({
    installId,
    eventId: "00000000-0000-4000-8000-000000000201",
    eventName: "sync_onboarding_step_viewed",
    properties: baseProperties({ onboardingStep: "folder:/Users/cody/Documents" }),
  });

  assert.equal(response.status, 400);
  assert.equal(json.error, "unknown_property_value:onboardingStep");
});

test("rejects repo URLs and other unknown properties", async () => {
  const { response, json } = await postEvents({
    installId,
    eventId: "00000000-0000-4000-8000-000000000301",
    eventName: "sync_onboarding_completed",
    properties: baseProperties({ repoURL: "https://github.com/cody/private.git" }),
  });

  assert.equal(response.status, 400);
  assert.equal(json.error, "unknown_property:repoURL");
});

test("installation deletion is token-authenticated and parameterized", async () => {
  const { db, response, json } = await deleteEvents({
    authorization: "Bearer delete-token",
    "x-installation-id": installId.toUpperCase(),
  }, { DELETION_TOKEN: "delete-token" });

  assert.equal(response.status, 204);
  assert.equal(json, undefined);
  assert.match(db.preparedSql, /^DELETE FROM onboarding_events WHERE install_id = \?$/);
  assert.deepEqual(db.runValues, [[installId]]);
});

test("installation deletion fails closed without configured token", async () => {
  const { db, response, json } = await deleteEvents({ "x-installation-id": installId });
  assert.equal(response.status, 503);
  assert.equal(json.error, "deletion_unavailable");
  assert.deepEqual(db.runValues, []);
});

test("installation deletion rejects unauthorized and malformed IDs", async () => {
  const unauthorized = await deleteEvents({
    authorization: "Bearer wrong",
    "x-installation-id": installId,
  }, { DELETION_TOKEN: "delete-token" });
  assert.equal(unauthorized.response.status, 401);
  assert.equal(unauthorized.json.error, "unauthorized");

  const malformed = await deleteEvents({
    authorization: "Bearer delete-token",
    "x-installation-id": "../../private",
  }, { DELETION_TOKEN: "delete-token" });
  assert.equal(malformed.response.status, 400);
  assert.equal(malformed.json.error, "invalid_install_id");
  assert.deepEqual(malformed.db.runValues, []);
});

test("scheduled cleanup deletes only retention-expired rows", async () => {
  const db = new FakeD1Database();
  const promises = [];
  worker.scheduled({}, { DB: db, RETENTION_DAYS: "45" }, { waitUntil: (promise) => promises.push(promise) });
  await Promise.all(promises);

  assert.match(db.preparedSql, /^\s*DELETE FROM onboarding_events WHERE datetime\(received_at\) < datetime\('now', \?\)\s*$/);
  assert.deepEqual(db.runValues, [["-45 days"]]);
});

test("cleanup normalizes RFC3339 timestamps at the exact boundary", () => {
  const db = new DatabaseSync(":memory:");
  db.exec("CREATE TABLE onboarding_events (id TEXT PRIMARY KEY, received_at TEXT NOT NULL)");
  const insert = db.prepare("INSERT INTO onboarding_events (id, received_at) VALUES (?, ?)");
  insert.run("expired", "2026-05-15T03:59:59.999Z");
  insert.run("boundary", "2026-05-15T04:00:00.000Z");
  insert.run("newer", "2026-05-15T04:00:00.001Z");

  db.prepare(
    "DELETE FROM onboarding_events WHERE datetime(received_at) < datetime(?, ?)",
  ).run("2026-08-13T04:00:00.000Z", "-90 days");

  assert.deepEqual(
    db.prepare("SELECT id FROM onboarding_events ORDER BY id").all().map((row) => row.id),
    ["boundary", "newer"],
  );
  db.close();
});

test("scheduled cleanup defaults invalid retention to 90 days", async () => {
  const db = new FakeD1Database();
  const promises = [];
  worker.scheduled({}, { DB: db, RETENTION_DAYS: "0" }, { waitUntil: (promise) => promises.push(promise) });
  await Promise.all(promises);

  assert.deepEqual(db.runValues, [["-90 days"]]);
});

test("rejects removed paywall and purchase analytics", async () => {
  const { response, json } = await postEvents({
    installId,
    eventId: "00000000-0000-4000-8000-000000000401",
    eventName: "sync_purchase_finished",
    properties: baseProperties(),
  });

  assert.equal(response.status, 400);
  assert.equal(json.error, "unknown_event_name");
});
