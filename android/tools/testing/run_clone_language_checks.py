#!/usr/bin/env python3
"""Exercise clone authentication, SSH, folders and saved scopes in all 25 languages at actual 160% system text and restore the original scale.
Install matching debug/test APKs first. All three production form configurations are checked; transport is forbidden.
Notification permission is left unchanged.
"""
import argparse
import json
import pathlib
import subprocess
import sys
import tempfile

parser = argparse.ArgumentParser(description=__doc__)

options = parser.parse_args()
ROOT = pathlib.Path(__file__).resolve().parents[2]
ADB = str(pathlib.Path.home() / 'Library/Android/sdk/platform-tools/adb')
BASE = [ADB, '-s', 'emulator-5554']
assert subprocess.check_output(BASE + ['emu', 'avd', 'name'], text=True).splitlines()[0] == 'gitsync-paid-review-20261026'
assert subprocess.check_output(BASE + ['shell', 'getprop', 'ro.build.version.sdk'], text=True).strip() == '35'
output = pathlib.Path(tempfile.mkdtemp(prefix='syncmd-clone-language-', dir='/tmp'))
print('Clone language-check evidence:', output, flush=True)

def scale():
    values = [line.split('=', 1)[1] for line in subprocess.check_output(BASE + ['shell', 'settings', 'list', 'system'], text=True).splitlines() if line.startswith('font_scale=')]
    assert len(values) <= 1
    return values[0] if values else None

before = scale()
assert .5 <= float(before or '1') <= 3
proof = {'before': before, 'mode': 'clone-authentication-folder-scope'}
try:
    subprocess.run(BASE + ['shell', 'settings', 'put', 'system', 'font_scale', '1.6'], check=True)
    with (output / 'native.log').open('w') as stream:
        result = subprocess.run([sys.executable, str(ROOT / 'tools/testing/run_native_regression.py'), '--classes',
            'com.bontecou.syncmd.CloneLanguageTest#allLocalesRenderCloneAuthenticationFolderConsentAndSavedConnectionScopesWithoutTransport',
            '--raw-status', '--timeout', '900'], cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT, timeout=1000)
    proof['returncode'] = result.returncode
    assert result.returncode == 0, 'Native check failed: ' + str(output / 'native.log')
    names = tuple('clone-' + state + '-' + tag + '.png' for state in ('consent', 'ssh') for tag in ('ar', 'de', 'ja'))
    for name in names:
        capture = subprocess.check_output(BASE + ['exec-out', 'run-as', 'md.gitsync.md.debug', 'cat', 'files/parity-evidence/' + name])
        assert capture.startswith(b'\x89PNG')
        (output / name).write_bytes(capture)

finally:
    args = ['settings', 'delete', 'system', 'font_scale'] if before is None else ['settings', 'put', 'system', 'font_scale', before]
    subprocess.run(BASE + ['shell', *args], check=True, stdout=subprocess.DEVNULL)
    proof['after'] = scale()
    proof['restored'] = before == proof['after']
    (output / 'proof.json').write_text(json.dumps(proof, indent=2) + '\n')
    assert proof['restored'], 'Original font scale needs recovery'
print('Clone language check passed:', output / 'proof.json')
