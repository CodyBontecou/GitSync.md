# Android localization pipeline

The source baseline is the sibling iOS `Sync.md/Localizable.xcstrings`. It lists
English plus 25 other locales. Generated Android resources are checked in;
compiling the Android app does not require that sibling checkout or Python tools.

Coverage is intentionally explicit: the complete current base inventory contains
1,597 translatable string keys and 30 plural resources. Each non-English
locale has all 1,597 string keys and all 30 plural resources; no base resource
falls back to English. The 2026-11-03 literal pass moved remaining user-visible
Kotlin copy into five `values/l10n_*_strings.xml` files (276 strings, 8 plurals;
translations in `android_workflow_overrides.json`). ViewModels without a Context
expose `ui.util.UiText` (resource id + arguments, or raw Git/server text) and
screens resolve it at display time. `literal_guard.py` fails on new English
literals in Compose text, copy parameters, UI error/notice state and app-authored
exception messages; 143 reviewed exceptions (previews, Git/commit data, product
names, diagnostics that are caught and replaced) are pinned in
`literal_guard_baseline.json`. Raw JGit/server output is still shown verbatim.
The importer rejects plural forms that omit a category Android ICU selects for
the locale (for example Spanish `many`, Arabic `zero`/`two`).
All three tools share discovery
of every XML filename under base `values`, including `strings_*.xml` and arbitrary
names. Style entries do not count as strings. Coverage still does not include all
raw conditional/model/default copy, and native/linguistic acceptance remains open.
Product names, filenames, paths and Git refs must remain literal data.

## Extract visible Compose copy

```sh
python3 tools/localization/extract_compose.py --inventory /tmp/compose-copy.json
python3 tools/localization/extract_compose.py --inventory /tmp/compose-copy.json --apply
```

The tool uses Kotlin PSI from cached Kotlin compiler dependencies, not regex
replacement over source files. It accepts only direct visible String arguments of
known/composable UI functions, skips pure dynamic data/symbols/technical URLs,
preserves interpolation as positional arguments, and verifies all original source
ranges before changing a file. Unicode offsets are converted from JVM UTF-16 to
Python code points. A private temporary pre-edit source backup and hashes are
created before an apply. No Git reset/stash is used. The inspection tool currently
uses cached Kotlin compiler/stdlib 2.2.0, Gson 2.13.2, trove4j and coroutines; this
does not change the app's Kotlin version or dependencies. Use Java 17.

`english_literals.json` preserves independent unescaped Kotlin source expectations
for native AAPT/format testing. New extractions append to it. An intentional English
copy change requires reviewing its fixture and translation source, rather than
silently updating the native expectation from generated XML. The license card now
uses “One-time purchase via Google Play” instead of a fixed dollar amount. Its
fixture retains `original_source` and a `source_review` explanation, and all locale
values use the reviewed currency-neutral source. Forty-one existing redesign capitalization changes also have explicitly reviewed
source expectations with original text and review notes retained. Native checks
match resource identities and exact source, so duplicate English labels are valid
and changed wording still fails. Re-extraction preserves these review annotations.

`reviewed_native_sources.json` pins independently reviewed English formats for
handwritten resource keys that were not part of the original Compose extraction
or Android overrides. The generator checks these against the base resources and
rejects any translated formatted key without an independent argument source.
Review a new format here before importing it; do not derive its expectation from
the generated locale XML.

## Import and validate translations

```sh
python3 tools/localization/import_ios_catalog.py
python3 tools/localization/import_ios_catalog.py --apply
python3 tools/localization/import_ios_catalog.py --check
python3 tools/localization/native_expectations.py
python3 tools/localization/native_expectations.py --check
python3 -m unittest discover -s tools/localization -p 'test_*.py'
python3 tools/localization/literal_guard.py
```

The native expectation generator also accepts `--catalog /absolute/path/to/Localizable.xcstrings` for fixed-checkout verification.

Imports require English-equivalent copy (case/typography/whitespace normalization),
translated values in every iOS locale, and matching argument positions. There is
no fuzzy semantic matching. Swift placeholders are adapted to the Android
caller's types/positions; missing/extra arguments and malformed percent formats
are rejected. XML/Android quotes, apostrophes, whitespace and escape sequences
are preserved. Unmatched copy falls back to the complete English resources and
stays in the coverage manifest. Do not mark a key translated by copying English
into every locale.

`android_overrides.json` owns Android-specific copy and reviewed terminology
corrections. Its English source must still match the resource. It currently
contains the language-settings strings and the Git meaning of Stage All; the iOS
German/Spanish/French source had used theatrical-stage/floor wording. Overrides
currently provide all 25 locales. `android_workflow_overrides.json` adds LFS review
copy and plural forms, file/history labels, and corrections for malformed Back,
rebase, named-delete, background-policy and repository-action translations. The
Repository Actions overrides include reviewed Git action/message meanings for
malformed or untranslated inherited labels, alongside the full 34-string main
action resource group. Launcher-shortcut consent, background execution and
result-recovery copy also has all-25-locale mappings. `ios_aliases.json` lists
five explicitly reviewed source pairs with guarded punctuation and RTL arrows;
both English sources and every formatter argument must still match. Additional translations still require language
quality review; an iOS `translated` state alone is not semantic certification.

`locales_config.xml` exposes the same locale tags to Android 13+ app-language
settings. Older Android follows device language. Regional/script and legacy
resource qualifiers are explicit: pt-rBR, b+zh+Hans, b+zh+Hant, iw and in. Native
resource tests verify resolution, not just filename presence. See the official
[per-app language guidance](https://developer.android.com/guide/topics/resources/app-languages)
and [string resource escaping](https://developer.android.com/guide/topics/resources/string-resource).

## Native evidence and boundaries

`LocalizationTest` compares 468 independent English source expectations (including the reviewed license copy), additional workflow English, and the imported rows for all 25 locales in the current expectation asset. It checks
values against unescaped source fixtures, exercises English/unsupported-language
fallback and Android LocaleConfig, and renders production components in Spanish,
Arabic RTL, Turkish casing and narrow long-label Italian at 160% local text size.
It never sets the device or app language. `FileNavigationTest` opens the real
app-scoped system picker and returns with Back without changing its preference.
`FileNavigationTest` also changes the actual package locale across all 25
languages, requires a distinct resumed activity, checks 23 Settings labels and
text-layout overflow per language, verifies the localized 48dp Back control and non-overlapping title, and retains repository selection, catalog,
working bytes and HEAD. Arabic/Hebrew use dark mode and other locales use light
mode. The missing-default-folder case verifies localized recovery copy without an
Open action, and the valid folder control has a minimum 48dp target. A protected
host wrapper checks these routes at actual system font scale 1.6 and restores the
original setting in `finally`. These captures cover the selected Settings views;
other screens still need qualification. Native quantity checks compare six LFS/rebase plural resources across all 25
locales and English using Android ICU categories at 16 quantities, including Arabic
zero/two and Slavic few/many. These verify resource selection and formatting;
assistant-authored translations still need human linguistic review. Full
translation, dynamic errors, other screen recreations, cold-process language
persistence, release bundle language delivery and wider accessibility remain open.

The current callback request additions include ten labels and three foreground
review/return explanations in all 25 locales. The corresponding
`RepositoryCallbackScreenTest` component check uses injected locale resources,
direction and 160% component text, preserving the local draft, Git state and
catalog without dispatching Git or an external return. It has been authored but
has not run; it does not qualify actual system-language recreation or linguistic
quality. Runtime checks of the refreshed catalog are also pending after the
independent file-size format expectation was repaired.

Screenshot capture waits for a committed render frame after semantic assertions;
semantic idleness alone can still show the preceding locale in a system capture.
The frame-commit fix is test-only. The localization-phase broad product regression passed 115
checks; the subsequent four-case localization replay verifies the capture helper
and does not increase the distinct coverage count.


Clone recovery management adds 16 English fallback keys, reusing the existing
translated destination label. Independent source expectations and coverage checks
still apply; the new fallback copy is not counted as translated.

The Settings pass adds 27 mapped resource keys in all 25 locales and replaces
English `Background Sync` values that the iOS catalog had marked translated.
That is 700 authored values across 28 keys; the title correction does not increase
the mapped-key count. All 44 static string references in Settings have complete
locale entries. Brand names, application versions and filesystem paths remain
runtime data. The 660 remaining fallback keys and linguistic review remain open.


The complete first-run tour now has 16 Android-authored workflow mappings in all
25 non-English locales (400 values), covering the four pages, setup choices,
progress counter and save-error notice. All 20 `guide_` string resources now
have translations. Product names and Git identifiers remain literal. Source
English is unchanged and guarded by the override importer. Native format
checks take argument types from original Android source fixtures, so an iOS
numeric placeholder adapted to Android text remains text, while the tour's
integer step counter uses locale-specific digits. Human linguistic review and
the remaining 660 fallbacks are still open.

The URL-clone pass adds 36 workflow mappings in each of 25 locales (900 authored
values), including eight newly extracted conditional/default messages. This
covers public, username/token, SSH and saved-connection copy, disconnected GitHub
account guidance, folder consent, key visibility and clone-location controls.
English source wording and literal SSH address examples remain guarded. At that checkpoint, coverage
was 278 translated keys out of 910 base keys per locale, with 632 English
fallbacks and three translated LFS plurals. Dynamic model/transport error messages
and human linguistic review remain open.


Rebase preflight failures use `RebaseProblem` identities rather than English-message
matching. Active operations, detached HEAD, local changes and missing/unsupported
upstreams render translated recovery in the screen. The all-locale native workflow
triggers all four with real repository state at actual 160% system text and checks
index/config/worktree/ref preservation. Core tests also verify rejection before
authentication and dirty state introduced after a reviewed plan. Other runtime and
transport errors still need localization; these identities cover those four cases.


Clone-storage controls and both success notices now have authored translations in
all 25 non-English locales. Storage success state keeps a message identity rather
than an English sentence, so rendering resolves the current resource language.
The dedicated storage-language wrapper checks the production app-storage route,
notice retention across activity recreation, left-to-right filesystem paths in RTL locales, wrapping text and 48dp Back target
at 160% system text. Shared-folder/provider errors remain runtime diagnostics and
still need localization and wider workflow qualification.


The normal file-browser/editor workflow adds 24 authored mappings in each of the
25 non-English locales, including hidden-file controls, empty-state/menu copy,
unsaved-change/reload/delete confirmations and save/rename feedback. Feedback
retains message identities and renders in the current resource language; size
numbers also use the app configuration locale. Git identifiers, file names and
technical unit symbols remain data. Runtime filesystem/binary/large-file error
messages and human linguistic review still need work.


File recovery uses typed `RepositoryFileIssue` values and an exhaustive Android
resource mapping. The browser/editor no longer chooses messages by matching
English exception text. Twelve localized reasons cover external-change guards,
missing/unreadable paths, invalid names, protected paths, duplicate names, denied
access and binary/non-UTF-8/oversized read-only content. Unknown platform failures
use the localized read/operation fallback; their arbitrary diagnostic text is
not a translation source. These assistant-authored translations still require
human linguistic review.

Push Sync adds 28 previously missing keys in all 25 languages (700 values),
covering consent scope, registration, GitHub installation management, recovery,
notifications and update results. All 30 keys in `push_strings.xml` now resolve
translated values; its title was already covered. The existing “Already up to
date” mapping is now an Android workflow override in all 25 languages, removing
inherited translator annotations/stray punctuation and correcting the Polish
in-progress meaning. Arabic/Hebrew notification arguments isolate the literal
branch name with FSI/PDI; the branch argument itself is unchanged. The catalog
now maps 431 of 973 base keys per language, leaving 542 English fallbacks; six
plural resources and 468 independent original English fixtures are unchanged.
The Push Sync language runner is documented in `tools/testing/README.md`.
Human linguistic review and the remaining fallback/workflow coverage stay open.


Pull All adds 30 missing mappings and corrects its title/widget action mappings
in every non-English locale (800 added or corrected values across 32 keys).
All 33 keys across the foreground/background Pull All resource files now have
translations. Coverage is 461 of 973 keys per language, with 512 English
fallbacks. Six plural resources and 468 original English expectations are
unchanged. Translation quality review and remaining workflows stay open.


Background Sync adds 36 mappings in all 25 languages (900 values), covering
automatic pull/publish consent, scheduling limits, repository enrollment, pinned
branch/connection review, health feedback and settings recovery. All 44 strings
in `background_strings.xml` now have locale mappings. Arabic/Hebrew binding text
isolates unchanged branch and remote/upstream arguments with FSI/PDI. The catalog
at that stage mapped 497 of 973 keys per language, leaving 476 English fallbacks. The six
plural resources and 468 independent original English expectations are unchanged.
Translation quality review and the other fallback workflows remain open.


The existing GitHub App installation form and callback destinations, result summaries,
progress, duplicate-folder selection and return handoff add 16 Android-specific
mappings per locale (400 values). The GitHub account identifier field uses explicit
LTR text and an ASCII keyboard with Done dismissal. These additions pass source
format validation and compilation separately from the earlier Push Sync language
workflow; the new field and callback results still need device layout and linguistic
review. Their mappings do not imply that live GitHub linking has been qualified.


All resource keys in `callback_strings.xml` now have mappings in the 25 non-English
locales, including background authority, interrupted work, rejected overlapping
requests, saved results and return-delivery recovery. Menu references in the new
paragraphs use the existing translated Repository actions and Authorized links
labels. This does not cover unextracted/default/model/error copy elsewhere or
establish linguistic or runtime completion. The long dialog's new component test
is compiled separately from native execution.


Credential migration, repository metadata status and deletion/rename-conflict
explanations add another 16 keys per locale. Migration copy preserves encrypted
storage, separate fetch/push addresses, existing secure/public choices, inactive
recovered credentials and explicit removal of all saved credentials when public
access is selected. Conflict explanations quote the app's existing localized
Ours/Theirs labels. All keys in the credential-migration, repository-status,
deletion-conflict and rename-conflict resource files now have locale mappings;
this is resource coverage, with native layout and linguistic review still open.


All 31 remote-merge resources now have mappings, including reviewed local-only
merge versus publication, retained commit IDs, conflict recovery, acknowledgement
and explicit same-commit push retry. Arabic/Hebrew technical arguments use LTR
isolates; the author name/email pair is isolated together. The merge screen uses
the shared wrapping header and explicit 48dp action/confirmation controls. These
are source/build changes; the new locale consent component test has not executed
on a device and does not establish publication or linguistic acceptance.


All interrupted-clone recovery and release-note resource keys now have mappings.
Recovery dismissal copy explicitly preserves files and saved connections, and its
confirmation body scrolls at large text. Release-note headers wrap and upgrade
acknowledgement retains an explicit 48dp minimum. These source changes still need
native language/layout and human linguistic qualification; older English fixture
passes do not establish current all-language acceptance.


Obsidian's 13 remaining messages and the referenced Repository connection title
now have mappings in every locale. Instructions retain the literal companion
command `GitSync.md Connect: Connect this vault`, verified in the sibling plugin,
and state that forgetting future handoffs does not remove credentials already
imported into Obsidian. Headers wrap; forget consent scrolls; paths are LTR.
Native handoff/switching and linguistic qualification remain separate from source
and build checks. Extracted fallbacks now remain in publication (64) and general
UI copy (195); unextracted/default/model/error text remains outside that count.


The first publication group adds 32 keys per locale: local review/preparation,
public/private approval, saved-commit publishing/retry and explicit adoption after
uncertain creation. Positional owner/name/visibility/ID/URL arguments remain typed;
Arabic/Hebrew keep owner/name together as one LTR identifier. Preparation does not
create a remote, and matching names do not prove a prior attempt created one.
Publication dialogs now scroll and headers wrap. The 32 remaining publication keys
and 195 general UI fallbacks, plus native/provider/linguistic qualification and
unextracted copy, stay open.


The remaining 32 publication recovery, progress, sensitive-file and discard keys
now have mappings in all 25 locales. All 86 publication resource keys are mapped;
195 extracted general UI keys still fall back to English. This source coverage
does not establish native language/layout or human linguistic acceptance.


Diagnostics adds 33 previously unextracted resources and maps those plus eight
existing screen keys in all 25 locales (41 keys, 1,025 values). Feedback is stored
as resource identities and resolved during composition. Operation/outcome/reason
labels, empty states, filters, export chooser and clear-log consent now follow the
app language. The exported support log retains its stable existing format.
Counts and timestamps use the composition locale. Clear consent scrolls and its
controls have 48dp minimum height. The new all-locale component fixture is authored
for retained feedback, severity filtering, cancel preservation and clearing hidden
records, but has not run on the physical Pixel. Human linguistic review remains
open. Coverage is 823/1,010 keys; 187 extracted general UI keys still fall back.


SSH trust review and management adds 10 previously embedded messages and maps them
plus 14 existing keys in every locale (24 keys, 600 values). Unknown/changed-key
approval, impersonation warnings, one-server forgetting, all-server reset and
six trust-store failure identities resolve from current resources. Transport
exceptions outside this store’s own error dialog remain separate pending copy.
Hosts/ports, algorithms and fingerprints use LTR text; Arabic/Hebrew embedded
fingerprints and complete host:port pairs are isolated. Headers wrap and consent
bodies scroll with 48dp dialog/action controls. Approval matching, persistence,
rollback and verification gates remain in the store. New native component and
stale-approval tests are authored; physical execution and human linguistic review
remain pending. Current source coverage is 847/1,020, with 173 general UI fallbacks.

The 847-key release is now installed and hash-verified on the physical Pixel 7.
The existing English/Gboard Push Sync linked-account form replay passes after
that update, preserving the personal connection. This does not qualify the new
SSH/Diagnostics locale fixtures or all-language layout and linguistic acceptance.

Physical 847-key walkthrough evidence now covers selected production Diagnostics
and empty SSH trust/reset-consent states in English and Spanish (US), including
actual app-language selection and restoration through Android Settings. The
recorded full regression remains unqualified: one Settings scroll-offset check
was repaired, then physical ADB loss interrupted the next replay. The successful
restoration segment proves English/default-language and connection controls at
that checkpoint, not completion of the whole path. See the incomplete draft and
runtime proof in `tools/testing/README.md`; all-locale native fixtures and human
language acceptance remain pending.


LFS endpoint normal-flow consent and management adds 16 previously embedded
resources and maps those plus 16 existing keys in each locale (32 keys, 800 values).
Upload/download consent, access-policy summaries and revocation use complete
messages for each direction; endpoint addresses remain literal. Revocation copy
explicitly preserves approval and credentials for the opposite direction. Scope,
credential storage, secret-clearing and authorization behavior remain unchanged.
Store-generated error/exception text remains pending separate localization.
New all-locale component fixtures are authored; device and linguistic acceptance
remain pending. Coverage is 879/1,036, with 157 extracted English fallbacks.

The iOS catalog changed from
`ef25055161a2bb49d2d024bd6771f24d049b26a179712afc233f092c16830b1a` to
`097bf7764dbd42562147c65afa7c52a6f6cde920444fe95c9182335f191db5da`
during this source phase. Twenty-two Android labels lost automatic matches.
Their 550 translations were preserved from the independently pinned 847-key test
APK expectations and checked against regenerated XML after formatter adaptation.
`preserved_catalog_sources.json` records their sources and provenance hashes.
`publication_file_size` now gets its unchanged independent source expectation
from the workflow override instead of a duplicate reviewed-source entry.


## Preserve existing coverage during imports

The importer compares the previous checked-in `coverage.json` and existing locale
XML before replacing generated files. A newly translated label cannot mask a lost
active label, even when the total mapping count stays the same or increases.
Locale removal, translated plural removal and loss of existing plural categories
are also reported. Retiring an Android resource that no longer exists in the
English base does not require an exception.

Preview the current catalog without modifying generated resources:

```sh
python3 tools/localization/import_ios_catalog.py
```

If coverage would be lost, `/tmp/syncmd-localization-import-preview.json` includes
`translation_loss` with the affected labels, locales, plurals and plural categories.
Both `--apply` and `--check` reject such loss before any generated file is written.
Preserve the missing translations with exact-source overrides or fix the catalog
match. For an intentional removal already inspected in the preview, use:

```sh
python3 tools/localization/import_ios_catalog.py --apply --allow-translation-loss
```

The option applies only to `--apply`; it does not bypass argument/source validation
or malformed-baseline checks. It does not delete obsolete locale directories.
A first import with no previous coverage file remains supported. The guard relies
on existing locale XML for the previous plural names and categories. Baseline or
XML parse failures stop the import instead of replacing that evidence.

`test_catalog_loss.py` exercises the real importer in isolated temporary projects,
including exact preservation of generated-file bytes after rejection, preview-only
loss reports, explicit removal, normal additions, plural-only loss and malformed
baseline handling. The full localization tooling suite currently has 22 tests.


The LFS approval store’s own error dialog now resolves eight failure identities
from resources in every locale (8 newly extracted keys, 200 values). Saved-record
damage, stale review, mismatched authorization, invalid separate credentials,
save, recovery, revoke and reset failures remain distinct. Raw exception strings
are no longer forwarded into that dialog. Dismissing the message only clears the
displayed error; resetting/revoking still requires its existing explicit path.
The transport-facing exceptions and other LFS action/model error text remain
separate pending extraction/localization. Current coverage is 887/1,044, with
157 extracted fallbacks. New error fixtures are authored, not device-qualified;
human linguistic acceptance remains open.


The reviewed-discard batch adds five workflow messages (125 values) plus a quantity resource in all 25 locales. It distinguishes committed all-file discard from unborn staging-only behavior and explicitly retains ignored files. RevertConfirmDialog uses the frozen review’s distinct path count, complete LTR paths, localized actions and a bounded list containing its footer. The old English-suffix count resource remains historical data and is no longer used by that dialog. Native expectation generation/check and the 22 tooling regressions pass; authored all-locale dialog layout checks compile but have not run on the physical Pixel. These checks do not replace human linguistic review.

LFS file summaries now extract five conditional availability/cache messages, with 125 authored translations across all 25 locales. Candidate, tracked-object and extension-review sizes use the current context’s Android file-size formatter instead of concatenating raw English byte labels. The 150-state component locale/layout fixture compiles but has not executed. Physical English summary/readback checks and the exact limits are recorded in `tools/testing/lfs-file-state-evidence/README.md`; other LFS copy and human linguistic review remain pending.


Exact-file LFS/regular-Git confirmation now extracts four conditional messages,
with 100 assistant-authored translations across all 25 locales. A separate
`lfs_tracking_files` quantity resource retains the reviewed generic file-count
forms from `discard_review_files`; it does not concatenate an English suffix or
interpolate a count into an unpluralized sentence. The confirmation lists every
frozen selected path with LTR text inside a lazy scrollable body, and both actions
have 48dp minimum height. An authored component fixture covers both choices,
1/2/35 files, long Unicode paths, large text, locale recomposition, stale-review
disabling and confirm/cancel callbacks across 150 states. It compiles during the
Android-test APK build but has not executed. The existing generic ICU plural
fixture also includes this new resource in its generated expectation matrix;
that native fixture has not rerun. No physical/linguistic acceptance is claimed.


The main LFS screen and Git-menu entry now map their remaining 13 static
resources in all 25 locales (325 assistant-authored values). Introductory pointer/
transfer semantics, exact tracking actions, staging preservation, reload,
download, empty states and attention heading keep their independently pinned
English source. All 20 static string resource references across the main screen,
exact confirmation and its entry have mappings; the Git LFS brand stays literal.
Lock-management components and dynamic model notices/errors remain separate
unfinished copy. The existing 150-state confirmation fixture now explicitly
checks the localized apply and cancel labels, but remains unexecuted. Source
coverage is 914/1,058 with 144 extracted English fallbacks and eight plurals;
resource/build validation does not establish physical or linguistic acceptance.


LFS successful-action recovery now has a new all-25-locale message (25 authored
values). If the action returns successfully but the following object/plan read
fails, the model retains its confirmed notice and sets a language-independent
reload-required state. The screen resolves the explanation from current
resources and hides old tracking/download/lock controls; explicit reload obtains
new state. Existing successful-action notices and other model/error copy remain
separate unfinished localization. Coverage is 915/1,059 with 144 extracted
fallbacks and eight plurals. Core/physical behavior and language layout are not
proved by catalog validation alone; focused JVM and build evidence is recorded
in `tools/testing/lfs-followup-recovery-evidence/`.


Successful exact tracking, extension tracking and hydration feedback now retains
`LfsNotice` operation identities and numeric results instead of preformatted
English. Composition resolves three messages and two quantity resources from
current language resources. Seventy-five authored string values and translated
ICU forms cover all 25 locales. English completion copy is unchanged. Existing
lock/endpoint notices use an explicit `Plain` case and remain unfinished copy;
other error localization also stays open. The updated endpoint native assertion
checks that plain cancellation text is preserved. The retained-notice component
fixture is authored for 250 states with different restored-file/downloaded-object
quantities, locale recomposition, large text and RTL; it compiles but has not run.
It does not qualify actual app-language recreation or persistence. Coverage is
918/1,062, with 144 extracted fallbacks and ten plural resources per locale.


The LFS lock confirmation now maps its seven previously embedded title/action/
missing-owner messages and four existing owner/warning/acknowledgement/stale-review
keys in all 25 locales (275 authored values). The shared lock card owner label
and missing-owner value use the same resources. Literal paths, branch refs and
endpoint addresses keep explicit LTR layout within RTL dialogs. Confirmation
still requires a valid, non-busy review and acknowledgement for force release;
server authorization and core logic are unchanged. The authored 125-scenario
native fixture covers all locales with create/release/force choices, provided/
missing owners, acknowledgement, busy/stale gates, cancel and reopened consent.
It has not executed. Other lock-list controls, model/errors, actual language
recreation, physical/accessibility/motion and human linguistic acceptance remain
open. Coverage is 929/1,069 with 140 extracted fallbacks and ten plurals.


The remaining static LFS lock-list controls now map 19 resources across all 25
locales (475 authored values), including verification/read-only modes, ownership,
empty/unsupported states, file-specific action review and routine refresh
notices. Routine check completion retains a typed support status; legacy lock
mutation/endpoint feedback and other errors remain separate unfinished text.
Unsupported servers explicitly show unverified ownership even if verification
was requested. A callback-based list renderer preserves production model
routing and lets the authored 150-case native fixture check read-only access,
owned/forced/create dispatch and busy gates without issuing server requests.
The existing retained-notice fixture expands to 300 states. Both fixtures are
unexecuted; actual language recreation, physical/accessibility/motion and human
linguistic acceptance remain pending. Coverage is 948/1,088, with 140 extracted
fallbacks and ten plurals.


Catalog/picker/empty-state labels add ten mappings per locale (250 authored
values), including the keep-files removal explanation without changing removal
behavior. The GitHub picker uses a new integer quantity resource, replacing its
visible English `repos` suffix; the old extracted string remains unused historical
fixture data. A wrapping account/count component keeps the literal login in LTR
and permits independent count wrapping at large text. Its authored 200-state
native fixture checks all locales, zero/one/two/few/many and maximum-Int counts,
long login preservation and non-overlapping bounds at 240dp/160% text. It compiles
but is unexecuted. Removal copy now scrolls, and its existing actions have 48dp
minimum height. Full-screen layout, removal-language workflow, actual language
recreation, physical accessibility/motion and linguistic acceptance remain open.
Coverage is 958/1,088, 130 extracted fallbacks and eleven plurals per locale.


The active Local repositories discovery/reconnect/history flow adds 475 authored
values across 19 keys and all 25 locales. Thirteen existing resource keys cover
scan/import/history controls and guidance; six newly extracted statuses resolve
retained clean/dirty/unknown-branch and kept/deleted/tracking-removed data at
composition time. Literal branch names use LTR and stay separate from localized
status text. Existing history flag precedence and every operation callback are
unchanged. The authored 200-state component fixture covers all locales, branch
availability, clean/dirty values and history flag combinations at 160% text/RTL;
it compiles but has not executed. Scan warning strings, model errors, destructive
and move-recovery consent, full-screen recreation, physical/accessibility/motion
and human linguistic acceptance remain open. Coverage is 977/1,094 with 117
extracted fallbacks and eleven plurals per locale. Unused legacy repository
dialogs were identified through call-site search and were not presented as an
active production flow or translated in this pass.


Final tracking-removal consent now freezes the reviewed plan and backup choice,
shows its literal LTR path, and disables stale/busy confirmation. Five resources
plus the existing Remove tracking action add 150 authored values in all 25
locales. Backup/permanent-history explanations and stale-review recovery resolve
from current resources. The 50-scenario native consent fixture compiles but has
not run; first-stage/removal/move consent, generic model errors and human
linguistic review remain separate work. The pure request executor has six real-
Git JVM cases covering queued revocation and both actual backup choices; this
does not qualify Android Settings persistence or native interaction. Coverage is
983/1,099 with 116 extracted fallbacks and eleven plurals.


The first tracking-removal review adds 125 authored values across five keys in
all 25 locales. The title, working-file guarantee, backup choice and irreversible
no-backup warning now use translated resources. Metadata size uses the current
context’s Android file-size formatter inside a typed String argument, replacing
the visible raw English bytes suffix; the old extracted resource stays as unused
fixture data. A controlled checkbox row exposes one accessible toggle callback,
48dp targets and busy gating. Continue still captures the current plan/backup in
the frozen final request. The authored 50-scenario native review fixture compiles
but has not run; first/final production navigation, persistence, actual language
recreation, physical/accessibility/motion and human linguistic acceptance remain
open. Native checkbox label assertions target merged control semantics, including
the prior LFS force-release fixture. Coverage is 988/1,100, with 112 extracted
fallbacks and eleven plurals.

The eight Home/Git/Vault plural resources now have 528 authored forms across all
25 locales. Six carry a count argument; the two Vault subtitles deliberately do
not, because the count is a separate UI element. Source/formatter and line-break
checks pass. The existing `LocalizationTest` loops over the complete expected
plural matrix and will include these additions at runtime; those new native
quantity cases have not executed. Human linguistic and layout review remains open.
