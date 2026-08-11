# GitSync.md localization remediation

This directory contains the review artifacts and guarded automation used for the
localization release. Generated App Store marketing images remain under the
gitignored `marketing/` directory and are uploaded from the local checkout only.
Mutation scripts refuse to run unless their explicit confirmation flag is set.

## Locale mapping

| Runtime | App Store Connect |
| --- | --- |
| `ar` | `ar-SA` |
| `de` | `de-DE` |
| `es` | `es-ES` |
| `fr` | `fr-FR` |
| `nl` | `nl-NL` |
| `nb` | `no` |
| all other supported locales | same identifier |

The runtime matrix is English plus 25 translations: `ar`, `da`, `de`, `es`, `fi`, `fr`, `he`, `hi`, `hu`, `id`, `it`, `ja`, `ko`, `nb`, `nl`, `pl`, `pt-BR`, `ru`, `sv`, `th`, `tr`, `uk`, `vi`, `zh-Hans`, and `zh-Hant`.

## Reproduce local artifacts

Create an isolated Python environment and install `scripts/localization/requirements.txt`, then run:

```sh
python scripts/localization/translate_catalog.py --provider iciba
python scripts/localization/sync_catalog_from_stringsdata.py
python scripts/localization/audit_catalog.py
python scripts/localization/audit_source_strings.py
python scripts/localization/prepare_release_notes.py --provider iciba
python scripts/localization/audit_release_notes.py
python scripts/localization/prepare_next_version_metadata.py
python scripts/localization/audit_app_store_metadata.py
scripts/capture-marketing.sh
python scripts/localization/audit_screenshots.py
python scripts/localization/validate_screenshots_with_asc.py
python scripts/localization/make_contact_sheets.py
```

With an existing App Store Connect credential, the authenticated postflight is also read-only:

```sh
python scripts/localization/asc_read_only_postflight.py
```

The screenshot validator invokes Apple's local `asc screenshots validate` command for all 52 locale/device directories. The postflight invokes only authenticated read operations and writes a redacted summary to `localization/reports/asc-read-only-postflight.json`.

Machine translations and the entries in `manual-overrides.json` require human linguistic review before publication. `intentional-equivalents.json` documents syntax-only strings and technical Git terminology that may correctly remain identical to English.

## Change-control boundary

The live `2.5.1` version remains `READY_FOR_DISTRIBUTION`. The approved
localization release targets the `2.5.2` App Store Connect draft. Its metadata,
screenshots, build attachment, submission, and release are separate guarded
operations. The removed `bontecou.syncmd.unlock` IAP and the paid-up-front price
remain outside the mutation scope.
