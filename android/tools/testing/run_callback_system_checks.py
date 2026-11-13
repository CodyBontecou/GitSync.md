#!/usr/bin/env python3
"""Exercise a busy callback at actual 160% system text and restore the original scale.
Install matching debug/test APKs first. Notification permission is left unchanged;
the selected test checks the real notification return when permission is granted.
"""
import json
import pathlib
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
ADB = str(pathlib.Path.home() / 'Library/Android/sdk/platform-tools/adb')
BASE = [ADB, '-s', 'emulator-5554']
assert subprocess.check_output(BASE + ['emu', 'avd', 'name'], text=True).splitlines()[0] == 'gitsync-paid-review-20261026'
output = pathlib.Path(tempfile.mkdtemp(prefix='syncmd-callback-system-', dir='/tmp'))
print('Callback system-check evidence:', output, flush=True)

def scale():
    values = [line.split('=', 1)[1] for line in subprocess.check_output(BASE + ['shell', 'settings', 'list', 'system'], text=True).splitlines() if line.startswith('font_scale=')]
    assert len(values) <= 1
    return values[0] if values else None

before = scale()
assert .5 <= float(before or '1') <= 3
proof = {'before': before}
try:
    subprocess.run(BASE + ['shell', 'settings', 'put', 'system', 'font_scale', '1.6'], check=True)
    with (output / 'native.log').open('w') as stream:
        result = subprocess.run([sys.executable, str(ROOT / 'tools/testing/run_native_regression.py'), '--classes',
            'com.bontecou.syncmd.RepositoryCallbackTransportTest#busyBackgroundCallbackReturnsItsOwnErrorWithoutDisturbingTheRunningAction',
            '--raw-status', '--timeout', '180'], cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT, timeout=300)
    proof['returncode'] = result.returncode
    assert result.returncode == 0, 'Native check failed: ' + str(output / 'native.log')
    capture = subprocess.check_output(BASE + ['exec-out', 'run-as', 'com.bontecou.syncmd.debug', 'cat', 'files/parity-evidence/callback-background-busy.png'])
    assert capture.startswith(b'\x89PNG')
    (output / 'callback-background-busy.png').write_bytes(capture)
finally:
    args = ['settings', 'delete', 'system', 'font_scale'] if before is None else ['settings', 'put', 'system', 'font_scale', before]
    subprocess.run(BASE + ['shell', *args], check=True, stdout=subprocess.DEVNULL)
    proof['after'] = scale()
    proof['restored'] = before == proof['after']
    (output / 'proof.json').write_text(json.dumps(proof, indent=2) + '\n')
    assert proof['restored'], 'Original font scale needs recovery'
print('Callback system check passed:', output / 'proof.json')
