# Obsidian community directory submission

The entry is submitted at https://community.obsidian.md/plugins/gitsync-connect. All automated checks completed successfully for 0.1.2 (commit 333ed21), including artifact attestation and byte-for-byte build verification. The compatible native GitSync.md update is waiting for Apple App Review with manual release after approval and has not been released publicly. The material below records the submission and remaining native-app release requirements.

## Entry

- Repository URL: https://github.com/CodyBontecou/gitsync-obsidian-connect
- Owner: select Cody Bontecou's personal community profile (linked to GitHub account CodyBontecou).
- ID: `gitsync-connect`
- Name: GitSync.md Connect
- Description: Connect the Git plugin using your existing GitSync.md GitHub sign-in.
- Author: GitSync.md
- Author website: https://gitsync.md
- Release: https://github.com/CodyBontecou/gitsync-obsidian-connect/releases/tag/0.1.2
- License: MIT
- Mobile support: iPhone and iPad. Desktop commands explain that desktop Git should use a credential helper.

The directory reads the name, description, and version from the default branch's root manifest. Repository URL and ownership are the current submission form fields; the other values above help verify the imported entry.

## Reviewer description

GitSync.md Connect is a companion to the separate GitSync.md iOS app and the Git community plugin by Vinzent. After an authenticated GitHub HTTPS clone is opened as an Obsidian vault, it imports credentials through an encrypted, expiring local handoff and fills missing Git author fields. Users sign into GitHub in GitSync.md once. It does not install Git, schedule automatic sync, or implement a separate Git client.

The companion is free. GitSync.md has its own pricing and feature availability. A GitHub account and a compatible GitSync.md native app build are required. The plugin has no direct HTTP calls or telemetry; GitSync.md and Obsidian Git access GitHub for authentication and Git operations. It accesses files inside the vault only. README explains token permissions, device-local storage in Git 2.41.1, and revocation/sign-out behavior. It uses Git plugin internals with runtime checks; those APIs may change.

Ten protocol/lifecycle tests passed, including a compiled Swift/CryptoKit producer against the JavaScript consumer. Clone → automatic connection → pull → commit/push passed on a physical iPhone with Obsidian 1.14.5 and Git 2.41.1. Declared minimum Obsidian version is 1.8.7; older versions were not physically tested. A newer Git secret-storage API is covered by fixtures only.

## Before submitting

- Make the GitSync.md native update containing the handoff publicly available, or provide reviewers with a working supported test build and clear access instructions. Public availability of that app build is currently unconfirmed; the tested development build's version was 2.6.0.
- Verify a clean installation using the published release attachments, then repeat the iPhone pull/push flow with the supported native build.
- Sign into https://community.obsidian.md using the maintainer's Obsidian account and link GitHub. GitHub CLI authentication does not sign into the Obsidian account.
- Open Plugins → New plugin, paste the repository URL above, and choose the personal owner.
- Review the current developer policies and the ongoing-maintenance commitment. The maintainer must agree to these before selecting Submit.
- Record the resulting entry URL and review feedback here after submission; do not claim directory installation is available until approved.

The current process uses the Obsidian Community site, not a pull request to the legacy community-plugins.json list.

## Official references

- https://docs.obsidian.md/plugins/releasing/submit-plugin
- https://docs.obsidian.md/community-directory/set-up-and-claim
- https://docs.obsidian.md/community-directory/developer-policies
- https://docs.obsidian.md/community-directory/submission-requirements-for-plugins
