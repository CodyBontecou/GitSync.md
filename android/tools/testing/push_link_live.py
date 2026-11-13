#!/usr/bin/env python3
"""Opt-in real Android browser GitHub App link using a fresh, empty relay device.

Only the owned debug emulator is used. Credentials/private maps stay on-device.
A human may complete GitHub login in the emulator's actual browser; this harness
never reads browser fields or reconstructs the OAuth callback. Recovery is
attempted even if the link times out; an interrupted recovery leaves a receipt
with its owner ID so the encrypted on-device baseline can be recovered.
"""
import argparse
import json
import pathlib
import re
import subprocess
import tempfile
import time
import uuid
import xml.etree.ElementTree as ET
from device_identity import OWNED_AVDS, require_owned_emulator

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--live-relay',action='store_true',required=True)
parser.add_argument('--device',default='emulator-5556')
parser.add_argument('--avd',choices=sorted(name for name,api in OWNED_AVDS.items() if api>=33),default='gitsync-paid-review-20261026')
parser.add_argument('--permission-wait-seconds',type=int,default=120)
parser.add_argument('--link-wait-seconds',type=int,default=600)
options=parser.parse_args()
assert 10<=options.permission_wait_seconds<=300 and 30<=options.link_wait_seconds<=1200
ADB=str(pathlib.Path.home()/'Library/Android/sdk/platform-tools/adb')
base=require_owned_emulator(ADB,options.device,options.avd)
PACKAGE='com.bontecou.syncmd.debug'
RUNNER=PACKAGE+'.test/androidx.test.runner.AndroidJUnitRunner'
PREFIX='com.bontecou.syncmd.'
fixture=str(uuid.uuid4())
output=pathlib.Path(tempfile.mkdtemp(prefix='syncmd-push-link-live-',dir='/tmp'))
print('Live GitHub link evidence:',output,flush=True)


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

def native(name, method, baseline=None):
    args = ['shell', 'am', 'instrument', '-w', '-r', '-e', 'privateFixtureOwner', fixture]
    if baseline:
        args += ['-e', 'privateFixtureBaseline', baseline, '-e', 'privateFixtureRestoreIfPresent', 'true']
    else:
        args += ['-e', 'liveRelay', 'true', '-e', 'fixture', fixture]
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
    (output/'proof.json').write_text(json.dumps(proof,indent=2)+'\n')
    assert good, 'Native phase failed: ' + str(log)

def restore_permission(before):
    subprocess.run(base + ['shell', 'pm', 'grant' if before['granted'] else 'revoke', PACKAGE, 'android.permission.POST_NOTIFICATIONS'], check=True, timeout=30)
    for flag, option in [('USER_SET', 'user-set'), ('USER_FIXED', 'user-fixed')]:
        subprocess.run(base + ['shell', 'pm', 'set-permission-flags' if flag in before['flags'] else 'clear-permission-flags', PACKAGE, 'android.permission.POST_NOTIFICATIONS', option], check=True, timeout=30)
    assert permission() == before, 'Notification metadata recovery requires attention'

before=metadata()
proof={'fixture':fixture,'device':options.device,'avd':options.avd,'before':before,'runs':[],'link_verified':False,'metadata_restored':False}
(output/'proof.json').write_text(json.dumps(proof,indent=2)+'\n')
baseline_attempted=False
ui_path='/data/local/tmp/push-link-'+fixture+'.xml'
try:
    baseline_attempted=True
    native('baseline-capture','FixtureSafetyTest#capturePrivateBaselineBeforeSuite','capture')
    if not permission()['granted']:
        print('Waiting for notification pre-grant on',options.device,PACKAGE,flush=True)
        deadline=time.monotonic()+options.permission_wait_seconds
        while time.monotonic()<deadline and not permission()['granted']:
            time.sleep(.5)
        assert permission()['granted'],'Notification setup timed out before live registration'
    native('prepare','PushSyncLinkLiveTest#prepareFreshDeviceAndLaunchRealGitHubAppLink')
    ready=json.loads(read('shell','run-as',PACKAGE,'cat','files/push-link-live-'+fixture+'/ready.json'))
    assert ready['fixture']==fixture and ready['expectedLogin']=='CodyBontecou'
    print('Real Android browser opened. Waiting for the app callback and linked personal-account screen; browser fields are never read.',flush=True)
    deadline=time.monotonic()+options.link_wait_seconds
    linked=False
    while time.monotonic()<deadline:
        # Never dump the browser: intents and authorization query parameters stay private.
        activity=read('shell','dumpsys','activity','activities')
        foreground=[line for line in activity.splitlines() if 'topResumedActivity=' in line]
        if any(PACKAGE+'/com.bontecou.syncmd.MainActivity' in line for line in foreground):
            result=subprocess.run(base+['shell','uiautomator','dump',ui_path],capture_output=True,timeout=20)
            if result.returncode==0:
                xml=read('shell','cat',ui_path)
                texts={node.get('text','') for node in ET.fromstring(xml).iter('node') if node.get('package')==PACKAGE}
                linked=('@'+ready['expectedLogin'] in texts and ready['unlinkText'] in texts and ready['screenTitle'] in texts)
                del xml,texts
                if linked:break
        time.sleep(2)
    assert linked,'Real browser callback and linked personal-account screen were not observed'
    proof['linked_screen_observed_before_inspection']=True
    native('verify','PushSyncLinkLiveTest#verifyRealBrowserCallbackAndExistingPersonalInstallation')
    proof['link_verified']=True
except BaseException as error:
    proof['error_type']=type(error).__name__
    raise
finally:
    try:
        if baseline_attempted:
            try:
                native('cleanup','PushSyncLinkLiveTest#cleanOwnedProductionDeviceAndRestoreSdkOptOut')
            finally:
                native('baseline-restore','FixtureSafetyTest#restorePrivateBaselineAfterSuiteOrCrash','restore')
    finally:
        try:
            subprocess.run(base+['shell','rm','-f',ui_path],check=True,timeout=15)
            restore_permission(before['notifications'])
            for package in (PACKAGE,PACKAGE+'.test'):
                subprocess.run(base+['shell','am','force-stop',package],check=True,timeout=20)
            proof['after']=metadata();proof['metadata_restored']=proof['before']==proof['after']
        except BaseException as error:
            proof['metadata_recovery_error_type']=type(error).__name__
            raise
        finally:
            (output/'proof.json').write_text(json.dumps(proof,indent=2)+'\n')
        assert proof['metadata_restored'],'Original device metadata recovery requires attention'
print('Live GitHub App link/status/unlink check passed:',output/'proof.json',flush=True)
