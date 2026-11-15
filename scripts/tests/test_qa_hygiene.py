"""Exercise scripts/qa_hygiene.py against good and bad docs/qa/ folders."""
from pathlib import Path
import contextlib
import importlib.util
import io
import subprocess
import tempfile
import unittest
from unittest import mock

CHECKER = Path(__file__).resolve().parents[1] / 'qa_hygiene.py'
spec = importlib.util.spec_from_file_location('qa_hygiene', CHECKER)
qa = importlib.util.module_from_spec(spec)
spec.loader.exec_module(qa)

SHA = 'f481bec0556856da716bed62f7db3857d73562f0'
GOOD = f'''---
status: active            # active | merged | superseded | closed
covers: [pr#68, issue#65]
tested: {SHA}
updated: 2026-11-15
---

# QA
'''


class Repo:
    def __init__(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.git('init', '-q', '-b', 'main')
        self.git('config', 'user.email', 'qa@example.invalid')
        self.git('config', 'user.name', 'qa')
        self.write('README.md', 'base\n')
        self.write('docs/evidence/old/shot.png', 'x')
        self.commit('base')
        self.git('checkout', '-q', '-b', 'feature')

    def git(self, *args):
        subprocess.run(['git', '-C', str(self.root), *args], check=True, capture_output=True)

    def write(self, rel, content):
        path = self.root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        if isinstance(content, bytes):
            path.write_bytes(content)
        else:
            path.write_text(content)

    def commit(self, msg='change'):
        self.git('add', '-A', '-f')
        self.git('commit', '-q', '-m', msg)

    def run(self, *args, states=None):
        out = io.StringIO()
        patch = mock.patch.object(qa.GitHub, 'state', lambda self, k, n: states[n]) if states else contextlib.nullcontext()
        argv = ['--root', str(self.root)] + ([] if states else ['--offline']) + list(args)
        with patch, contextlib.redirect_stdout(out):
            rc = qa.main(argv)
        return rc, out.getvalue()


def closed(state='merged', day='2026-01-01', is_pr=True):
    return {'state': state, 'closed_at': qa.dt.date.fromisoformat(day), 'is_pr': is_pr}


class CheckTests(unittest.TestCase):
    def setUp(self):
        self.repo = Repo()
        self.addCleanup(self.repo.tmp.cleanup)

    def test_good_folder_passes(self):
        self.repo.write('docs/qa/2026-11-15-pr68-dotfiles/README.md', GOOD)
        self.repo.write('docs/qa/2026-11-15-pr68-dotfiles/shots/a.png', b'\0' * 1000)
        self.repo.commit()
        rc, out = self.repo.run('check', '--base', 'main')
        self.assertEqual(rc, 0, out)

    def test_layout_and_file_rules(self):
        self.repo.write('docs/qa/loose.md', 'x')
        self.repo.write('docs/qa/Bad_Name/README.md', GOOD)
        self.repo.write('docs/qa/2026-11-15-no-readme/a.png', 'x')
        d = 'docs/qa/2026-11-15-files'
        self.repo.write(f'{d}/README.md', GOOD)
        self.repo.write(f'{d}/ci.log', 'x')
        self.repo.write(f'{d}/ci.log.gz', 'x')
        self.repo.write(f'{d}/clip.incomplete.mp4', 'x')
        self.repo.write(f'{d}/scratch/tmp.txt', 'x')
        self.repo.write(f'{d}/AuthKey.p8', 'x')
        self.repo.write(f'{d}/big.png', b'\0' * (qa.MAX_BYTES + 1))
        self.repo.commit()
        rc, out = self.repo.run('check')
        self.assertEqual(rc, 1)
        for needle in ('loose.md: files directly under', 'Bad_Name: folder name', 'no-readme: missing README.md',
                       'ci.log: raw log', 'ci.log.gz: raw log', 'clip.incomplete.mp4: unfinished',
                       'scratch/tmp.txt: scratch/', 'AuthKey.p8: possible signing', 'big.png: 1.0 MB is over'):
            self.assertIn(needle, out)

    def test_header_rules(self):
        cases = {
            'no-header': ('# QA\n', 'must start with a `---` header'),
            'unclosed': ('---\nstatus: active\n', 'not closed'),
            'missing': (f'---\nstatus: active\ntested: {SHA}\n---\n', 'missing `covers`'),
            'bad-status': (GOOD.replace('status: active', 'status: done'), "status 'done' must be one of"),
            'bad-covers': (GOOD.replace('[pr#68, issue#65]', '[#68]'), "covers entry '#68'"),
            'empty-covers': (GOOD.replace('[pr#68, issue#65]', '[]'), 'at least one PR or issue'),
            'short-sha': (GOOD.replace(SHA, SHA[:7]), 'full 40-character'),
            'bad-date': (GOOD.replace('2026-11-15', '15/11/2026'), 'YYYY-MM-DD'),
        }
        for slug, (text, _) in cases.items():
            self.repo.write(f'docs/qa/2026-11-15-{slug}/README.md', text)
        self.repo.write('docs/qa/2026-11-15-closed-ok/README.md',
                        GOOD.replace('status: active', 'status: closed').replace('[pr#68, issue#65]', '[]'))
        self.repo.commit()
        rc, out = self.repo.run('check')
        self.assertEqual(rc, 1)
        for slug, (_, needle) in cases.items():
            self.assertRegex(out, rf'2026-11-15-{slug}/README.md: .*{qa.re.escape(needle)}')
        self.assertNotIn('closed-ok', out)

    def test_new_legacy_files_fail_against_base(self):
        self.repo.write('docs/evidence/old/new.png', 'x')
        self.repo.write('docs/thing-qa.md', 'x')
        self.repo.write('docs/evidence/old/shot.png', 'modified')  # edits to existing legacy files are fine
        self.repo.commit()
        rc, out = self.repo.run('check', '--base', 'main')
        self.assertEqual(rc, 1)
        self.assertIn('docs/evidence/old/new.png: new file in legacy', out)
        self.assertIn('docs/thing-qa.md: new file in top-level', out)
        self.assertNotIn('shot.png', out)

    def test_moving_legacy_files_out_passes(self):
        self.repo.write('docs/qa/2026-11-15-pr68-x/README.md', GOOD)
        self.repo.git('mv', 'docs/evidence/old/shot.png', 'docs/qa/2026-11-15-pr68-x/shot.png')
        self.repo.commit()
        rc, out = self.repo.run('check', '--base', 'main')
        self.assertEqual(rc, 0, out)

    def test_stale_active(self):
        self.repo.write('docs/qa/2026-11-15-untouched/README.md', GOOD)
        self.repo.commit('on main')
        self.repo.git('checkout', '-q', 'main')
        self.repo.git('merge', '-q', 'feature')
        self.repo.git('checkout', '-q', 'feature')
        self.repo.write('docs/qa/2026-11-15-touched/README.md', GOOD)
        self.repo.write('docs/qa/2026-11-15-open/README.md', GOOD.replace('pr#68', 'pr#70'))
        self.repo.commit()
        states = {68: closed(), 65: closed('closed', is_pr=False), 70: {'state': 'open', 'closed_at': None, 'is_pr': True}}
        rc, out = self.repo.run('check', '--base', 'main', states=states)
        self.assertEqual(rc, 1)
        self.assertIn('ERROR: docs/qa/2026-11-15-touched/README.md: status is active', out)
        self.assertIn('WARNING: docs/qa/2026-11-15-untouched/README.md: status is active', out)
        self.assertNotIn('2026-11-15-open', out)

    def test_github_unavailable_degrades_to_warning(self):
        self.repo.write('docs/qa/2026-11-15-pr68-x/README.md', GOOD)
        self.repo.commit()
        failed = subprocess.CompletedProcess([], 1, '', 'gh: To use GitHub CLI, please run: gh auth login')
        with mock.patch.object(qa.subprocess, 'run', side_effect=lambda cmd, **kw: failed if cmd[0] == 'gh' else subprocess.run(cmd, **kw)):
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                rc = qa.main(['--root', str(self.repo.root), 'check'])
        self.assertEqual(rc, 0, out.getvalue())
        self.assertIn('WARNING: docs/qa: skipping PR/issue state checks', out.getvalue())


class CleanupTests(unittest.TestCase):
    def test_lists_candidates(self):
        repo = Repo()
        self.addCleanup(repo.tmp.cleanup)
        repo.write('docs/qa/2026-01-01-old/README.md', GOOD.replace('status: active', 'status: merged'))
        repo.write('docs/qa/2026-11-01-recent/README.md', GOOD.replace('pr#68', 'pr#69'))
        repo.write('docs/qa/2026-11-02-dropped/README.md',
                   GOOD.replace('status: active', 'status: closed').replace('[pr#68, issue#65]', '[]'))
        repo.write('docs/qa/2026-11-03-replaced/README.md', GOOD.replace('status: active', 'status: superseded'))
        repo.commit()
        today = qa.dt.date.today()
        recent = (today - qa.dt.timedelta(days=10)).isoformat()
        old = (today - qa.dt.timedelta(days=120)).isoformat()
        states = {68: closed(day=old), 65: closed('closed', day=old, is_pr=False), 69: closed(day=recent)}
        rc, out = repo.run('cleanup', states=states)
        self.assertEqual(rc, 0)
        self.assertRegex(out, r'`docs/qa/2026-01-01-old` \| pr#68, issue#65 \| all covered refs closed for 120 days')
        self.assertIn('`docs/qa/2026-11-02-dropped` | — | status: closed', out)
        self.assertIn('`docs/qa/2026-11-03-replaced` | pr#68, issue#65 | status: superseded', out)
        self.assertNotIn('recent`', out)
        self.assertIn('`docs/evidence/old`', out)


if __name__ == '__main__':
    unittest.main()
