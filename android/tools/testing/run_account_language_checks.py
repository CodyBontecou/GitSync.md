#!/usr/bin/env python3
"""Check production account/author screens in 25 real app locales at 160% system text.

Install matching debug/test APKs first. Uses only the dedicated API 35 GitSync AVD,
keeps the encrypted baseline inside the app and restores the original font setting.
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
parser.add_argument('--device', default='emulator-5556')
options = parser.parse_args()
assert re.fullmatch(r'emulator-\d+', options.device), 'An emulator serial is required'
ROOT = pathlib.Path(__file__).resolve().parents[2]
BASE = [str(pathlib.Path.home() / 'Library/Android/sdk/platform-tools/adb'), '-s', options.device]
def read(*arguments):
    return subprocess.check_output(BASE + list(arguments), text=True, timeout=30).strip()
assert read('emu', 'avd', 'name').splitlines()[0] == 'gitsync-paid-review-20261026'
assert read('shell', 'getprop', 'ro.build.version.sdk') == '35'
assert shutil.disk_usage(ROOT).free >= 1024 ** 3, 'Free at least 1 GiB before device testing'
output = pathlib.Path(tempfile.mkdtemp(prefix='syncmd-account-language-', dir='/tmp'))
print('Account language evidence:', output, flush=True)
def scale():
    values = [line.split('=', 1)[1] for line in read('shell', 'settings', 'list', 'system').splitlines() if line.startswith('font_scale=')]
    assert len(values) <= 1
    return values[0] if values else None
before = scale()
assert .5 <= float(before or '1') <= 3
proof = {'device': options.device, 'before': before, 'mode': 'account-author-locales', 'expected_locales': 25, 'states_per_locale': 4}
try:
    subprocess.run(BASE + ['shell', 'settings', 'put', 'system', 'font_scale', '1.6'], check=True, timeout=30)
    with (output / 'native.log').open('w') as stream:
        result = subprocess.run([sys.executable, str(ROOT / 'tools/testing/run_native_regression.py'), '--device', options.device, '--classes',
            'com.bontecou.syncmd.AccountLanguageTest', '--raw-status', '--timeout', '900'], cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT, timeout=1000)
    proof['returncode'] = result.returncode
    assert result.returncode == 0, 'Native check failed: ' + str(output / 'native.log')
    log = (output / 'native.log').read_text()
    match = re.search(r'Logs: (/tmp/syncmd-protected-native-[^\s]+)', log)
    assert match, 'Missing native evidence directory'
    native_log = pathlib.Path(match[1]) / 'regression.log'
    tags = re.findall(r'account_locale=([^;]+); states=accounts,removal,empty,author; font_scale=1.6; external_calls=0', native_log.read_text())
    assert len(tags) == len(set(tags)) == 25, 'Incomplete locale matrix'
    proof['locales'] = tags
    proof['native_log'] = str(native_log)
    for state in ('remove', 'author'):
        for tag in ('ar', 'de', 'fi', 'ja', 'uk'):
            name = 'account-language-' + state + '-' + tag + '.png'
            capture = subprocess.check_output(BASE + ['exec-out', 'run-as', 'md.gitsync.md.debug', 'cat', 'files/parity-evidence/' + name], timeout=30)
            assert capture.startswith(b'\x89PNG'), name
            (output / name).write_bytes(capture)
finally:
    arguments = ['settings', 'delete', 'system', 'font_scale'] if before is None else ['settings', 'put', 'system', 'font_scale', before]
    subprocess.run(BASE + ['shell', *arguments], check=True, timeout=30, stdout=subprocess.DEVNULL)
    proof['after'] = scale()
    proof['restored'] = before == proof['after']
    (output / 'proof.json').write_text(json.dumps(proof, indent=2) + '\n')
    assert proof['restored'], 'Original font scale needs recovery'
print('Account language check passed:', output / 'proof.json')
