# GitSync.md: agent rules

The rules for every coding agent (Claude Code, Codex, pi and others) live in [CLAUDE.md](CLAUDE.md). Read it before you start, and follow it as if it were this file.

In short, for QA notes and evidence: one folder per piece of QA under `docs/qa/<YYYY-MM-DD>-<pr|issue><number>-<slug>/`, a `README.md` with the `status`/`covers`/`tested`/`updated` header, no raw logs, no file over 1 MB, nothing private, committed in the PR it verifies. Check with `python3 scripts/qa_hygiene.py check --base origin/main`.
