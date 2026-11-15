"""Disposable synthetic Git oracle for the guide; never accesses an existing vault."""
import json
import subprocess
import tempfile
from pathlib import Path

with tempfile.TemporaryDirectory(prefix='pr59-git-sides-') as directory:
    root = Path(directory)
    def git(*args, expected=0):
        result = subprocess.run(['git', '-C', directory, *args], text=True, capture_output=True)
        assert result.returncode == expected, (args, result.stderr, result.stdout)
        return result.stdout
    git('init', '-b', 'main')
    git('config', 'user.name', 'Synthetic QA')
    git('config', 'user.email', 'fixture@example.invalid')
    note = root / 'note.md'
    note.write_text('base\n')
    git('add', 'note.md'); git('commit', '-m', 'synthetic base')
    git('checkout', '-b', 'phone')
    note.write_text('phone edit\n')
    git('commit', '-am', 'synthetic phone edit')
    git('checkout', '-b', 'remote', 'main')
    note.write_text('remote edit\n')
    git('commit', '-am', 'synthetic remote edit')
    git('checkout', 'phone')
    git('merge', '--no-edit', 'remote', expected=1)
    merge = {'ours': git('show', ':2:note.md'), 'theirs': git('show', ':3:note.md')}
    assert merge == {'ours': 'phone edit\n', 'theirs': 'remote edit\n'}, merge
    git('merge', '--abort')
    git('rebase', 'remote', expected=1)
    rebase = {'ours': git('show', ':2:note.md'), 'theirs': git('show', ':3:note.md')}
    assert rebase == {'ours': 'remote edit\n', 'theirs': 'phone edit\n'}, rebase
    git('rebase', '--abort')
    print(json.dumps({'result': 'PASS', 'gitVersion': git('--version').strip(), 'normalMerge': merge, 'rebase': rebase}, indent=2))
