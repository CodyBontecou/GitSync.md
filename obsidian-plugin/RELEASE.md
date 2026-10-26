# Release GitSync.md Connect

## Current candidate

Version [0.1.3](https://github.com/CodyBontecou/gitsync-obsidian-connect/releases/tag/0.1.3) is available through the [Obsidian community directory](https://community.obsidian.md/plugins/gitsync-connect) and in [CodyBontecou/gitsync-obsidian-connect](https://github.com/CodyBontecou/gitsync-obsidian-connect). All automated community checks pass. Recommend the directory’s **Add to Obsidian → Install → Enable** flow for users. Manual asset installation is a fallback; do not recommend downloading a ZIP. See [SUBMISSION.md](SUBMISSION.md) for the prepared directory entry and remaining requirements. The MIT license here applies to this companion plugin, not the parent iOS application.

The physical iPhone check passed clone → automatic connection → Obsidian Git pull → commit/push with the app's handoff implementation. Evidence lives in the app repository at `docs/obsidian-iphone-qa-20261014.md`. Release the matching GitSync.md app update before advertising the plugin as generally usable. App version 2.6.0 alone is not sufficient evidence: the tested development build included unreleased handoff code.

## Prepare

On macOS with Node 22+, Xcode command-line tools, and zip:

```sh
npm test
npm run release:prepare
cd dist
shasum -a 256 -c SHA256SUMS
```

The optional development ZIP has exactly one `gitsync-connect/` folder; it is not the recommended user installation method. `main.js` and `manifest.json` are separate assets for Obsidian's installer. `gitsync-connect-source.zip` contains the standalone repository root, including the manifest, documentation, license, source, tests, native protocol fixture, and release workflow. `dist/source/` contains the same uncompressed repository tree. Copy its contents, including hidden files, into a dedicated plugin repository. Do not initialize or publish the whole iOS app repository as the plugin repository.

Before publication, run `npm test` and `npm run release:prepare` from the exported repository too. Review the native Swift snapshot against the app's implementation whenever changing the protocol. The app monorepo's tests use the live app source; the standalone repository uses its included test snapshot.

## Publish the candidate

1. Review the license, README, changelog, and exported source, then publish the standalone source to a dedicated public GitHub repository. Choose its final owner/name before adding installation links to announcements.
2. Keep `package.json`, `manifest.json`, and `versions.json` aligned. Use a plain `0.1.3` tag, without a `v` prefix. The included GitHub workflow validates/tests/builds and creates a **draft** release for matching version tags.
3. Review the draft assets and their GitHub attestations, then publish it. Attach only `main.js` and `manifest.json` (and `styles.css` if a future release needs it). Do not attach ZIPs or checksum files; they remain local/CI artifacts. Until this step, there is no public download or BRAT installation target. Include the compatible GitSync.md app release/build in the release notes.
4. Verify a clean manual installation of the two published installer files into a disposable vault. On an iPhone with the compatible app, enable Git and this plugin, clone/open the vault, trust the plugins, and verify both pull and push. Confirm no token entry in Obsidian is required.
5. Optionally test BRAT against the final repository. This is a beta distribution path and has not yet been verified for this candidate. Never advertise the app monorepo path as a BRAT target.
6. Submit the dedicated repository through the Obsidian Community directory using the current [submission guide](https://docs.obsidian.md/plugins/releasing/submit-plugin). The default branch must have `README.md`, `LICENSE`, and `manifest.json` at its root, and a matching GitHub release must attach `main.js` and `manifest.json`. Only announce in-app directory installation after approval.

No repository, tag, release, or community submission is created by `release:prepare`. Source and GitHub workflow changes still need to be committed/published. The matching native app availability remains a separate release requirement.

## Compatibility and support

- iPhone/iPad; Obsidian minimum 1.8.7 declared, physically tested 1.14.5.
- Git community plugin 2.41.1 physically tested. New secret-store API covered by automated fixtures only.
- GitHub HTTPS clones authenticated with a GitSync.md GitHub account; regular root `.git` directory.
- Existing tokens are preserved automatically; explicit Reconnect can replace them.
- Sign-out does not clear the token already imported into Obsidian. Explain the token storage/revocation behavior from README in public release notes.
- No analytics or new credential service. Device-local storage inherits Obsidian Git's guarantees.
- Before future releases, update versions/changelog and repeat actual iPhone lifecycle and pull/push checks. A fixture pass alone does not verify a new Obsidian Git version.

## Verify published provenance

Tag builds attest both supported release assets with `actions/attest@v4` before the draft release is created. After downloading the two release attachments:

```sh
gh attestation verify main.js --repo CodyBontecou/gitsync-obsidian-connect
gh attestation verify manifest.json --repo CodyBontecou/gitsync-obsidian-connect
```

The install/source ZIPs and SHA256SUMS generated by `release:prepare` are for development and CI downloads only. The public release exposes only supported installer files.
