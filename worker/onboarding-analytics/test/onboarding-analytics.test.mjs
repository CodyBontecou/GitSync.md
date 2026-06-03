import assert from "node:assert/strict";
import test from "node:test";

import worker from "../src/index.ts";

const installId = "00000000-0000-4000-8000-000000000001";

class FakeD1Database {
  preparedSql = "";
  statements = [];

  prepare(sql) {
    this.preparedSql = sql;
    return {
      bind: (...values) => ({ values }),
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
