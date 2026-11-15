#!/usr/bin/env python3
"""Enforce the QA notes and evidence rules in CLAUDE.md.

  qa_hygiene.py check   [--base REF] [--offline]   fail on layout, header, size, file-type and stale-status problems
  qa_hygiene.py cleanup [--days 90] [--offline]    print the "QA cleanup" issue body (Markdown) to stdout

GitHub lookups (PR and issue state) use the `gh` CLI. If `gh` is missing, not
authenticated or the API fails, those checks are skipped with a warning; the
offline checks still run.
"""
import argparse
import datetime as dt
import json
import os
import re
import subprocess
import sys
from pathlib import Path

QA_DIR = 'docs/qa'
MAX_BYTES = 1024 * 1024
STATUSES = ('active', 'merged', 'superseded', 'closed')
HEADER_KEYS = ('status', 'covers', 'tested', 'updated')
FOLDER_RE = re.compile(r'^\d{4}-\d{2}-\d{2}-[a-z0-9]+(?:-[a-z0-9]+)*$')
REF_RE = re.compile(r'^(pr|issue)#([1-9]\d*)$')
SHA_RE = re.compile(r'^[0-9a-f]{40}$')
FORBIDDEN_NAME_RES = (
    (re.compile(r'\.log(\.gz)?$', re.I), 'raw log file; record the CI run ID and result in the README instead'),
    (re.compile(r'\.incomplete\.', re.I), 'unfinished recording'),
    (re.compile(r'\.(p8|p12|pem|key|keystore|jks|mobileprovision|env)$', re.I), 'possible signing material or secret'),
)
LEGACY_RES = (
    (re.compile(r'^docs/evidence/'), 'legacy docs/evidence/ folder'),
    (re.compile(r'^docs/[^/]+-qa\.md$'), 'top-level docs/*-qa.md file'),
)


class Report:
    def __init__(self):
        self.errors = []
        self.warnings = []

    def error(self, path, msg):
        self.errors.append((path, msg))

    def warn(self, path, msg):
        self.warnings.append((path, msg))

    def emit(self):
        gha = os.environ.get('GITHUB_ACTIONS') == 'true'
        for kind, items in (('warning', self.warnings), ('error', self.errors)):
            for path, msg in items:
                if gha:
                    print(f'::{kind} file={path}::{msg}')
                else:
                    print(f'{kind.upper()}: {path}: {msg}')


# ---------------------------------------------------------------- header

def parse_header(text):
    """Return (fields, problem). fields maps key -> raw string value."""
    lines = text.splitlines()
    if not lines or lines[0].strip() != '---':
        return None, 'README.md must start with a `---` header block'
    fields = {}
    for line in lines[1:]:
        if line.strip() == '---':
            return fields, None
        line = re.sub(r'\s+#.*$', '', line).strip()  # inline comments need a space before '#'
        if not line:
            continue
        key, sep, value = line.partition(':')
        if not sep:
            return None, f'header line is not `key: value`: {line!r}'
        fields[key.strip()] = value.strip()
    return None, 'header block is not closed with `---`'


def parse_covers(raw):
    raw = raw.strip()
    if not (raw.startswith('[') and raw.endswith(']')):
        return None, 'covers must be a list, e.g. [pr#68, issue#65]'
    items = [i.strip() for i in raw[1:-1].split(',') if i.strip()]
    refs = []
    for item in items:
        m = REF_RE.match(item)
        if not m:
            return None, f'covers entry {item!r} must look like pr#68 or issue#65'
        refs.append((m.group(1), int(m.group(2))))
    return refs, None


def validate_header(fields, rel, report, today):
    for key in HEADER_KEYS:
        if key not in fields:
            report.error(rel, f'header is missing `{key}`')
    status = fields.get('status')
    if status is not None and status not in STATUSES:
        report.error(rel, f'status {status!r} must be one of {", ".join(STATUSES)}')
    refs = None
    if 'covers' in fields:
        refs, problem = parse_covers(fields['covers'])
        if problem:
            report.error(rel, problem)
        elif not refs and status != 'closed':
            report.error(rel, 'covers must list at least one PR or issue unless status is closed')
    tested = fields.get('tested')
    if tested is not None and not SHA_RE.match(tested):
        report.error(rel, 'tested must be a full 40-character lowercase commit SHA')
    updated = fields.get('updated')
    if updated is not None:
        try:
            day = dt.date.fromisoformat(updated)
            if day > today + dt.timedelta(days=1):
                report.error(rel, f'updated {updated} is in the future')
        except ValueError:
            report.error(rel, f'updated {updated!r} must be a YYYY-MM-DD date')
    return status, refs


# ---------------------------------------------------------------- GitHub

class GitHub:
    """Looks up PR/issue state with `gh api`. Disables itself on the first failure."""

    def __init__(self, offline, report):
        self.available = not offline
        self.report = report
        self.cache = {}
        self.reason = 'offline mode' if offline else None
        self.repo = os.environ.get('GITHUB_REPOSITORY') or '{owner}/{repo}'

    def state(self, kind, number):
        """Return dict(state='open'|'merged'|'closed', closed_at=date|None, is_pr=bool) or None."""
        if not self.available:
            return None
        if number in self.cache:
            return self.cache[number]
        try:
            out = subprocess.run(
                ['gh', 'api', f'repos/{self.repo}/issues/{number}'],
                capture_output=True, text=True, timeout=30)
        except (OSError, subprocess.TimeoutExpired) as exc:
            return self._disable(f'gh unavailable ({exc})')
        if out.returncode != 0:
            msg = (out.stderr or out.stdout).strip().splitlines()
            msg = msg[-1] if msg else f'exit {out.returncode}'
            if 'Not Found' in msg or '404' in msg:
                self.cache[number] = {'state': 'missing', 'closed_at': None, 'is_pr': kind == 'pr'}
                return self.cache[number]
            return self._disable(f'GitHub API failed ({msg})')
        data = json.loads(out.stdout)
        pr = data.get('pull_request')
        merged_at = pr.get('merged_at') if pr else None
        if data.get('state') == 'open':
            state = 'open'
        else:
            state = 'merged' if merged_at else 'closed'
        stamp = merged_at or data.get('closed_at')
        result = {
            'state': state,
            'closed_at': dt.date.fromisoformat(stamp[:10]) if stamp else None,
            'is_pr': pr is not None,
        }
        self.cache[number] = result
        return result

    def _disable(self, reason):
        self.available = False
        self.reason = reason
        self.report.warn(QA_DIR, f'skipping PR/issue state checks: {reason}')
        return None


# ---------------------------------------------------------------- scanning

def git(root, *args):
    out = subprocess.run(['git', '-C', str(root), *args], capture_output=True, text=True)
    if out.returncode != 0:
        raise RuntimeError(f'git {" ".join(args)} failed: {out.stderr.strip()}')
    return out.stdout


def qa_folders(root):
    base = root / QA_DIR
    if not base.is_dir():
        return []
    return sorted(p for p in base.iterdir() if p.is_dir())


def folder_files(folder):
    return sorted(p for p in folder.rglob('*') if p.is_file())


def read_folder(folder, root, report, today):
    """Validate one docs/qa/<dir>/ and return (status, refs) or (None, None)."""
    rel = folder.relative_to(root).as_posix()
    if not FOLDER_RE.match(folder.name):
        report.error(rel, 'folder name must be <YYYY-MM-DD>-<slug>, lowercase, e.g. 2026-11-15-pr68-dotfiles-index-lock')
    for path in folder_files(folder):
        prel = path.relative_to(root).as_posix()
        if 'scratch' in path.relative_to(folder).parts[:-1]:
            report.error(prel, 'scratch/ output must not be committed')
        for regex, why in FORBIDDEN_NAME_RES:
            if regex.search(path.name):
                report.error(prel, why)
        size = path.stat().st_size
        if size > MAX_BYTES:
            report.error(prel, f'{size / MAX_BYTES:.1f} MB is over the 1 MB limit; downscale or crop it')
    readme = folder / 'README.md'
    if not readme.is_file():
        report.error(rel, 'missing README.md')
        return None, None
    rrel = readme.relative_to(root).as_posix()
    fields, problem = parse_header(readme.read_text(encoding='utf-8', errors='replace'))
    if problem:
        report.error(rrel, problem)
        return None, None
    return validate_header(fields, rrel, report, today)


def changed_paths(root, base, filters):
    out = git(root, 'diff', '--name-only', f'--diff-filter={filters}', '-M', f'{base}...HEAD')
    return [line for line in out.splitlines() if line]


def check(args):
    root = Path(args.root).resolve()
    today = dt.date.today()
    report = Report()
    gh = GitHub(args.offline, report)

    touched = set()
    if args.base:
        try:
            for path in changed_paths(root, args.base, 'ACR'):
                for regex, why in LEGACY_RES:
                    if regex.search(path):
                        report.error(path, f'new file in {why}; put QA under {QA_DIR}/<YYYY-MM-DD>-<ref>-<slug>/')
            touched = {p for p in changed_paths(root, args.base, 'ACMRD') if p.startswith(QA_DIR + '/')}
        except RuntimeError as exc:
            report.error('.', str(exc))

    base = root / QA_DIR
    if base.is_dir():
        for path in sorted(base.iterdir()):
            if path.is_file():
                report.error(path.relative_to(root).as_posix(), f'files directly under {QA_DIR}/ are not allowed; use a folder')

    for folder in qa_folders(root):
        rel = folder.relative_to(root).as_posix()
        status, refs = read_folder(folder, root, report, today)
        if status != 'active' or not refs:
            continue
        states = [gh.state(kind, n) for kind, n in refs]
        if any(s is None for s in states):
            continue
        for (kind, n), s in zip(refs, states):
            if s['state'] == 'missing':
                report.error(f'{rel}/README.md', f'covers {kind}#{n}, which does not exist')
            elif (kind == 'pr') != s['is_pr']:
                report.warn(f'{rel}/README.md', f'{kind}#{n} is actually {"a PR" if s["is_pr"] else "an issue"}')
        if all(s['state'] in ('merged', 'closed') for s in states):
            summary = ', '.join(f'{k}#{n} {s["state"]}' for (k, n), s in zip(refs, states))
            msg = f'status is active but everything it covers is closed ({summary}); set status to merged or closed'
            # The rule allows fixing this "in the next PR that touches it", so only
            # a PR that touches the folder (or a full run without --base) fails.
            if not args.base or any(p.startswith(rel + '/') for p in touched):
                report.error(f'{rel}/README.md', msg)
            else:
                report.warn(f'{rel}/README.md', msg)

    report.emit()
    n = len(qa_folders(root))
    print(f'qa-hygiene: {n} QA folder(s), {len(report.errors)} error(s), {len(report.warnings)} warning(s)')
    return 1 if report.errors else 0


# ---------------------------------------------------------------- cleanup

def human(size):
    for unit in ('B', 'KB', 'MB', 'GB'):
        if size < 1024 or unit == 'GB':
            return f'{size:.0f} {unit}' if unit == 'B' else f'{size:.1f} {unit}'
        size /= 1024


def tree_size(path):
    if path.is_file():
        return path.stat().st_size
    return sum(p.stat().st_size for p in path.rglob('*') if p.is_file())


def cleanup(args):
    root = Path(args.root).resolve()
    today = dt.date.today()
    report = Report()
    gh = GitHub(args.offline, report)
    rows = []
    unknown = []
    for folder in qa_folders(root):
        rel = folder.relative_to(root).as_posix()
        status, refs = read_folder(folder, root, report, today)
        reason = None
        if status in ('closed', 'superseded'):
            reason = f'status: {status}'
        elif refs:
            states = [gh.state(k, n) for k, n in refs]
            if any(s is None for s in states):
                unknown.append(rel)
            elif all(s['state'] in ('merged', 'closed') and s['closed_at'] for s in states):
                last = max(s['closed_at'] for s in states)
                age = (today - last).days
                if age > args.days:
                    reason = f'all covered refs closed for {age} days (last {last}, status: {status})'
        if reason:
            covers = ', '.join(f'{k}#{n}' for k, n in refs or [])
            rows.append((rel, covers or '—', reason, tree_size(folder)))

    legacy = []
    docs = root / 'docs'
    if (docs / 'evidence').is_dir():
        legacy += sorted(p for p in (docs / 'evidence').iterdir())
    if docs.is_dir():
        legacy += sorted(p for p in docs.glob('*-qa.md'))
    repo = os.environ.get('GITHUB_REPOSITORY')
    rules = f'[CLAUDE.md](https://github.com/{repo}/blob/main/CLAUDE.md#cleanup)' if repo else 'CLAUDE.md'

    out = [
        '<!-- generated by scripts/qa_hygiene.py cleanup; edits here are overwritten -->',
        f'QA folders that are deletion candidates under the rules in {rules}, as of {today}.',
        '',
        'Delete the listed folders in one docs PR unless a note in a folder\'s README says why it must stay. '
        'Git history keeps them: `git log --all -- docs/qa/<dir>`. This workflow never deletes anything.',
        '',
    ]
    if rows:
        out += ['| Folder | Covers | Why | Size |', '| --- | --- | --- | ---: |']
        out += [f'| `{r}` | {c} | {why} | {human(s)} |' for r, c, why, s in rows]
        out += ['', f'Total: {len(rows)} folder(s), {human(sum(r[3] for r in rows))}.']
    else:
        out.append('No `docs/qa/` folders are deletion candidates.')
    if unknown:
        out += ['', f'Could not check PR/issue state ({gh.reason}) for: ' + ', '.join(f'`{u}`' for u in unknown)]
    if legacy:
        out += ['', '### Legacy layout still to migrate', '',
                'These predate `docs/qa/`. Migrate or delete them; new files here fail the `qa-hygiene` check.', '',
                '| Path | Size |', '| --- | ---: |']
        out += [f'| `{p.relative_to(root).as_posix()}` | {human(tree_size(p))} |' for p in legacy]
    if report.errors:
        out += ['', '### Folders that fail the hygiene check', '']
        out += [f'- `{p}`: {m}' for p, m in report.errors]
    print('\n'.join(out))
    for path, msg in report.warnings:
        print(f'WARNING: {path}: {msg}', file=sys.stderr)
    # Exit 3 tells the workflow there is nothing to report.
    return 0 if (rows or legacy or report.errors) else 3


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--root', default='.', help='repository root (default: .)')
    parser.add_argument('--offline', action='store_true', help='skip GitHub PR/issue lookups')
    sub = parser.add_subparsers(dest='cmd', required=True)
    c = sub.add_parser('check')
    c.add_argument('--base', help='base ref to diff against, e.g. origin/main (enables new-legacy-file checks)')
    k = sub.add_parser('cleanup')
    k.add_argument('--days', type=int, default=90)
    args = parser.parse_args(argv)
    return check(args) if args.cmd == 'check' else cleanup(args)


if __name__ == '__main__':
    sys.exit(main())
