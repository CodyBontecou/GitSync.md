# GitSync.md: agent rules

## QA notes and evidence

Screenshots, Argent receipts and QA write-ups belong in the repo, but each one must have a single home, a status and an end of life.

### Where it goes

- Each piece of QA work gets one folder: `docs/qa/<YYYY-MM-DD>-<pr|issue><number>-<slug>/`, for example `docs/qa/2026-11-15-pr68-dotfiles-index-lock/`. For QA not tied to a single PR or issue, use a slug alone, such as `docs/qa/2026-11-13-issue-relevance/`. The header must still name what the QA covers, so open an issue or PR for QA work rather than committing it straight to main.
- The write-up is that folder's `README.md`. Evidence (screenshots, Argent JSON receipts, short log excerpts saved as `.txt`, e2e YAML) sits next to it, in subfolders if needed.
- Don't add new top-level `docs/*-qa.md` or `docs/*-qa-<YYYYMMDD>.md` files, or files under `docs/evidence/`. Editing, moving out of, or deleting existing legacy files is fine.

### Required header

Each `README.md` starts with this block, before any other content:

```markdown
---
status: active            # active | merged | superseded | closed
covers: [pr#68, issue#65] # PRs and issues this QA verifies; at least one, unless status is closed
tested: <full commit SHA> # the exact head that was tested
updated: YYYY-MM-DD
---
```

- `active`: the PR or issue is still open.
- `merged`: the PR merged after this QA. Add the merge commit to the body.
- `superseded`: later QA replaces this one. Link the replacement.
- `closed`: the PR closed unmerged or the investigation was dropped. The folder is a deletion candidate.

### When to commit it

- Commit QA in the PR it verifies, before that PR merges. Don't leave QA output untracked in a checkout once the session ends.
- When a PR merges or closes, set its QA folder's `status` and `updated` in the same session, or in the next PR that touches it.
- QA written after a merge goes in a small docs PR right away, not into a later unrelated branch.

### What never gets committed

- Log files (`*.log`, `*.log.gz`), including raw CI logs. Record the run ID and result in the README or a small JSON receipt instead, and save any short excerpt you need as `.txt`.
- Any single evidence file over 1 MB. Downscale screenshots (Argent's default 0.25 scale is fine) or crop them.
- Secrets, tokens, signing material, personal data, real user vault content or real repository names. Use the owned fixtures (`syncmd-uitest-fixtures/*`).
- Unfinished recordings (`*.incomplete.*`) and scratch output. Put scratch files under a `scratch/` folder, which is gitignored. The check skips gitignored files, so local scratch output doesn't fail it.

### Cleanup

- A monthly workflow opens a "QA cleanup" issue. It lists folders whose covered PRs and issues have all been closed for over 90 days, plus every `closed` and `superseded` folder.
- Delete listed folders in one docs PR unless a note in the README says why one must stay. Git history keeps them: `git log --all -- docs/qa/<dir>`.
- Keep a folder past 90 days only when it documents an open issue, a release gate or a regression test that someone still runs.

### Enforcement

The `qa-hygiene` CI check (`.github/workflows/qa-hygiene.yml`) enforces the layout, header, size and file-type rules above, and rejects new files in the legacy locations. A folder still marked `active` after everything it covers has closed fails the PRs that touch that folder and is a warning everywhere else. Fix the files; don't weaken the check.

Run it locally before pushing QA:

```sh
python3 scripts/qa_hygiene.py check --base origin/main
```

`.github/workflows/qa-cleanup.yml` runs the monthly cleanup listing. `python3 scripts/qa_hygiene.py cleanup` prints the same list.
