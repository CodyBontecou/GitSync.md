# iOS exploratory QA — 8 November 2026

Used Argent on the existing installed app on `agent-capture-iPhone`, iOS 27.0, UDID `15EC4EBE-5744-4B13-BABA-4207C4F43E51`. This pass explored Debug log and notification-permission recovery, which were not covered in the preceding conflict/editor passes. No reproducible bug was confirmed, so no application source changes or rebuild were needed.

## Checks

- Opened Debug log from signed-out Settings. It showed 151 entries and 45 errors in the last 24 hours.
- Selected Background: visible rows were background registrations, with BG badges and yesterday timestamps.
- Opened Clear All Logs while filtered, then cancelled. All 151 entries remained, and the Background filter remained selected.
- Opened Share Logs from the filtered view, then closed the native share sheet without choosing a destination. Returned to the same filter and count. [After cancellation](evidence/ios-settings-qa-20261108/logs-after-share-cancel.png).
- Enabled push notifications while signed out. The app explained that GitHub must be connected and displayed the native notification permission prompt.
- Denied the prompt. The switch returned to off and the screen displayed “Notifications were denied in Settings.” [Denied state](evidence/ios-settings-qa-20261108/notification-denial.png).
- Tried enabling again after denial. The switch remained off with the denial explanation; no repeated system prompt or crash appeared.
- Returned to Settings: push notifications and Background Sync were off; author remained Not set; appearance remained System. [Final settings](evidence/ios-settings-qa-20261108/settings-final.png). Closed Settings to the signed-out empty home.

## Scope and side effects

These were live, one-off Argent checks using accessibility-discovered targets, not automated regression replays. No log was cleared, shared, or copied; no repository, account, or source file was changed. The capture simulator's notification authorization is now denied after testing the real permission prompt. Other devices were untouched. Authenticated notification delivery and background execution were not tested. This pass exercised cancellation and permission state edges; it did not exercise additional free-text input fields.
