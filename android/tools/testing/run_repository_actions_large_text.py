#!/usr/bin/env python3
"""Run repository action/shortcut UI checks at actual 160% system text on an owned emulator.

Matching debug/test APKs must already be installed. Private baseline recovery
stays inside the app; the original font setting is restored in finally.
"""
import argparse
import json
import pathlib
import re
import subprocess
import sys
import tempfile
from device_identity import OWNED_AVDS, require_owned_emulator

ROOT = pathlib.Path(__file__).resolve().parents[2]
ADB = str(pathlib.Path.home() / 'Library/Android/sdk/platform-tools/adb')
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--device', required=True)
parser.add_argument('--avd', required=True, choices=sorted(OWNED_AVDS))
options = parser.parse_args()
base = require_owned_emulator(ADB, options.device, options.avd)
package = 'com.bontecou.syncmd.debug'
output = pathlib.Path(tempfile.mkdtemp(prefix='syncmd-action-large-text-', dir='/tmp'))
print('Repository action large-text evidence:', output, flush=True)

def read(*args):
    return subprocess.check_output(base + list(args), text=True, timeout=30).strip()

def font():
    rows = [line.split('=', 1)[1] for line in read('shell', 'settings', 'list', 'system').splitlines() if line.startswith('font_scale=')]
    assert len(rows) <= 1
    return rows[0] if rows else None

before = font()
assert .5 <= float(before or '1') <= 3
proof = {'device': options.device, 'avd': options.avd, 'before_font': before, 'expected_tests': 19, 'requested_font': '1.6'}
(output / 'before.json').write_text(json.dumps(proof, indent=2) + '\n')
try:
    subprocess.run(base + ['shell', 'settings', 'put', 'system', 'font_scale', '1.6'], check=True, timeout=30)
    with (output / 'native.log').open('w') as stream:
        process = subprocess.Popen([sys.executable, str(ROOT / 'tools/testing/run_native_regression.py'),
            '--device', options.device, '--avd', options.avd, '--classes', 'com.bontecou.syncmd.RepositoryActionsScreenTest',
            '--raw-status', '--timeout', '600'], cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT)
        try:
            code = process.wait(timeout=680)
        except subprocess.TimeoutExpired:
            print('Same protected native process remains live; retaining its handle.', flush=True)
            code = process.wait()
    proof['returncode'] = code
    driver = (output / 'native.log').read_text()
    folder = pathlib.Path(re.search(r'Logs: (/tmp/syncmd-protected-native-\S+)', driver)[1])
    log = (folder / 'regression.log').read_text()
    proof['native_log'] = str(folder / 'regression.log')
    assert code == 0 and re.search(r'^OK \(19 tests\)\s*$', log, re.M), proof['native_log']
    assert not re.search(r'^INSTRUMENTATION_STATUS_CODE: -\d+', log, re.M), proof['native_log']
    assert 'Private baseline restored and removed;' in driver
    proof['passed'] = True
    for name in ['repository-shortcut-consent-dark-xl.png', 'shortcut-background-consent-dark-xl.png']:
        data = subprocess.check_output(base + ['exec-out', 'run-as', package, 'cat', 'files/parity-evidence/' + name], timeout=30)
        assert data.startswith(b'\x89PNG'), name
        (output / name).write_bytes(data)
finally:
    args = ['settings', 'delete', 'system', 'font_scale'] if before is None else ['settings', 'put', 'system', 'font_scale', before]
    subprocess.run(base + ['shell', *args], check=True, timeout=30, stdout=subprocess.DEVNULL)
    proof['after_font'] = font()
    proof['restored'] = before == proof['after_font']
    for name in [package, package + '.test']:
        subprocess.run(base + ['shell', 'am', 'force-stop', name], check=True, timeout=15)
    (output / 'proof.json').write_text(json.dumps(proof, indent=2) + '\n')
    assert proof['restored'], 'Original font setting needs recovery'
print('Repository actions large-text check passed:', output / 'proof.json')
