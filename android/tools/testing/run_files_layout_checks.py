#!/usr/bin/env python3
"""Check production browser/editor long paths, rotation and actual 160% system text.
Install matching debug/test APKs first. Only owned fixture data is changed.
The protected runner keeps preference recovery inside the app; notifications stay unchanged.
"""
import argparse
import json
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--cleanup-navigation-id', help='Exact owned interrupted fixture UUID; native setup verifies ownership before removal')
parser.add_argument('--device', default='emulator-5554', help='Serial returned for the dedicated GitSync AVD')
options = parser.parse_args()
assert re.fullmatch(r'emulator-[0-9]+', options.device), 'Only an emulator serial is permitted'
ROOT = pathlib.Path(__file__).resolve().parents[2]
ADB = str(pathlib.Path.home() / 'Library/Android/sdk/platform-tools/adb')
BASE = [ADB, '-s', options.device]
AVD = 'gitsync-paid-review-20261026'
assert subprocess.check_output(BASE + ['emu', 'avd', 'name'], text=True, timeout=10).splitlines()[0] == AVD
assert subprocess.check_output(BASE + ['shell', 'getprop', 'ro.build.version.sdk'], text=True, timeout=10).strip() == '35'
free_bytes = shutil.disk_usage(ROOT).free
assert free_bytes >= 1024**3, 'Free at least 1 GiB before running the emulator fixture; preserve private recovery data'
output = pathlib.Path(tempfile.mkdtemp(prefix='syncmd-files-layout-', dir='/tmp'))
print('Files layout-check evidence:', output, flush=True)


def scale():
    values = [line.split('=', 1)[1] for line in subprocess.check_output(
        BASE + ['shell', 'settings', 'list', 'system'], text=True, timeout=10).splitlines() if line.startswith('font_scale=')]
    assert len(values) <= 1
    return values[0] if values else None


before = scale()
assert .5 <= float(before or '1') <= 3
proof = {'before': before, 'mode': 'file-browser-editor-long-paths', 'serial': options.device,
         'avd': AVD, 'host_free_bytes': free_bytes, 'native_passed': False, 'restored': False}
try:
    subprocess.run(BASE + ['shell', 'settings', 'put', 'system', 'font_scale', '1.6'], check=True, timeout=15)
    command = [sys.executable, str(ROOT / 'tools/testing/run_native_regression.py'), '--classes',
               'com.bontecou.syncmd.FileNavigationTest#longPathsKeepBrowserAndEditorUsableAcrossLandscapeRecreation',
               '--device', options.device, '--raw-status', '--timeout', '240']
    if options.cleanup_navigation_id:
        command += ['--cleanup-navigation-id', options.cleanup_navigation_id]
    with (output / 'native.log').open('w') as stream:
        result = subprocess.run(command, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT, timeout=480)
    proof['returncode'] = result.returncode
    assert result.returncode == 0, 'Native check failed: ' + str(output / 'native.log')
    proof['native_passed'] = True
    names = ('files-long-editor-landscape.png', 'files-long-editor-portrait.png', 'files-long-browser-portrait.png',
             'files-long-unsaved-landscape.png', 'files-long-keyboard-landscape.png', 'files-long-editor-landscape-dark.png',
             'files-long-reload-landscape-dark.png', 'files-long-delete-landscape-dark.png')
    for name in names:
        capture = subprocess.check_output(BASE + ['exec-out', 'run-as', 'md.gitsync.md.debug', 'cat',
                                                  'files/parity-evidence/' + name], timeout=20)
        assert capture.startswith(b'\x89PNG')
        (output / name).write_bytes(capture)
except BaseException as error:
    proof['failure_type'] = type(error).__name__
    raise
finally:
    try:
        args = ['settings', 'delete', 'system', 'font_scale'] if before is None else ['settings', 'put', 'system', 'font_scale', before]
        subprocess.run(BASE + ['shell', *args], check=True, stdout=subprocess.DEVNULL, timeout=15)
        proof['after'] = scale()
        proof['restored'] = before == proof['after']
    except BaseException as error:
        proof['restore_error'] = type(error).__name__
    (output / 'proof.json').write_text(json.dumps(proof, indent=2) + '\n')
    assert proof['restored'], 'Original font scale needs recovery; preserve app userdata and inspect ' + str(output / 'proof.json')
print('Files layout check passed:', output / 'proof.json')
