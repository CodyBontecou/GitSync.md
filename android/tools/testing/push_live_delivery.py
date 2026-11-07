#!/usr/bin/env python3
"""Explicit personal-project FCM smoke test. Requires matching APKs and pre-granted notification permission.

Registration tokens stay in the debug app's private fixture and this process's memory.
The instrumentation restores exact preference snapshots and deletes its SDK token.
"""
import argparse, json, pathlib, subprocess, time, urllib.request, urllib.error, uuid
from device_identity import OWNED_AVDS, require_owned_emulator
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--live-firebase', action='store_true', required=True,
                    help='Send a real notification using the approved personal project')
parser.add_argument("--device", default="emulator-5556")
parser.add_argument("--avd", choices=sorted(OWNED_AVDS), default="gitsync-paid-review-20261026")
options=parser.parse_args()
adb='/Users/codybontecou/Library/Android/sdk/platform-tools/adb'
base=require_owned_emulator(adb,options.device,options.avd)
fixture=str(uuid.uuid4())
pathlib.Path('/tmp/syncmd-push-live-fixture').write_text(fixture)
log=pathlib.Path('/tmp/syncmd-push-live-delivery.log')
with log.open('w') as output:
    process=subprocess.Popen(base+['shell','am','instrument','-w','-e','liveFirebase','true','-e','fixture',fixture,'-e','class','com.bontecou.syncmd.PushSyncLiveDeliveryTest','com.bontecou.syncmd.debug.test/androidx.test.runner.AndroidJUnitRunner'],stdout=output,stderr=subprocess.STDOUT)
    try:
        address=None
        deadline=time.monotonic()+130
        while time.monotonic()<deadline and process.poll() is None:
            candidate=subprocess.run(base+['exec-out','run-as','com.bontecou.syncmd.debug','cat',f'files/push-live-{fixture}/address.json'],capture_output=True)
            if candidate.returncode==0 and candidate.stdout.strip():
                try: address=json.loads(candidate.stdout)
                except (ValueError, UnicodeError): pass
                else: break
            time.sleep(1)
        if address:
            token=subprocess.check_output(['gcloud','auth','print-access-token','--account=bontecouc@gmail.com'],text=True).strip()
            message={'message':{'token':address['token'],'data':{'type':'gitsync.push.v1','repo':'octocat/hello-world','branch':address['branch'],'hint':fixture,'head':'0'*40},'android':{'priority':'HIGH','ttl':'120s','restricted_package_name':'com.bontecou.syncmd.debug'}}}
            request=urllib.request.Request('https://fcm.googleapis.com/v1/projects/gitsync-md-android-20261028/messages:send',data=json.dumps(message).encode(),headers={'Authorization':'Bearer '+token,'Content-Type':'application/json','x-goog-user-project':'gitsync-md-android-20261028'})
            try:
                with urllib.request.urlopen(request,timeout=30) as response:
                    result=json.load(response)
                    print('FCM accepted:',response.status, 'message ID present:',bool(result.get('name')),flush=True)
            except urllib.error.HTTPError as error:
                # The provider error is not printed because it can echo a registration token.
                print('FCM HTTP status:',error.code,flush=True)
        else:
            print('Native fixture did not become ready; inspect its log.',flush=True)
    finally:
        try: process.wait(timeout=240)
        except subprocess.TimeoutExpired:
            print('Instrumentation still owns cleanup; do not force-stop it.',flush=True)
            raise
print(log.read_text())
assert process.returncode==0 and 'OK (1 test)' in log.read_text(),str(log)
