#!/usr/bin/env python3
"""Real background callback process-loss QA on the dedicated API-35 debug emulator.
All original preference/locale recovery stays encrypted inside the app. Never uninstall.
Use --recover <evidence-directory> to retry owned cleanup/baseline recovery after interruption.
"""
import argparse
import fcntl
import json
import pathlib
import re
import subprocess
import tempfile
import time
import uuid
from callback_git_fixture import CallbackGitFixture

ROOT = pathlib.Path(__file__).resolve().parents[2]
ADB = str(pathlib.Path.home() / 'Library/Android/sdk/platform-tools/adb')
BASE = [ADB, '-s', 'emulator-5554']
PACKAGE = 'com.bontecou.syncmd.debug'
RUNNER = PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner'
PREFIX = 'com.bontecou.syncmd.'
CLASS = PREFIX + 'RepositoryCallbackInterruptionTest#'
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--mode', choices=('before-remote', 'after-remote', 'after-result', 'all'), default='all')
parser.add_argument('--recover', type=pathlib.Path)
parser.add_argument('--stage-recovery',action='store_true',help='Test recovery by leaving one killed before-remote fixture pending for an immediate --recover command')
options = parser.parse_args()
assert not options.stage_recovery or (options.mode=='before-remote' and options.recover is None)
assert subprocess.check_output(BASE + ['emu', 'avd', 'name'], text=True).splitlines()[0] == 'gitsync-paid-review-20261026'
assert subprocess.check_output(BASE + ['shell', 'getprop', 'ro.build.version.sdk'], text=True).strip() == '35'
output = options.recover.resolve() if options.recover else pathlib.Path(tempfile.mkdtemp(prefix='syncmd-callback-interruption-', dir='/tmp'))
assert output.is_dir() and output.name.startswith('syncmd-callback-interruption-') and output.parent.resolve() == pathlib.Path('/tmp').resolve()
checkpoint = output / 'recovery.json'
owner_lock=(output / '.active.lock').open('a')
try:
    fcntl.flock(owner_lock,fcntl.LOCK_EX | fcntl.LOCK_NB)
except BlockingIOError:
    raise RuntimeError('This evidence directory still has an active controller; do not recover it concurrently')
print('Interruption evidence:', output, flush=True)

def save(state):
    pending = checkpoint.with_suffix('.new')
    pending.write_text(json.dumps(state, indent=2) + '\n')
    pending.replace(checkpoint)

def stop_owned():
    for package in (PACKAGE, PACKAGE + '.test'):
        subprocess.run(BASE + ['shell', 'am', 'force-stop', package], check=True, timeout=15)

def command(selection, arguments=()):
    return BASE + ['shell', 'am', 'instrument', '-w', '-r', *arguments, '-e', 'class', selection, RUNNER]

def native(name, selection, arguments=(), timeout=90):
    path = output / (name + '.log')
    with path.open('w') as stream:
        result = subprocess.run(command(selection, arguments), stdout=stream, stderr=subprocess.STDOUT, timeout=timeout)
    text = path.read_text()
    assert result.returncode == 0 and re.search(r'^OK \([1-9][0-9]* tests?\)', text, re.M) and 'FAILURES!!!' not in text and 'Process crashed' not in text and 'INSTRUMENTATION_STATUS_CODE: -3' not in text, 'Native check failed: ' + str(path)
    return text

def cleanup(state):
    stop_owned()
    failure = None
    if state.get('cleanup_needed'):
        try:
            native(state['mode'] + '-cleanup', CLASS + 'cleanOnlyTheOwnedInterruptionFixture', ('-e', 'callbackInterruptionId', state['id']))
            state['cleanup_needed'] = False
            save(state)
        except BaseException as error:
            failure = error
    if state.get('baseline_needed') or state.get('capture_pending'):
        try:
            arguments = ('-e', 'privateFixtureBaseline', 'restore', '-e', 'privateFixtureOwner', state['id'])
            if state.get('capture_pending') and not state.get('baseline_needed'):
                arguments += ('-e', 'privateFixtureRestoreIfPresent', 'true')
            text = native(state['mode'] + '-baseline-restore', PREFIX + 'FixtureSafetyTest#restorePrivateBaselineAfterSuiteOrCrash', arguments)
            assert 'Private preference maps restored;' in text or 'No owned private baseline exists;' in text
            state['baseline_needed'] = False
            state['capture_pending'] = False
            save(state)
        except BaseException as error:
            failure = error
    if state.get('reverse_needed'):
        try:
            port=state['reverse_port']; assert isinstance(port,int) and 1024<=port<=65535
            endpoint='tcp:'+str(port)
            rows=[line.split() for line in subprocess.check_output(BASE+['reverse','--list'],text=True).splitlines()]
            matches=[row for row in rows if len(row)>=3 and row[-2]==endpoint]
            assert all(row[-1]==endpoint for row in matches),'Owned reverse mapping changed; preserving it for review'
            if matches:
                subprocess.run(BASE+['reverse','--remove',endpoint],check=True)
            state['reverse_needed']=False;save(state)
        except BaseException as error:
            failure=error
    stop_owned()
    if failure:
        raise RuntimeError('Owned recovery needs retry; preserve app data and run --recover ' + str(output)) from failure

if options.recover:
    state = json.loads(checkpoint.read_text())
    assert state['schema'] == 1 and str(uuid.UUID(state['id'])) == state['id']
    cleanup(state)
    print('Owned fixture and encrypted baseline recovered.', flush=True)
    raise SystemExit(0)

results = []
for mode in (('before-remote', 'after-remote', 'after-result') if options.mode == 'all' else (options.mode,)):
    fixture = CallbackGitFixture(mode)
    state = {'schema': 1, 'id': str(uuid.uuid4()), 'mode': mode, 'baseline_needed': False, 'capture_pending': False, 'cleanup_needed': False, 'reverse_needed': False}
    save(state)
    arguments = ('-e', 'callbackInterruptionId', state['id'], '-e', 'callbackInterruptionMode', mode,
        '-e', 'callbackGitUrl', fixture.url, '-e', 'callbackGitCertificate', fixture.certificate)
    process = None
    leave_pending = False
    try:
        stop_owned()
        existing = subprocess.check_output(BASE + ['reverse', '--list'], text=True)
        assert 'tcp:' + str(fixture.port) not in existing.split()
        state['reverse_needed']=True;state['reverse_port']=fixture.port;save(state)
        subprocess.run(BASE + ['reverse', 'tcp:' + str(fixture.port), 'tcp:' + str(fixture.port)], check=True,stdout=subprocess.DEVNULL)
        state['capture_pending'] = True
        save(state)
        captured = native(mode + '-baseline-capture', PREFIX + 'FixtureSafetyTest#capturePrivateBaselineBeforeSuite', ('-e', 'privateFixtureBaseline', 'capture', '-e', 'privateFixtureOwner', state['id']))
        assert 'Encrypted fixture baseline saved inside private no-backup storage.' in captured
        state['capture_pending'] = False
        state['baseline_needed'] = True
        state['cleanup_needed'] = True
        save(state)
        path = output / (mode + '-prepare.log')
        with path.open('w') as stream:
            process = subprocess.Popen(command(CLASS + 'prepareProductionPushAndWaitForTheHostToKillThisProcess', arguments), stdout=stream, stderr=subprocess.STDOUT)
            deadline = time.monotonic() + 60
            prepared = 'Owned callback is ready for process death: ' + mode
            while prepared not in path.read_text() and time.monotonic() < deadline:
                assert process.poll() is None, 'Preparation ended before the intended boundary: ' + str(path)
                time.sleep(.1)
            assert prepared in path.read_text(), 'Preparation boundary timed out: ' + str(path)
            boundary = fixture.state()
            assert boundary['receives'] == 1 and boundary['authorization_headers'] == 0
            assert boundary['stage'] == {'before-remote': 'held-before-remote', 'after-remote': 'held-after-remote', 'after-result': 'published'}[mode]
            pid = subprocess.check_output(BASE + ['shell', 'pidof', PACKAGE], text=True).strip()
            assert re.fullmatch(r'[0-9]+', pid)
            subprocess.run(BASE + ['shell', 'am', 'force-stop', PACKAGE], check=True)
            # The observation handle must reach a terminal state before any recovery phase starts.
            try:
                process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                stop_owned()
                process.wait(timeout=20)
            assert 'Process crashed' in path.read_text() or 'INSTRUMENTATION_FAILED' in path.read_text(), 'Expected process death was not observed: ' + str(path)
            assert not subprocess.run(BASE + ['shell', 'pidof', PACKAGE], capture_output=True, text=True).stdout.strip()
        fixture.released.set()
        if mode == 'before-remote':
            deadline = time.monotonic() + 5
            while fixture.state()['dropped'] != 1 and time.monotonic() < deadline:
                time.sleep(.05)
            assert fixture.state()['dropped'] == 1 and fixture.head() == fixture.initial
        if options.stage_recovery:
            leave_pending=True
            results.append({'mode':mode,'staged_recovery':True,'fixture':str(fixture.root),'killed_pid':int(pid),'boundary':boundary})
            print('Owned fixture deliberately staged for --recover:',output,flush=True)
        else:
            native(mode + '-recover', CLASS + 'recoverInFreshProcessRejectReplayAndExplicitlyRetryTheSameCommit', arguments, timeout=90)
            final = fixture.state()
            assert final['head'] != fixture.initial and final['applied'] == 1 and final['authorization_headers'] == 0
            assert final['receives'] == (2 if mode == 'before-remote' else 1)
            assert fixture.git('--git-dir=' + str(fixture.root / 'repo.git'), 'rev-list', '--count', 'main') == '2'
            fixture.git('--git-dir=' + str(fixture.root / 'repo.git'), 'fsck', '--full')
            results.append({'mode': mode, 'fixture': str(fixture.root), 'killed_pid': int(pid), 'boundary': boundary, 'final': final,
                'prepare_log': str(path), 'recovery_log': str(output / (mode + '-recover.log'))})
            print('Passed actual process-loss boundary:', mode, flush=True)
    finally:
        stop_owned()
        if process is not None and process.poll() is None:
            process.wait(timeout=20)
        fixture.close()
        if not leave_pending:
            cleanup(state)
        (output / 'proof.json').write_text(json.dumps({'results': results, 'recovery': state}, indent=2) + '\n')
print('Owned recovery staged; run --recover on this directory.' if options.stage_recovery else 'All selected callback process-loss boundaries passed; proof: '+str(output / 'proof.json'),flush=True)
