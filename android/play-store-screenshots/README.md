# Google Play editorial campaign

The en-US Android assets adapt the current iOS campaign in `aso-tool/.asc/aso/screenshots-editorial-v1`: ivory paper, Georgia serif headlines, bundled Manrope supporting text, cobalt cloud and bird contours, and fine Git workflow sketches. The original repository-owned SVG artwork is reused; the feature graphic repositions those contours for its landscape format.

## Review and outputs

Open `index.html` in a browser for a responsive gallery, or `preview.png` for the contact sheet. `gitsync-google-play-assets.zip` contains five phone screenshots and one feature graphic.

Listing PNGs are saved in `../app/src/main/play/listings/en-US/graphics/`. Uploaded and saved to the paid app’s en-US default listing on October 27, 2026. The phone screenshots and feature graphic were submitted for Google review with managed publishing off, so they publish automatically once approved. Google quick checks were running at submission confirmation. See `play-console-upload.json` and `play-console-submitted.png`.

- Five phone screenshots: 1080 × 1920, 24-bit RGB PNG.
- Feature graphic: 1024 × 500, 24-bit RGB PNG.
- Fresh real Android emulator captures in `sources/`: `vault.png`, `git.png`, and `diff.png`.
- The release app's built-in sample repository is shown in light mode, without private repositories or credentials.
- UI is only rectangularly cropped and uniformly scaled. No UI reconstruction, recoloring, or retouching.
- Copy covers Android repository status, pull/commit/push, branches, diffs and staging. The iOS editor, SSH, Obsidian integration, stash and Shortcuts claims are omitted while Android parity remains unfinished.
- Manrope is bundled with its OFL license. Georgia is supplied by macOS; use the same font environment for identical renders.

Dimensions and PNG format follow [Google Play's preview asset requirements](https://support.google.com/googleplay/android-developer/answer/9866151?hl=en).

## Rebuild

```sh
cd play-store-screenshots
npm install
npx playwright install chromium
npm run render
```

In Codex, the bundled runtime can be reused without installing dependencies:

```sh
CODEX_NODE_MODULES=/path/to/bundled/node_modules node play-store-screenshots/render.mjs
```

`CHROMIUM_EXECUTABLE` can select a browser executable. Rendering is local and requires no API keys, network calls or image generation.

Edit `config.js` for copy and rectangular crop coordinates, `styles.css` for layout, and `campaign.js` for composition. `art.js` contains the reused iOS sketch SVGs. `render.mjs` exports the listing PNGs, checks text fit and copy/capture/footer clearance, verifies dimensions and RGB channels, and rebuilds the contact sheet, manifest and ZIP. Original earlier captures remain in `screenshots/`.

Capture note: the emulator accessibility helper returned stale data after navigation, so capture controls were verified visually. This is an asset capture session, not a passing app regression test. The sample repository remains staged and app Dark Theme is disabled for these captures.
