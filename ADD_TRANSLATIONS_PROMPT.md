# Prompt: Add 25 Language Translations to Sync.md (Parallel Agents)

## Overview

This is a **parallel translation task** for the Sync.md iOS app.
The app has been internationalized with English as the base language.
Your job is to add translations for one language (assigned below).

Run one agent per language. All agents work on separate files — there are
**zero conflicts** between agents.

---

## Project Context

- **Project root**: `/Users/codybontecou/dev/Sync.md`
- **All 173 English string keys** live in:
  `Sync.md/Localizable.xcstrings`
- **Your output**: Create one new file:
  `Sync.md/{your-locale}.lproj/Localizable.strings`
- The project uses `PBXFileSystemSynchronizedRootGroup` (Xcode 16+),
  so new files in the `Sync.md/` directory are auto-discovered — no
  Xcode project file edits are required.

---

## Your Task (same for every agent)

### Step 1 — Read the source strings

Read the file `Sync.md/Localizable.xcstrings`. This is a JSON file.
For each entry in `"strings"`, you need:
- The **key** (the JSON object key, e.g. `"Cancel"`)
- The **English value** (at `localizations.en.stringUnit.value`)
- The **comment** (at `comment`) — this is context for you as a translator

### Step 2 — Create the translations file

Create the directory and file:
```
Sync.md/{your-locale}.lproj/Localizable.strings
```

The file format is standard Apple `.strings`:
```
/* Translator comment describing context */
"key" = "translated value";
```

**Rules:**
1. Translate every one of the 173 keys — do not skip any.
2. The **key** is ALWAYS the exact English string — never translate it.
3. Preserve all format specifiers exactly as-is: `%@`, `%lld`, `\n`.
   Only translate the surrounding natural-language text.
4. Do NOT translate proper nouns: `Sync.md`, `GitHub`, `Git`,
   `Obsidian`, `ghp_...`, `PAT`, `SHA`, `OAuth`.
5. Preserve punctuation style (ellipsis `…`, em dash `—`, curly
   apostrophes `'`) — use the target language's natural equivalents
   where appropriate, but keep `%@` and `%lld` unchanged.
6. URL placeholders like `https://github.com/user/repo` should be
   kept as-is (they are examples, not content).
7. Use natural, idiomatic phrasing for a mobile app UI — not
   literal word-for-word translation.
8. For RTL languages (Arabic, Hebrew), only translate the values —
   the layout is handled by iOS automatically.

### Step 3 — Commit

After creating the file, commit:
```bash
cd /Users/codybontecou/dev/Sync.md
git add Sync.md/{your-locale}.lproj/Localizable.strings
git commit -m "i18n: add {Language Name} ({locale}) translations"
```

---

## Language Assignments

Spin up one agent per row. Each agent should be given this entire
document plus the specific row from the table below as their assignment.

| Agent | Language              | Locale code | Notes                           |
|-------|-----------------------|-------------|---------------------------------|
| 1     | Spanish               | `es`        | Use Latin American standard     |
| 2     | French                | `fr`        | Use metropolitan French         |
| 3     | German                | `de`        |                                 |
| 4     | Italian               | `it`        |                                 |
| 5     | Brazilian Portuguese  | `pt-BR`     | Use `pt-BR` not `pt`            |
| 6     | Russian               | `ru`        |                                 |
| 7     | Japanese              | `ja`        | Use polite/neutral register     |
| 8     | Korean                | `ko`        | Use formal polite style (합쇼체) |
| 9     | Simplified Chinese    | `zh-Hans`   | Mainland China standard         |
| 10    | Traditional Chinese   | `zh-Hant`   | Taiwan/HK standard              |
| 11    | Arabic                | `ar`        | Use Modern Standard Arabic      |
| 12    | Hindi                 | `hi`        |                                 |
| 13    | Turkish               | `tr`        |                                 |
| 14    | Dutch                 | `nl`        |                                 |
| 15    | Polish                | `pl`        |                                 |
| 16    | Vietnamese            | `vi`        |                                 |
| 17    | Indonesian            | `id`        |                                 |
| 18    | Thai                  | `th`        |                                 |
| 19    | Swedish               | `sv`        |                                 |
| 20    | Danish                | `da`        |                                 |
| 21    | Norwegian Bokmål      | `nb`        |                                 |
| 22    | Finnish               | `fi`        |                                 |
| 23    | Ukrainian             | `uk`        |                                 |
| 24    | Hebrew                | `he`        | RTL — values only               |
| 25    | Hungarian             | `hu`        |                                 |

---

## Example Output

For **Spanish (`es`)**, the file `Sync.md/es.lproj/Localizable.strings`
should begin like this:

```strings
/* Generic cancel button */
"Cancel" = "Cancelar";

/* Generic OK / dismiss button */
"OK" = "OK";

/* Generic done / close button */
"Done" = "Listo";

/* Generic save button */
"Save" = "Guardar";

/* Generic back navigation button */
"Back" = "Atrás";

/* Generic error alert title */
"Error" = "Error";

/* Fallback when no specific error message is available */
"Unknown error" = "Error desconocido";

/* Divider label between two alternative actions */
"or" = "o";

/* Displayed when a sync or date has never occurred */
"Never" = "Nunca";

/* Sync progress message at the start of a clone operation */
"Preparing to clone..." = "Preparando la clonación…";

/* Sync progress message after a successful clone. %lld is the number of files. */
"Clone complete! (%lld files)" = "¡Clonación completa! (%lld archivos)";

/* Error when pushing commits fails. %@ is the libgit2 error detail. */
"Push failed: %@" = "Error al enviar: %@";

/* Navigation title for the Git control sheet */
"Git" = "Git";

/* Push button label when there is exactly one local change */
"Push 1 change" = "Enviar 1 cambio";

/* Push button label showing the number of local changes. %lld is the count (2 or more). */
"Push %lld changes" = "Enviar %lld cambios";

/* ... (continue for all 173 keys) ... */
```

---

## After ALL Agents Complete (Coordinator Step)

Once all 25 agents have committed their files, run this single command
to register all new locales in the Xcode project:

```bash
cd /Users/codybontecou/dev/Sync.md

# Insert all 25 locales into knownRegions in project.pbxproj
python3 - << 'EOF'
import re

path = "Sync.md.xcodeproj/project.pbxproj"
with open(path) as f:
    content = f.read()

new_locales = [
    "ar", "cs", "da", "de", "es", "fi", "fr", "he", "hi",
    "hu", "id", "it", "ja", "ko", "nb", "nl", "pl", "pt-BR",
    "ru", "sv", "th", "tr", "uk", "vi", "zh-Hans", "zh-Hant",
]

# Find the knownRegions array and insert all new locales
def add_regions(m):
    existing = m.group(0)
    for locale in new_locales:
        if f'"{locale}"' not in existing and f'\t\t\t\t{locale},' not in existing:
            existing = existing.replace(
                "\t\t\t\tBase,",
                f"\t\t\t\tBase,\n\t\t\t\t{locale},"
            )
    return existing

content = re.sub(
    r'knownRegions = \(.*?Base,\s*\);',
    add_regions,
    content,
    flags=re.DOTALL
)

with open(path, "w") as f:
    f.write(content)

print("knownRegions updated.")
EOF

git add Sync.md.xcodeproj/project.pbxproj
git commit -m "i18n: register 25 new locales in knownRegions"
```

---

## Verification

After everything is done, verify all 25 language files exist:

```bash
find /Users/codybontecou/dev/Sync.md/Sync.md -name "Localizable.strings" | sort
```

Expected output: 25 files, one per locale directory.

Build to confirm no regressions:
```bash
cd /Users/codybontecou/dev/Sync.md
xcodebuild -project Sync.md.xcodeproj \
           -scheme "Sync.md" \
           -destination "generic/platform=iOS" \
           -configuration Debug \
           CODE_SIGNING_ALLOWED=NO build 2>&1 | grep -E "error:|warning:|BUILD"
```

Expected: `** BUILD SUCCEEDED **` with no errors.
