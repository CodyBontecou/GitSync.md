# FCM Workers runtime correction

This preserves the correction discovered during the approved 2026-11-02 physical Pixel webhook check. The production relay source used for this check is `/tmp/syncmd-existing-installation-relay`; the sibling iOS checkout is read-only in this session.

`fcm-runtime.patch` contains the sender correction. Apply it from the relay root; copy `fcm.test.ts` to `src/fcm.test.ts`, `webhook.test.ts` to `src/webhook.test.ts`, and `check-fcm-runtime.mjs` to `tools/check-fcm-runtime.mjs`. The runtime script expects the relay working directory and its installed esbuild and Miniflare dependencies.

The default Workers `fetch` must be called through a wrapper to preserve its global receiver. Workers supports `manual` redirects; OAuth rejects any non-success response, and FCM rejects every redirect before interpreting the response. Credentials are never forwarded to the redirect target. The validator accepts the real `repo:` plus 59 hex digits emitted by the shared branch collapse-ID generator, as well as plain 64-character hex keys. Both formats have an exact 64-character bound; malformed keys stop before authorization. The unit and workerd checks use the actual generator.

Verification: 106 relay unit tests; five workerd checks (two existing installation lookup checks, FCM success, OAuth redirect rejection, FCM redirect rejection); installed and current Workers type checks; deployment dry run. The runtime checks generate an ephemeral RSA key and mock Google endpoints; they use no production credential. The pre-fix runtime test failed with an illegal receiver, then with unsupported redirect mode after correcting the receiver alone.

Live delivery qualification and deployment receipts are recorded in FEATURE_PARITY.md. This directory itself is source evidence, not a claim that the entire Android parity task is complete.


`deployed-relay-source.zip` preserves every source/test/tool/config/dependency-manifest input in the final tested/deployed receipt. It excludes credentials, Worker local state and node_modules. Extract into a separate directory to inspect or restore the tested relay; its SHA-256 is recorded in `evidence/syncmd-webhook-fcm-collapse-proof.json`. The sibling checkout was not edited in this session. The deployed runtime fix is version `1eb9d62a-4beb-4440-9f59-8a9ca1d345a6`.

The physical live check passed with the app backgrounded and periodic sync off. Cleanup is verified in `evidence/registry-after-cleanup.json` and `evidence/syncmd-live-webhook-test-plan.json`. Screenshots cover notification, tap result, updated repository and retained connection. The saved Argent walkthrough is observational and has no qualified portable replay pass; it includes external cloud actions and documented selector/system-UI fallbacks.
