# Post-fix verification

Android API 36 emulator; all data synthetic.

- `threebutton-{false,true,reverse}.txt`: portrait / landscape / reverse landscape, 13 passing cases each.
- `gesture-{false,true,reverse}.txt`: same orientations, 13 passing cases each.
- `system-xl.txt`: three-button portrait at system font scale 1.6, 13 passing cases.
- Each case requests light/normal and dark/160% component text: 91 passing instances, 182 unclipped action-geometry checks. Safe bounds include navigation/status bars and camera cutouts.
- `widget-before.txt`: new production-picker-body regression failed before its inset fix (last row extends 42px beneath three-button navigation).
- `consent-and-spacing-tests.txt`: device TestRunner completion for the focused spacing test, both discard consent tests (including 25 locales at large text with working files/index preserved), and three fake-billing paywall/gate tests: 6 tests, 0 failed. The shell listener timed out while the 25-locale test continued; this records the device's eventual completion.
- `ssh-and-merge-tests.txt`: final SSH suite (including 25-locale changed-key review/cancel checks) and isolated real-fixture merge flow: 7 tests passed. SSH test storage is scoped to synthetic test pins; Git remotes are local fixtures.

Screenshots show the fixed changed-key review, discard review and widget picker in dark/160% text with three-button navigation, and fixed vault check in reverse landscape. The latter was visually checked to ensure both the final clone button and header fit within the camera-cutout-safe viewport.

Debug app and instrumentation APK builds succeeded. `git diff --check` passed. Original navigation, font scale and rotation settings were restored. No release build, store upload or Git commit was made.
