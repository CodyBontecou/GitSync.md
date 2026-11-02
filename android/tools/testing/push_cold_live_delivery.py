#!/usr/bin/env python3
"""Explicit live personal-project FCM -> absent process -> real background Git fast-forward.

The sender uses only bontecouc@gmail.com / gitsync-md-android-20261028.
Generated fixture delivery addresses and OAuth tokens remain in process memory;
preference/credential snapshots never leave the app. While this harness waits,
pre-grant notifications using the permission-setup tool on this exact owned device.
Its finally blocks restore the original permission grant/flags and private maps.
"""
import argparse
import hashlib
import json
import os
import pathlib
import re
import shlex
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import uuid
from device_identity import OWNED_AVDS, require_owned_emulator

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--live-firebase', action='store_true', required=True)
parser.add_argument('--device', default='emulator-5556')
parser.add_argument('--avd', choices=sorted(name for name, api in OWNED_AVDS.items() if api >= 33), default='gitsync-paid-review-20261026')
parser.add_argument('--permission-wait-seconds', type=int, default=120)
parser.add_argument('--process-exit', choices=('cached-kill', 'uid-signal'), default='cached-kill', help='Use uid-signal when a bound app is ineligible for cached-process killing; both require actual absence before FCM')
options = parser.parse_args()
assert 10 <= options.permission_wait_seconds <= 300
ADB = str(pathlib.Path.home() / 'Library/Android/sdk/platform-tools/adb')
base = require_owned_emulator(ADB, options.device, options.avd)
PACKAGE = 'md.gitsync.md.debug'
RUNNER = PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner'
PREFIX = 'com.bontecou.syncmd.'
PROJECT = 'gitsync-md-android-20261028'
ACCOUNT = 'bontecouc@gmail.com'
fixture = str(uuid.uuid4())
output = pathlib.Path(tempfile.mkdtemp(prefix='syncmd-push-cold-live-', dir='/tmp'))
print('Cold live FCM evidence:', output, flush=True)


def read(*args):
    return subprocess.check_output(base + list(args), text=True, timeout=30).strip()


def permission():
    rows = [line.strip() for line in read('shell', 'dumpsys', 'package', PACKAGE).splitlines() if line.strip().startswith('android.permission.POST_NOTIFICATIONS: granted=')]
    assert len(rows) == 1, 'Notification permission metadata unavailable'
    match = re.fullmatch(r'android.permission.POST_NOTIFICATIONS: granted=(true|false), flags=\[\s*([^]]*)\]', rows[0])
    assert match, 'Unexpected notification permission metadata'
    flags = sorted(flag.strip() for flag in match[2].split('|') if flag.strip())
    assert set(flags) <= {'USER_SET', 'USER_FIXED', 'USER_SENSITIVE_WHEN_GRANTED', 'USER_SENSITIVE_WHEN_DENIED'}, 'Preserve policy-fixed permission state; use another owned fixture'
    return {'granted': match[1] == 'true', 'flags': flags}


def metadata():
    text = read('shell', 'appops', 'get', PACKAGE, 'MANAGE_EXTERNAL_STORAGE')
    mode = 'default' if 'No operations.' in text or 'Default mode: default' in text else re.search(r'MANAGE_EXTERNAL_STORAGE:\s*(\w+)', text)[1]
    return {'notifications': permission(), 'storage': mode, 'font': read('shell', 'settings', 'get', 'system', 'font_scale'),
            'locales': read('shell', 'cmd', 'locale', 'get-app-locales', PACKAGE),
            'rotation': {key: read('shell', 'settings', 'get', 'system', key) for key in ('accelerometer_rotation', 'user_rotation')}}


def pid():
    result = subprocess.run(base + ['shell', 'pidof', PACKAGE], capture_output=True, text=True, timeout=15)
    value = result.stdout.strip()
    assert not value or re.fullmatch(r'\d+', value), 'Unexpected app process topology'
    return value or None


def native(name, method, baseline=None):
    args = ['shell', 'am', 'instrument', '-w', '-r', '-e', 'privateFixtureOwner', fixture]
    if baseline:
        args += ['-e', 'privateFixtureBaseline', baseline, '-e', 'privateFixtureRestoreIfPresent', 'true']
    else:
        args += ['-e', 'liveFirebase', 'true', '-e', 'fixture', fixture]
    args += ['-e', 'class', PREFIX + method, RUNNER]
    log = output / (name + '.log')
    with log.open('w') as stream:
        process = subprocess.Popen(base + args, stdout=stream, stderr=subprocess.STDOUT)
        try:
            code = process.wait(timeout=180)
        except subprocess.TimeoutExpired:
            print('Native phase still owns its work; waiting on the same process:', name, flush=True)
            code = process.wait()
    text = log.read_text()
    good = code == 0 and re.search(r'^OK \(1 test\)\s*$', text, re.M) and not re.search(r'^INSTRUMENTATION_STATUS_CODE: -\d+', text, re.M)
    proof['runs'].append({'name': name, 'passed': bool(good), 'log': str(log)})
    assert good, 'Native phase failed: ' + str(log)


def sender_token():
    env = dict(os.environ)
    for key in ('CLOUDSDK_AUTH_CREDENTIAL_FILE_OVERRIDE', 'CLOUDSDK_AUTH_ACCESS_TOKEN', 'CLOUDSDK_AUTH_ACCESS_TOKEN_FILE', 'CLOUDSDK_AUTH_IMPERSONATE_SERVICE_ACCOUNT', 'GOOGLE_APPLICATION_CREDENTIALS'):
        env.pop(key, None)
    command = ['gcloud', '--account=' + ACCOUNT, '--project=' + PROJECT, '--quiet']
    for property_name in ('auth/impersonate_service_account', 'auth/credential_file_override', 'auth/access_token_file'):
        result = subprocess.run(command + ['config', 'get-value', property_name], capture_output=True, text=True, env=env, timeout=30)
        assert result.returncode == 0 and result.stdout.strip() in ('', '(unset)'), 'Sender credential override must be reviewed; no alternate account will be used'
    result = subprocess.run(command + ['auth', 'print-access-token'], capture_output=True, text=True, env=env, timeout=60)
    assert result.returncode == 0 and len(result.stdout.strip()) > 40, 'Personal sender credential is unavailable'
    return result.stdout.strip()


def job_state(database, event):
    assert database == 'no_backup/androidx.work.workdb' and re.fullmatch(r'[a-f0-9]{64}', event)
    sql = "SELECT state FROM WorkSpec WHERE id IN (SELECT work_spec_id FROM WorkName WHERE name='push-sync-" + event + "');"
    result = subprocess.run(base + ['shell', 'run-as', PACKAGE, 'sqlite3', '-readonly', database, shlex.quote(sql)], capture_output=True, text=True, timeout=15)
    assert result.returncode == 0, 'Read-only owned work-state probe failed'
    value = result.stdout.strip()
    assert not value or value in ('0', '1', '2', '3', '4', '5'), 'Unexpected owned WorkManager state'
    return int(value) if value else None


def restore_permission(before):
    subprocess.run(base + ['shell', 'pm', 'grant' if before['granted'] else 'revoke', PACKAGE, 'android.permission.POST_NOTIFICATIONS'], check=True, timeout=30)
    for flag, option in [('USER_SET', 'user-set'), ('USER_FIXED', 'user-fixed')]:
        subprocess.run(base + ['shell', 'pm', 'set-permission-flags' if flag in before['flags'] else 'clear-permission-flags', PACKAGE, 'android.permission.POST_NOTIFICATIONS', option], check=True, timeout=30)
    assert permission() == before, 'Notification metadata recovery requires attention'


before = metadata()
proof = {'fixture': fixture, 'device': options.device, 'avd': options.avd, 'api': OWNED_AVDS[options.avd], 'personal_project': PROJECT, 'sender_account': ACCOUNT, 'before': before, 'process_exit': options.process_exit, 'runs': []}
baseline_attempted = False
try:
    credential = sender_token()
    print('Personal sender credential ready; no token printed or persisted.', flush=True)
    baseline_attempted = True
    native('baseline-capture', 'FixtureSafetyTest#capturePrivateBaselineBeforeSuite', 'capture')
    if not permission()['granted']:
        print('Waiting for notification pre-grant on', options.device, PACKAGE, flush=True)
        deadline = time.monotonic() + options.permission_wait_seconds
        while time.monotonic() < deadline and not permission()['granted']:
            time.sleep(.5)
        assert permission()['granted'], 'Notification setup timed out before live registration'
    native('prepare', 'PushSyncColdLiveTest#prepareOwnedCheckoutBehindRemoteAndLiveAddress')
    ready = json.loads(read('shell', 'run-as', PACKAGE, 'cat', 'files/push-cold-live-' + fixture + '/ready.json'))
    assert ready['fixture'] == fixture and re.fullmatch(r'[a-f0-9]{64}', ready['event'])
    # A normal launch clears the stopped-package bit. Home followed by cached
    # killing or an app-UID signal models process loss without suppressing FCM.
    subprocess.run(base + ['shell', 'am', 'start', '-W', '-n', PACKAGE + '/com.bontecou.syncmd.MainActivity'], check=True, stdout=subprocess.DEVNULL, timeout=40)
    warm_pid = pid(); assert warm_pid
    proof['warm_pid'] = warm_pid
    proof['prepare_pid'] = ready['pid']
    subprocess.run(base + ['shell', 'input', 'keyevent', 'KEYCODE_HOME'], check=True, timeout=15)
    time.sleep(1)
    if options.process_exit == 'uid-signal':
        subprocess.run(base + ['shell', 'run-as', PACKAGE, 'kill', '-TERM', warm_pid], check=True, timeout=15)
    else:
        subprocess.run(base + ['shell', 'am', 'kill', PACKAGE], check=True, timeout=15)
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline and pid():
        time.sleep(.25)
    proof['pid_after_exit_attempt'] = pid()
    assert proof['pid_after_exit_attempt'] is None, 'App process must be absent before sending'
    package_state = read('shell', 'dumpsys', 'package', PACKAGE)
    assert re.search(r'User 0:.*\bstopped=false\b', package_state), 'Force-stopped packages are not a cold FCM delivery scenario'
    address_bytes = subprocess.check_output(base + ['exec-out', 'run-as', PACKAGE, 'cat', 'files/push-cold-live-' + fixture + '/address.json'], timeout=30)
    address = json.loads(address_bytes); del address_bytes
    payload = {'message': {'token': address['token'], 'data': {'type': 'gitsync.push.v1', 'repo': 'octocat/hello-world', 'branch': address['branch'], 'hint': fixture, 'head': '0' * 40},
        'android': {'priority': 'HIGH', 'ttl': '120s', 'restricted_package_name': PACKAGE}}}
    request = urllib.request.Request('https://fcm.googleapis.com/v1/projects/' + PROJECT + '/messages:send', data=json.dumps(payload).encode(),
        headers={'Authorization': 'Bearer ' + credential, 'Content-Type': 'application/json', 'x-goog-user-project': PROJECT})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            accepted = response.status == 200 and bool(json.load(response).get('name'))
    except urllib.error.HTTPError as error:
        raise RuntimeError('Personal FCM provider returned status ' + str(error.code)) from None
    assert accepted
    del payload, address, credential, request
    proof.update(fcm_accepted=True, process_absent_before_send=True, stopped_package=False, warm_pid=warm_pid, prepare_pid=ready['pid'])
    print('FCM accepted; observing the owned job without launching instrumentation.', flush=True)
    deadline = time.monotonic() + 180
    states = []; wake_pid = None
    while time.monotonic() < deadline:
        current_pid = pid()
        if current_pid:
            assert current_pid != warm_pid
            wake_pid = wake_pid or current_pid
        value = job_state(ready['database'], ready['event'])
        if not states or states[-1] != value:
            states.append(value)
        if value == 2:
            break
        assert value not in (3, 5), 'Cold background event failed or was canceled'
        time.sleep(.25)
    assert wake_pid and states[-1] == 2, 'Cold background completion was not observed'
    notifications = read('shell', 'cmd', 'notification', 'list')
    assert any(PACKAGE in line and ready['event'] in line for line in notifications.splitlines()), 'Cold notification must exist before inspection'
    starts = read('shell', 'logcat', '-b', 'events', '-d', '-s', 'am_proc_start:I')
    rows = [line for line in starts.splitlines() if re.search(r'\[0,' + wake_pid + r',', line) and PACKAGE + ',' in line]
    assert len(rows) == 1, 'Cold process-start reason must be unambiguous'
    fields = rows[0].split('[', 1)[1].rsplit(']', 1)[0].split(',')
    component = ','.join(fields[5:])
    expected_receiver = '{' + PACKAGE + '/com.google.firebase.iid.FirebaseInstanceIdReceiver}'
    expected_service = '{' + PACKAGE + '/com.bontecou.syncmd.push.GitSyncMessagingService}'
    assert (fields[4], component) in (('broadcast', expected_receiver), ('service', expected_service)), 'Cold process must start through a recognized Firebase entry point'
    proof.update(wake_pid=wake_pid, observed_work_states=states, worker_succeeded_before_instrumentation=True, notification_before_inspection=True,
                 cold_start={'hosting_type': fields[4], 'component': component})
    native('verify', 'PushSyncColdLiveTest#verifyDurableColdFastForwardAfterHostObservedWorkerSuccess')
finally:
    try:
        if baseline_attempted:
            try:
                native('cleanup', 'PushSyncColdLiveTest#cleanOnlyOwnedColdLiveResources')
            finally:
                native('baseline-restore', 'FixtureSafetyTest#restorePrivateBaselineAfterSuiteOrCrash', 'restore')
    finally:
        restore_permission(before['notifications'])
        for package in (PACKAGE, PACKAGE + '.test'):
            subprocess.run(base + ['shell', 'am', 'force-stop', package], check=True, timeout=20)
        proof['after'] = metadata(); proof['metadata_restored'] = proof['before'] == proof['after']
        (output / 'proof.json').write_text(json.dumps(proof, indent=2) + '\n')
        assert proof['metadata_restored'], 'Original device metadata recovery requires attention'
print('Cold live FCM check passed:', output / 'proof.json', flush=True)
