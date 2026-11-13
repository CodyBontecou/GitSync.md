# Obsidian Git companion: physical iPhone QA, 14 October 2026

Status: the full live flow passed on the physical iPhone: private clone in GitSync.md, automatic Obsidian Git connection, pull, and commit/push. GitHub independently confirmed the exact note created on the phone. The USB interruption was resolved by reconnecting; this was a live test across resumed sessions, not an uninterrupted automated replay.

## Device and setup

- Cody's iPhone 17 Pro, iOS 27.0.1, paired with Developer Mode enabled.
- Obsidian 1.14.5 (387), official Obsidian Git 2.41.1, GitSync.md Connect 0.1.0.
- GitSync.md built with Xcode for this physical phone, signed with the project's existing team, and installed in place without uninstalling or clearing app data. The original repository list and GitHub sign-in remained available.
- Fresh, private fixture repository: [CodyBontecou/gitsync-obsidian-qa-fixed-20261014](https://github.com/CodyBontecou/gitsync-obsidian-qa-fixed-20261014).
- Original diagnostic fixture: [CodyBontecou/gitsync-obsidian-iphone-qa-20261014](https://github.com/CodyBontecou/gitsync-obsidian-iphone-qa-20261014).
- Both fixture repositories contain test notes, the two plugin distributions, and Obsidian vault settings. No credentials were entered into Obsidian or committed to a fixture. `.obsidian/plugins/obsidian-git/data.json` and workspace files are ignored; the handoff mailbox is under `.git`.

## Tested source

Base repository commit: `2e054050daeda9bae9cc88384bb214227a41e523`, with the uncommitted companion/handoff changes. SHA-256 of `ObsidianCredentialHandoff.swift`: `cf6873fc90b60098689d46b981c2fe1eeec55e1020acec3192708d74da510448`. SHA-256 of the companion bundle: `c9023da321216ca81365f420196aa56e312a921755d1915dcc4503da103d12b2`.

## Observed results

| Step | Result | Evidence |
| --- | --- | --- |
| Clone original private vault using existing GitSync.md sign-in | Pass | Local main at `944168b2cba2dcc8d96305a6e3a7f00075a35fc7` |
| Enable both plugins and automatic credential import | Pass | App switched to GitSync.md and returned; Obsidian displayed “Obsidian Git is connected to GitSync.md.” |
| Initial Obsidian Git pull | Fail | Fetch reached GitHub and the local repository showed BEHIND REMOTE, but the remote note did not appear. The clone lacked repository author identity, required by Obsidian Git's mobile pull implementation. |
| Fix and automated regression | Pass | Added selected GitSync.md author name/email to the encrypted payload; companion fills only missing `user.name`/`user.email`. New regression failed before the fix and passed afterward. All ten tests, including Swift-to-JavaScript encryption and author import, passed. Signed physical-device build passed. |
| Fresh private clone with fixed companion | Pass | Local main at `fabe7dd`, in On My iPhone → Obsidian. |
| Fresh vault: trust plugins and automatic setup | Pass | Live wait for the connection notice succeeded in 1.072 seconds. No token, GitHub login, author name, or email was manually entered in Obsidian. |
| Fresh vault: pull new remote content through Obsidian Git | Pass, live verification | Remote-only `FromGitHub.md` was created at `caedfea17d2dc2063fe26a08b81278ce3b4374f3`; Obsidian Git Pull ran; the file appeared in the fresh vault and was opened with marker `gitsync-iphone-pull-20261014`. GitSync.md's Pull was not used for this fixture. |
| Create local note for push | Pass | `FromiPhone.md` was created and edited through Obsidian's UI, with marker `gitsync-iphone-push-20261014`. |
| Commit and push through Obsidian Git | Pass | After USB reconnection, ran “Git: Commit-and-sync with specific message.” Obsidian reported “Committed 5 files.” GitHub confirmed commit `4611fae72b46df1710b3a701f03734712496f014`, parent `caedfea17d2dc2063fe26a08b81278ce3b4374f3`, and the exact `FromiPhone.md` marker/body. Both author identity fields are present. |

The new author-import behavior preserves existing repository identity fields. Authentication remains in Obsidian Git's supported device-local store or its newer secret-store API when available.

## Screenshots

- [Fresh clone](evidence/obsidian-iphone-20261014/clone-fixed.png)
- [Automatic connection](evidence/obsidian-iphone-20261014/connected.png)
- [Remote note rendered in Obsidian](evidence/obsidian-iphone-20261014/pulled-note.png)
- [Local note ready to commit/push](evidence/obsidian-iphone-20261014/local-push-note.png)
- [Obsidian commit confirmation](evidence/obsidian-iphone-20261014/committed-note.png)
- [GitHub push verification](evidence/obsidian-iphone-20261014/push-verification.json)

## Recording limitations

Argent recordings are saved under `.argent/flows/gitsync-obsidian-iphone-*.yaml`. They are diagnostic traces, not a proven end-to-end regression suite. Some Files/Obsidian controls lacked usable accessibility labels; manual recovery was necessary. A trust-dialog tap during presentation selected Restricted Mode on the first fixture; the plugins were subsequently enabled in that fixture's settings. On the fresh fixture the dialog was allowed to settle and the correct Trust button was selected directly.

The fixed-pull replay ran the Pull command but its subsequent sidebar check was interrupted when another app was foregrounded. Obsidian was brought back and the actual pulled file and marker were then verified. That replay is not recorded as an uninterrupted passing run. The physical pull result is based on live UI/content evidence rather than the interrupted replay's verdict.

## Completed push verification

[Commit on GitHub](https://github.com/CodyBontecou/gitsync-obsidian-qa-fixed-20261014/commit/4611fae72b46df1710b3a701f03734712496f014) has message `QA: verify Obsidian Git push from iPhone`. The GitHub contents API returned:

```text
Marker: gitsync-iphone-push-20261014

Created on the iPhone in Obsidian and pushed using the imported GitSync.md credentials.
```

The five changed files were `FromiPhone.md` and `.obsidian/{app,appearance,community-plugins,core-plugins}.json`. No credential files or `.git` handoff files were included. Author name/email are populated; private identity values are omitted from saved evidence. The resumed push recording is a six-step diagnostic fragment and has not been replayed; its unlabeled mobile-menu tap was captured as the containing WebView, so that selector requires repair before reliable replay.

Do not delete either test repository or vault without an explicit cleanup request. Existing user vaults were not edited. Token revocation, background sync, and later token rotation are outside this test's scope.
