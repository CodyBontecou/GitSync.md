#!/usr/bin/env python3
"""Verify Push Sync controls and recovery in 25 app locales at actual 160% system text.

Install matching debug/test APKs first. The private baseline remains inside the
app; host finally blocks also restore font and app locales after interruption.
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
parser.add_argument('--device', required=True, help='Explicit owned emulator serial; its AVD identity is checked before mutation')
parser.add_argument('--avd', required=True, choices=sorted(name for name, api in OWNED_AVDS.items() if api >= 33))
options = parser.parse_args()
base = require_owned_emulator(ADB, options.device, options.avd)
package = 'com.bontecou.syncmd.debug'
output = pathlib.Path(tempfile.mkdtemp(prefix='syncmd-push-language-', dir='/tmp'))
print('Push Sync language evidence:', output, flush=True)

def read(*arguments):
    return subprocess.check_output(base + list(arguments), text=True, timeout=30).strip()

def scale():
    values = [line.split('=', 1)[1] for line in read('shell', 'settings', 'list', 'system').splitlines() if line.startswith('font_scale=')]
    assert len(values) <= 1
    return values[0] if values else None

before = scale()
assert .5 <= float(before or '1') <= 3
original_locale = read('shell', 'cmd', 'locale', 'get-app-locales', package)
locale_tags = re.search(r'\[([^\]]*)\]\s*$', original_locale)[1].replace('_', '-').replace(' ', '')
assert not locale_tags or all(re.fullmatch(r'[A-Za-z0-9-]+', tag) for tag in locale_tags.split(','))
proof = {'device': options.device, 'avd': options.avd, 'api': OWNED_AVDS[options.avd], 'before_font': before, 'before_locales': original_locale, 'expected_locales': 25, 'states_per_locale': 21}
(output / 'before.json').write_text(json.dumps(proof, indent=2) + '\n')
try:
    subprocess.run(base + ['shell', 'settings', 'put', 'system', 'font_scale', '1.6'], check=True, timeout=30)
    with (output / 'native.log').open('w') as stream:
        result = subprocess.run([sys.executable, str(ROOT / 'tools/testing/run_native_regression.py'), '--device', options.device, '--avd', options.avd,
            '--classes', 'com.bontecou.syncmd.PushSyncLanguageTest#everyLocaleSupportsPushConsentConnectionsRecoveryAndUnlinkAtLargeSystemText', '--raw-status', '--timeout', '900'], cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT, timeout=1000)
    proof['returncode'] = result.returncode
    folder = pathlib.Path(re.search(r'Logs: (/tmp/syncmd-protected-native-[^\s]+)', (output / 'native.log').read_text())[1])
    log = (folder / 'regression.log').read_text()
    rows = re.findall(r'push_locale=([^;]+); states=([^;]+); font_scale=1.6; git_writes=0; external_calls=0', log)
    expected = ['off', 'registered-selected', 'registration-pending', 'registration-restored', 'all-repositories', 'suspended', 'cancelled', 'owner', 'stopped', 'failed', 'no-match', 'open-failed', 'permission', 'unavailable', 'empty', 'connected', 'unlink-cancelled', 'recreated-confirmation-dismissed', 'unlinked', 'opted-out', 'back']
    proof['native_log'] = str(folder / 'regression.log')
    assert result.returncode == 0 and not re.search(r'^INSTRUMENTATION_STATUS_CODE: -\d+', log, re.M), 'Native check failed: ' + str(folder / 'regression.log')
    tags = [tag for tag, states in rows]
    assert len(tags) == len(set(tags)) == 25, 'Incomplete Push Sync locale matrix'
    assert all(states.split(',') == expected for tag, states in rows), 'Incomplete recovery workflow'
    assert 'OK (1 test)' in log, 'Native method did not pass'
    proof.update(locales=tags, states=expected, native_log=str(folder / 'regression.log'))
    for tag in ['ar', 'de', 'ja']:
        name = 'push-language-' + tag + '.png'
        capture = subprocess.check_output(base + ['exec-out', 'run-as', package, 'cat', 'files/parity-evidence/' + name], timeout=30)
        assert capture.startswith(b'\x89PNG'), name
        (output / name).write_bytes(capture)

finally:
    try:
        arguments = ['settings', 'delete', 'system', 'font_scale'] if before is None else ['settings', 'put', 'system', 'font_scale', before]
        subprocess.run(base + ['shell', *arguments], check=True, timeout=30, stdout=subprocess.DEVNULL)
    finally:
        arguments = ['cmd', 'locale', 'set-app-locales', package] + (['--locales', locale_tags] if locale_tags else [])
        subprocess.run(base + ['shell', *arguments], check=True, timeout=30, stdout=subprocess.DEVNULL)
        proof['after_font'] = scale()
        proof['after_locales'] = read('shell', 'cmd', 'locale', 'get-app-locales', package)
        proof['restored'] = before == proof['after_font'] and original_locale == proof['after_locales']
        for name in [package, package + '.test']:
            subprocess.run(base + ['shell', 'am', 'force-stop', name], check=True, timeout=15)
        (output / 'proof.json').write_text(json.dumps(proof, indent=2) + '\n')
        assert proof['restored'], 'Original font or app locales need recovery'
print('Push Sync language check passed:', output / 'proof.json')
