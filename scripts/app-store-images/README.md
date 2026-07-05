# App Store image draft generator

This is a small, repo-aware first draft for generating App Store marketing images for the app in the current repository.

It inspects the repo for an app name, product copy, brand colors, typography hints, feature names, and existing screenshots. AI is used only for abstract backgrounds and visual treatments. Real app UI screenshots are composited into a phone frame when available; if no screenshots are found, the final images are marked draft-only with placeholders.

## Setup

Install the tool dependencies from the repo root:

```bash
npm --prefix scripts/app-store-images install
```

Add your OpenAI API key to a local `.env` file or export it in your shell:

```bash
OPENAI_API_KEY=your_key_here
```

The key is required only when `--generate` is passed. Dry runs never call the AI API.

## Dry run

Dry-run is the default and writes only a plan/manifest:

```bash
npm --prefix scripts/app-store-images run plan
# or
npm --prefix scripts/app-store-images exec -- tsx scripts/app-store-images/generate.ts --dry-run
```

This prints the planned image count, model, quality, target size, screenshot sources, and prompts, then writes:

```text
app-store-output/manifest.json
```

## Generate images

Generation requires `OPENAI_API_KEY` and an explicit `--generate` flag:

```bash
npm --prefix scripts/app-store-images run generate
# equivalent:
npm --prefix scripts/app-store-images exec -- tsx scripts/app-store-images/generate.ts --generate --max-images 3
```

Outputs are written to:

```text
app-store-output/backgrounds/
app-store-output/final/
app-store-output/manifest.json
```

`app-store-output/` is ignored by git and should be reviewed manually before any App Store submission.

## Screenshots

Put real app screenshots here:

```text
app-store-input/screenshots/
```

PNG, JPG, JPEG, and WEBP files are supported. The tool also looks in common repo folders such as `screenshots/`, `Screenshots/`, `fastlane/screenshots/`, `metadata/`, `docs/`, `app-store/`, and `marketing/`.

If no screenshots exist, the generated final images use a neutral frame that says “Add app screenshot here” and the manifest sets `draftOnly: true`.

## Brand overrides

Copy the example file and adjust it if detection is not good enough:

```bash
cp app-store-input/brand.example.json app-store-input/brand.json
```

Shape:

```json
{
  "appName": "App Name",
  "primaryColor": "#000000",
  "secondaryColor": "#ffffff",
  "accentColor": "#4F46E5",
  "fontFamily": "system",
  "category": "productivity",
  "tone": "premium, calm, modern"
}
```

## Cost controls

The tool is conservative by default:

- dry-run mode is default
- no AI call happens unless `--generate` is passed
- `--max-images` defaults to `3`
- an absolute first-draft cap rejects more than `6` images
- `--max-variants-per-screen` defaults to `1`
- an absolute first-draft cap rejects more than `2` variants per screen
- `--quality` defaults to `medium`
- `--model` defaults to `gpt-image-2`
- each image is retried at most once
- existing output PNGs are not overwritten unless `--force` is passed

Useful flags:

```bash
--target-size 1320x2868   # default; also supports 1290x2796 and 1260x2736
--input-screenshots app-store-input/screenshots
--output-dir app-store-output
--brand-file app-store-input/brand.json
--seed 123                # passed through if the model supports it
--force                   # overwrite previous PNG outputs
```

## What repo inspection looks for

The detector is intentionally generic and conservative. It checks common sources such as:

- iOS `Info.plist`, XcodeGen `project.yml`, Xcode project files, `package.json`, and README titles for the app name
- CSS/theme/token files, Swift design files, Tailwind config, and marketing HTML for colors and typography hints
- README/docs/website copy, HTML article cards, headings, and localization-like strings for feature ideas
- common screenshot/marketing folders for real UI screenshots

## Notes

This is a practical draft pipeline, not a production App Store submission system. Review copy, screenshots, generated backgrounds, and device framing before submitting anything to App Store Connect.
