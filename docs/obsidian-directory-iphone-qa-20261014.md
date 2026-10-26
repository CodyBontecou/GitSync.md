# Community-directory installation: physical iPhone QA

Date: 14 October 2026, approximately 16:41 Europe/Lisbon.

Status: PASS for live community-directory installation, automatic connection, Obsidian Git pull, and Obsidian Git commit/push on the physical iPhone. A separate clean repeat passed and its shortform video is complete.

Device: Cody's physical iPhone 17 Pro, iOS 27.0.1. Obsidian 1.14.5. GitSync.md has the development companion handoff implementation; this is not verification of the pending public 2.8.0 release.

## Results

1. Created private disposable repository `CodyBontecou/gitsync-directory-qa-20261014`. Its initial commit `d49d3d0` contains only Welcome.md and .gitignore, with the entire .obsidian directory ignored. No plugins or credentials were seeded.
2. Cloned it using GitSync.md's existing GitHub account authentication into On My iPhone → Obsidian. Opened the new vault in Obsidian and verified its sidebar showed Welcome and one file, zero folders.
3. Enabled community plugins. Installed Git 2.41.1 by Vinzent through Settings → Community plugins → Browse and enabled it. Its page displayed Options and Disable after enabling.
4. Searching for GitSync returned only GitSync Portal by Kai Liu, a different plugin. Opening `obsidian://show-plugin?id=gitsync-connect` from a dismissed browser opened a search for `gitsync connect`, with zero plugins and “No results found.”
5. Independently downloaded Obsidian's public registry from https://raw.githubusercontent.com/obsidianmd/obsidian-releases/master/community-plugins.json. It contained 8,911 entries, matching the phone. No entry has id `gitsync-connect`; the only GitSync name match is `gitsync-portal`.
6. The public web listing at https://community.obsidian.md/plugins/gitsync-connect exists and advertises 0.1.2, passing review, and the same Add to Obsidian URI. Web-listing availability has not established installability in this app's registry.

The initial attempt was blocked at directory installation; the resumed live test below passed independently of the previous manual-install test documented in obsidian-iphone-qa-20261014.md.

## Resumed live test: passed, approximately 19:04–19:07 Lisbon

- Installed GitSync.md Connect 0.1.2 from Obsidian's built-in community browser. The listing showed version 0.1.2, then INSTALLED and Enable. No manual file copying or bundled plugin was used.
- Tapped Enable. The phone switched to GitSync.md, and the returned Obsidian accessibility tree contained the exact notice “Obsidian Git is connected to GitSync.md.” No token, account login or author fields were typed in Obsidian.
- Created remote-only FromGitHub.md after the clone, at commit `00714e2d037558eda144412e1c40e85d81c24727`. Ran `Git: Pull` in Obsidian's command palette. Opened FromGitHub and verified `gitsync-directory-pull-20261014` and “Created remotely after the iPhone clone.”
- Created FromiPhone.md through Obsidian's UI with exact body `gitsync-directory-push-20261014` (no trailing newline). Ran `Git: Commit-and-sync with specific message`, selecting message `QA: verify community installed companion push`. Obsidian reported “Committed 1 file.”
- GitHub independently returned commit `f594f36f60bc78478ee0d478eee00d8f341050e8`, parent `00714e2d037558eda144412e1c40e85d81c24727`, with only FromiPhone.md changed, correct author name/email, and exact expected body (blob `64a43bb39074d11d06a2abfcdc69a81248933d61`).

Evidence: [directory-installed companion](evidence/obsidian-directory-iphone-20261014/companion-installed.png), [pulled note](evidence/obsidian-directory-iphone-20261014/pulled-note.png), [phone commit confirmation](evidence/obsidian-directory-iphone-20261014/phone-commit.png).

The automation runner initially timed out enabling automation mode (Xcode exit 65). Scoped device-service cleanup and relaunch recovered it. An unrelated app briefly foregrounded; Obsidian was relaunched and live verification continued. This is a live QA pass, not an uninterrupted automated replay. The new three-step setup flow also captured an incorrect underlying `New folder` selector for the community-settings tap, so it must not be replayed as a passing test. Physical iPhone capture is unsupported by Argent; the subsequent native USB capture solution and clean repeat are documented below.

## Recorded clean repeat and shortform export

After the live QA pass, created the separate private demo repository `CodyBontecou/gitsync-demo-vault-20261014`, initial commit `0ebecbb`. Captured the physical iPhone screen over USB with Apple's AVFoundation/CoreMediaIO API. No camera, microphone, or Mac desktop was recorded. The native recording path required an H.264 output configuration and a short capture-session warmup; the initial attempt produced no movie and was not used.

The clean repeat cloned the demo repository in GitSync.md using the existing sign-in, opened the fresh vault, installed and enabled Git 2.41.1 and GitSync.md Connect 0.1.2 from the community browser, and observed the automatic connection notice again. GitHub-only FromGitHub.md was added at `7ae43da88f3db98ff685cf34f738082d317dd81e`, pulled using Obsidian Git, and opened on the phone. Created FromiPhone.md with exact body `Written on my iPhone. Pushed to GitHub.` and committed/pushed with message `Sync from iPhone`. GitHub independently confirmed commit `cfc41d7e88fc39b8e1465964ef607ee6eb230e0b`, its expected parent and only FromiPhone.md changed.

Artifacts are under `.argent/recordings/gitsync-promo-20261014/` (untracked): raw `happy-path-raw.mov`, editable `capture.swift`, `edit.py`, `voiceover.py`, `edit-decisions.json`, captioned-only `gitsync-connect-short-captioned.mp4`, and narrated `gitsync-connect-short.mp4`. The 32-second exports use 1080×1920 vertical H.264 at 30 fps; the narrated export adds AAC stereo at 48 kHz. Inspected contact sheets across the edit and decoded the entire final video/audio without errors. Removed idle time through cuts/speed changes, masked the clone filesystem path, and excluded screens listing unrelated repositories from the export. Synthetic narration uses the built-in Samantha voice; original capture contains no audio.

Apple's public lookup API still reported GitSync.md 2.7.0 during production. The video therefore persistently says “2.8 PREVIEW · APP UPDATE COMING SOON.” The directory plugin itself is installable; this footage does not claim the compatible app update is already public. No social upload or publication was performed. Scoped Argent device services and the native capture session were stopped.

## Installation diagnosis

The official [Obsidian publication FAQ](https://obsidian.md/blog/future-of-plugins/#how-do-i-submit-a-new-plugin-or-theme) states that a passing submission becomes searchable and downloadable in the app within 24 hours. This is a documented propagation window, not evidence that a new plugin release is required. The owner dashboard confirms that 0.1.2 completed review with passing attestation, network, dependency and byte-for-byte build checks. GitHub confirms 0.1.2 is neither draft nor prerelease, was published at 2026-10-14T14:35:35Z (15:35 Lisbon), and has main.js and manifest.json assets. The manifest id matches gitsync-connect, minAppVersion is 1.8.7, and isDesktopOnly is false.

A repeat live registry probe still returned 8,911 entries with no gitsync-connect entry (exit status 1). The most recent registry mirror commit was 588427f6ad8221579f4be010011a80e75a1e6be3 at 2026-10-14T15:30:41Z. One mirror run after release does not prove the full approval/propagation pipeline is complete. There is no supported maintainer control identified to force that upstream sync. Monitor the existing listing rather than resubmitting or retagging the release. If still missing after the 24-hour publication window on 15 October, collect the current registry probe and dashboard state for escalation to Obsidian's plugin-dev support channel; do not send a message without the user's authorization. The release timestamp is a monitoring reference, not an independently verified review-completion timestamp.

## Evidence and retained state

At 2026-10-14T16:40Z (17:40 Lisbon), the official live registry contained 8,912 entries, including `gitsync-connect` → `codybontecou/gitsync-obsidian-connect`. This confirms the publication propagation blocker has cleared without another release or submission. Argent listed Cody's iPhone as paired with no reachable transport/tunnel, so installation could not resume. Requested cable reconnection, unlock and awake screen once; avoid repeating that request while device availability is unchanged.

- [No companion results on iPhone](evidence/obsidian-directory-iphone-20261014/companion-no-results.png)
- [Git installed and enabled](evidence/obsidian-directory-iphone-20261014/git-installed-enabled.png)
- [Registry snapshot](evidence/obsidian-directory-iphone-20261014/community-plugins.json)

The disposable vault and private repository remain available for a retry after the registry includes the companion. No ZIP/manual companion installation was substituted. An empty `GitSyncDirectoryTests` folder was also created in Obsidian during picker investigation. The saved default folder ends at the original Documents path; the displayed Documents name is Obsidian's underlying folder name, not proof of GitSync-owned storage. Earlier live commentary misidentified that label; opening the cloned vault and seeing the empty test folder in Obsidian corrected the diagnosis. No app source fix was made.

Argent flows created during setup are incomplete diagnostic traces with picker/selector exceptions, not verified regression tests. Retry from companion directory installation, then confirm the connection notice, introduce a new remote-only note and pull via Obsidian Git, create a phone-only note and commit/push via Obsidian Git, and independently verify its bytes and commit on GitHub.
