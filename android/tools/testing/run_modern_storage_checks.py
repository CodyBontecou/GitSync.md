#!/usr/bin/env python3
"""Verify denial, native folder grants, relocation and revocation on an owned API 30+ AVD.

Install matching debug/test APKs first. Private baselines stay inside the protected
native runner. Only app-op/permission metadata, counts and owned fixture logs leave
the device; shared-storage access and stopped app processes are restored finally.
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
PACKAGE = 'com.bontecou.syncmd.debug'
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--device', required=True)
parser.add_argument('--avd', choices=sorted(name for name, api in OWNED_AVDS.items() if api >= 30), required=True)
options = parser.parse_args()
base = require_owned_emulator(ADB, options.device, options.avd)
output = pathlib.Path(tempfile.mkdtemp(prefix='syncmd-modern-storage-', dir='/tmp'))
print('Modern storage evidence:', output, flush=True)


def read(*arguments):
    return subprocess.check_output(base + list(arguments), text=True, timeout=30).strip()


def storage_mode():
    text = read('shell', 'appops', 'get', PACKAGE, 'MANAGE_EXTERNAL_STORAGE')
    if 'No operations.' in text or 'Default mode: default' in text:
        return 'default'
    match = re.search(r'MANAGE_EXTERNAL_STORAGE:\s*(\w+)', text)
    assert match and match[1] in ('default', 'allow', 'ignore', 'deny', 'foreground'), 'Unknown app-op metadata'
    return match[1]


def metadata():
    package = read('shell', 'dumpsys', 'package', PACKAGE)
    return {
        'storage': storage_mode(),
        'font': read('shell', 'settings', 'get', 'system', 'font_scale'),
        'locales': read('shell', 'cmd', 'locale', 'get-app-locales', PACKAGE) if OWNED_AVDS[options.avd] >= 33 else None,
        'notifications': [line.strip() for line in package.splitlines() if 'android.permission.POST_NOTIFICATIONS: granted=' in line],
        'rotation': {key: read('shell', 'settings', 'get', 'system', key) for key in ('accelerometer_rotation', 'user_rotation')},
    }


def directory_names(arguments):
    result = subprocess.run(base + arguments, capture_output=True, text=True, timeout=30)
    if result.returncode:
        assert 'No such file or directory' in result.stderr + result.stdout, 'Cannot inspect owned fixture namespace'
        return []
    return result.stdout.splitlines()


def counters():
    result = {}
    for folder in ('files', 'shared_prefs', 'cache'):
        names = directory_names(['shell', 'run-as', PACKAGE, 'ls', folder])
        for prefix in ('move-test-', 'gitsync-location-test-'):
            result[folder + ':' + prefix] = sum(name.startswith(prefix) for name in names)
    names = directory_names(['shell', 'ls', '/sdcard/Documents'])
    for prefix in ('gitsync-move-', 'gitsync-location-test-'):
        result['Documents:' + prefix] = sum(name.startswith(prefix) for name in names)
    return result


def set_mode(mode):
    subprocess.run(base + ['shell', 'appops', 'set', PACKAGE, 'MANAGE_EXTERNAL_STORAGE', mode], check=True, timeout=30)


def run(name, classes, expected):
    log = output / (name + '.log')
    with log.open('w') as stream:
        result = subprocess.run([sys.executable, str(ROOT / 'tools/testing/run_native_regression.py'), '--device', options.device,
            '--avd', options.avd, '--classes', classes, '--raw-status', '--timeout', '900'], cwd=ROOT,
            stdout=stream, stderr=subprocess.STDOUT, timeout=1000)
    proof['runs'].append({'name': name, 'classes': classes, 'returncode': result.returncode, 'log': str(log), 'expected_tests': expected})
    assert result.returncode == 0, 'Native storage check failed: ' + str(log)
    native = pathlib.Path(re.search(r'Logs: (/tmp/syncmd-protected-native-\S+)', log.read_text())[1]) / 'regression.log'
    text = native.read_text()
    assert re.search(r'^OK \(' + str(expected) + r' tests?\)\s*$', text, re.M), 'Unexpected native test count'
    assert not re.search(r'^INSTRUMENTATION_STATUS_CODE: -\d+', text, re.M), 'Skipped/failed native case requires separate qualification'
    proof['runs'][-1]['native_log'] = str(native)
    print('Passed:', name, flush=True)


before = metadata()
initial = counters()
assert not any(initial.values()), 'Use a clean owned fixture namespace; do not delete unknown leftovers'
proof = {'device': options.device, 'avd': options.avd, 'api': OWNED_AVDS[options.avd], 'before': before, 'initial_counters': initial, 'runs': []}
try:
    set_mode('default')
    run('initial-denial', 'com.bontecou.syncmd.RepositoryStorageDeniedTest', 1)
    set_mode('allow')
    run('granted-storage', 'com.bontecou.syncmd.RepositoryLocationsTest,com.bontecou.syncmd.RepositoryMoveTest', 30)
    set_mode('default')
    run('revoked-denial', 'com.bontecou.syncmd.RepositoryStorageDeniedTest', 1)
    audit = read('shell', 'am', 'instrument', '-w', '-e', 'fixtureAudit', 'true', '-e', 'class',
        'com.bontecou.syncmd.FixtureStateAuditTest', PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner')
    (output / 'audit.log').write_text(audit + '\n')
    standard = {key: int(value) for key, value in re.findall(r'([a-z_]+)=(\d+)', audit)}
    assert 'OK (1 test)' in audit and len(standard) == 54 and not any(standard.values()), 'Fixture audit requires attention'
    proof['standard'] = standard
finally:
    set_mode(before['storage'])
    for package in (PACKAGE, PACKAGE + '.test'):
        subprocess.run(base + ['shell', 'am', 'force-stop', package], check=True, timeout=20)
    proof['after'] = metadata()
    proof['final_counters'] = counters()
    proof['restored'] = proof['before'] == proof['after']
    (output / 'proof.json').write_text(json.dumps(proof, indent=2) + '\n')
    assert proof['restored'] and proof['final_counters'] == initial, 'Original state/owned fixture recovery requires attention'
print('Modern storage checks passed:', output / 'proof.json')
