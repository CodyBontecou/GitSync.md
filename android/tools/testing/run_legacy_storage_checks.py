#!/usr/bin/env python3
"""Exercise legacy Android storage denial/grant/revocation on the owned API 28 AVD.

Install matching debug/test APKs first. Private preference baselines stay inside
protected native runs; only permission flags and owned fixture results are read.
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
PERMISSIONS = ['android.permission.READ_EXTERNAL_STORAGE', 'android.permission.WRITE_EXTERNAL_STORAGE']
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--device', required=True)
parser.add_argument('--avd', choices=sorted(name for name, api in OWNED_AVDS.items() if api in (28, 29)), required=True)
parser.add_argument('--picker-only', action='store_true', help='Check one native picker before the full storage/move cohort')
options = parser.parse_args()
base = require_owned_emulator(ADB, options.device, options.avd)
output = pathlib.Path(tempfile.mkdtemp(prefix='syncmd-legacy-storage-', dir='/tmp'))
print('Legacy storage evidence:', output, flush=True)

def permission_state():
    text = subprocess.check_output(base + ['shell', 'dumpsys', 'package', PACKAGE], text=True, timeout=20)
    result = {}
    for permission in PERMISSIONS:
        assert any(line.strip() == permission for line in text.splitlines()), 'Legacy permission is not requested'
        rows = [line.strip() for line in text.splitlines() if line.strip().startswith(permission + ': granted=')]
        assert len(rows) <= 1, 'Ambiguous permission users'
        # API 28 omits default-denied records and omits the flags field when zero.
        if rows:
            match = re.fullmatch(re.escape(permission) + r': granted=(true|false)(?:, flags=(.*))?', rows[0])
            assert match, 'Unknown permission metadata format'
            flags = (match[2] or '').strip().removeprefix('[').removesuffix(']').strip()
            result[permission] = {'granted': match[1] == 'true', 'flags': '' if flags in ('', '0', '0x0') else flags}
        else:
            result[permission] = {'granted': False, 'flags': ''}
    return result

before = permission_state()
assert all(not item['flags'] for item in before.values()), 'Only a fixture AVD with default permission flags is supported'
proof = {'device': options.device, 'avd': options.avd, 'api': OWNED_AVDS[options.avd], 'before': before, 'runs': []}

def set_permission(permission, granted):
    subprocess.run(base + ['shell', 'pm', 'grant' if granted else 'revoke', PACKAGE, permission], check=True, timeout=20)

def run(name, classes):
    log = output / (name + '.log')
    with log.open('w') as stream:
        result = subprocess.run([sys.executable, str(ROOT / 'tools/testing/run_native_regression.py'), '--device', options.device,
            '--avd', options.avd, '--classes', classes, '--raw-status', '--timeout', '900'], cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT, timeout=1000)
    proof['runs'].append({'name': name, 'classes': classes, 'returncode': result.returncode, 'log': str(log)})
    assert result.returncode == 0, 'Legacy storage native check failed: ' + str(log)
    print('Passed:', name, flush=True)

try:
    for permission in PERMISSIONS:
        set_permission(permission, False)
    run('initial-denial', 'com.bontecou.syncmd.RepositoryStorageDeniedTest')
    for permission in PERMISSIONS:
        set_permission(permission, True)
    assert all(item['granted'] for item in permission_state().values())
    classes = 'com.bontecou.syncmd.RepositoryLocationsTest#nativeFolderPickerGrantsPersistentAccessToTheSelectedDeviceFolder' if options.picker_only else 'com.bontecou.syncmd.RepositoryLocationsTest,com.bontecou.syncmd.RepositoryMoveTest'
    run('granted-storage', classes)
    for permission in PERMISSIONS:
        set_permission(permission, False)
    run('revoked-denial', 'com.bontecou.syncmd.RepositoryStorageDeniedTest')
finally:
    for permission, state in before.items():
        set_permission(permission, state['granted'])
    for package in [PACKAGE, PACKAGE + '.test']:
        subprocess.run(base + ['shell', 'am', 'force-stop', package], check=True, timeout=20)
    proof['after'] = permission_state()
    proof['restored'] = proof['before'] == proof['after']
    (output / 'proof.json').write_text(json.dumps(proof, indent=2) + '\n')
    assert proof['restored'], 'Legacy storage permission recovery needs attention'
print('Legacy storage checks passed:', output / 'proof.json')
