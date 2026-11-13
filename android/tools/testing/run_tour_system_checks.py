#!/usr/bin/env python3
"""Exercise all 25 tour languages at actual 160% system text and restore the original scale.
Install matching debug/test APKs first. All four production pages, setup choices and app-locale recreation are checked.
Notification permission is left unchanged.
"""
import argparse
import json
import pathlib
import subprocess
import sys
import tempfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--clone-entry",action="store_true",help="Check localized URL setup, destination title and completed-tour Back behavior")
options = parser.parse_args()
ROOT = pathlib.Path(__file__).resolve().parents[2]
ADB = str(pathlib.Path.home() / 'Library/Android/sdk/platform-tools/adb')
BASE = [ADB, '-s', 'emulator-5554']
assert subprocess.check_output(BASE + ['emu', 'avd', 'name'], text=True).splitlines()[0] == 'gitsync-paid-review-20261026'
output = pathlib.Path(tempfile.mkdtemp(prefix='syncmd-tour-system-', dir='/tmp'))
print('Tour system-check evidence:', output, flush=True)

def scale():
    values = [line.split('=', 1)[1] for line in subprocess.check_output(BASE + ['shell', 'settings', 'list', 'system'], text=True).splitlines() if line.startswith('font_scale=')]
    assert len(values) <= 1
    return values[0] if values else None

before = scale()
assert .5 <= float(before or '1') <= 3
proof = {'before': before, 'mode': 'clone-entry' if options.clone_entry else 'tour-pages'}
try:
    subprocess.run(BASE + ['shell', 'settings', 'put', 'system', 'font_scale', '1.6'], check=True)
    with (output / 'native.log').open('w') as stream:
        result = subprocess.run([sys.executable, str(ROOT / 'tools/testing/run_native_regression.py'), '--classes',
            'com.bontecou.syncmd.AppTourLanguageTest#' + ('localizedCloneChoiceOpensReadableUrlFormAndBackCannotReenterCompletedTour' if options.clone_entry else 'actualLanguageChangesPreserveTourProgressAndKeepAllPagesAndSetupChoicesReadable'),
            '--raw-status', '--timeout', '900'], cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT, timeout=1000)
    proof['returncode'] = result.returncode
    assert result.returncode == 0, 'Native check failed: ' + str(output / 'native.log')
    names = ('tour-clone-ar.png', 'tour-clone-fi.png', 'tour-clone-ja.png') if options.clone_entry else ('tour-setup-ar.png', 'tour-setup-de.png', 'tour-setup-ja.png', 'tour-header-fi.png')
    for name in names:
        capture = subprocess.check_output(BASE + ['exec-out', 'run-as', 'com.bontecou.syncmd.debug', 'cat', 'files/parity-evidence/' + name])
        assert capture.startswith(b'\x89PNG')
        (output / name).write_bytes(capture)

finally:
    args = ['settings', 'delete', 'system', 'font_scale'] if before is None else ['settings', 'put', 'system', 'font_scale', before]
    subprocess.run(BASE + ['shell', *args], check=True, stdout=subprocess.DEVNULL)
    proof['after'] = scale()
    proof['restored'] = before == proof['after']
    (output / 'proof.json').write_text(json.dumps(proof, indent=2) + '\n')
    assert proof['restored'], 'Original font scale needs recovery'
print('Tour system check passed:', output / 'proof.json')
