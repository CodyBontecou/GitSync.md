#!/usr/bin/env python3
"""Run owned debug fixtures with an encrypted baseline that stays inside the target app.

Install matching debug/test APKs first. The dedicated emulator and disabled automatic
sync are mandatory. No preferences, credentials or recovery journal are exported.
"""
import argparse,json,pathlib,re,subprocess,tempfile,uuid
from device_identity import OWNED_AVDS, require_owned_emulator
ADB='/Users/codybontecou/Library/Android/sdk/platform-tools/adb'
DEVICE='emulator-5554';PACKAGE='md.gitsync.md.debug';RUNNER=PACKAGE+'.test/androidx.test.runner.AndroidJUnitRunner'
DEFAULT=[
 'RepositoryObservationDeviceTest','ReplayCheckoutDeviceTest','RebaseScreenTest','RenameStorageDeviceTest','RemoteCredentialMigrationDeviceTest','RemoteConnectionTest','RepositoryActionsScreenTest','PullAllScreenTest','PullAllBackgroundStoreTest','PullAllBackgroundTransportTest','HistoryCloneTest','CloneCompletionTest','CloneRecoveryManagementTest','CloneCatalogProvenanceDeviceTest','RepositoryShortcutLauncherTest',
 'RepositoryCallbackScreenTest','RepositoryShortcutRunStoreTest','RepositoryShortcutDeviceTest','RepositoryShortcutLabelsTest','ObsidianConnectionTest','FileNavigationTest',
 'RepositoryCallbackTransportTest','RepositoryCallbackGrantTest','PushSyncTest','BackgroundSyncTest',
 'BackgroundSyncSchedulerTest','LocalizationTest','NavigationLifecycleTest','SafePullDeviceTest','RemoteMergeScreenTest','ConflictViewerLayoutTest',
 'RepositoryViewModelTest','RepositoryOperationScreenTest','HistoryViewModelTest','BranchViewModelTest','BranchCheckoutDeviceTest','RepositoryStatusDeviceTest','RepositoryOverviewUiTest','FolderPublicationDeviceTest#realLocalPushRecoversLostReplyAndKeepsTheSameCommit',
 'FixtureSafetyTest#privateSnapshotRoundTripsTypedMapsAcrossInstancesWithoutPlaintextOnDisk',
 'FixtureSafetyTest#corruptPrivateSnapshotCannotChangeAnyPreference']
PREFIX='com.bontecou.syncmd.'
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--classes',help='Comma-separated com.bontecou.syncmd test classes/methods; defaults to the broad regression')
parser.add_argument('--timeout',type=int,default=900)
parser.add_argument('--device',default=DEVICE,help='Emulator serial; the dedicated GitSync AVD identity is checked before any mutation')
parser.add_argument('--avd', choices=sorted(OWNED_AVDS), default='gitsync-paid-review-20261026', help='Exact owned AVD identity; its Android API is verified before mutation')
parser.add_argument('--clone-motion-capture',action='store_true',help='Add reading pauses only to the explicit clone recovery recording fixture')
parser.add_argument('--clone-recovery-motion',action='store_true',help='Add reading pauses only to the explicit clone recovery dismissal recording fixture')
parser.add_argument('--raw-status',action='store_true',help='Record per-test instrumentation start/end status for diagnosing stalled suites')
parser.add_argument('--cleanup-shortcut-id',help='Exact owned interrupted launcher shortcut UUID; requires one explicit launcher test method')
parser.add_argument('--cleanup-navigation-id',help='Exact owned interrupted FileNavigation fixture UUID; requires one explicit FileNavigation test method')
parser.add_argument('--obsidian-preparation-fixture-id',help='Owned two-process preparation fixture UUID; requires one explicit preparation/recovery method')
parser.add_argument('--move-fixture-id',help='Fresh owned move fixture UUID; requires one explicit RepositoryMoveColdStartTest phase')
parser.add_argument('--replay-fixture-id',help='Fresh owned cold-replay fixture UUID; requires one explicit ReplayCheckoutColdStartTest phase')
parser.add_argument('--clone-publication-fixture-id',help='Owned clone publication fixture UUID; requires one explicit cold preparation/recovery phase')
parser.add_argument('--clone-catalog-fixture-id',help='Owned catalog acknowledgement/revocation fixture UUID; requires one explicit process phase')
parser.add_argument('--shortcut-language',help='One RepositoryShortcutLanguageTest locale; requires its exact workflow method')
parser.add_argument('--repository-action-language',help='One RepositoryActionsLanguageTest locale; requires its exact workflow method')
parser.add_argument('--background-language',help='One explicit BackgroundSyncLanguageTest locale; requires its exact workflow method')
options=parser.parse_args()
assert re.fullmatch(r'emulator-[0-9]+',options.device),'Only an emulator serial is permitted'
DEVICE=options.device
classes=options.classes or ','.join(PREFIX+name for name in DEFAULT)
assert all(re.fullmatch(r'com\.bontecou\.syncmd\.[A-Za-z0-9_]+(?:#[A-Za-z0-9_]+)?',part) for part in classes.split(','))
assert 30<=options.timeout<=1800
regression_args=()
assert sum(bool(value) for value in (options.cleanup_navigation_id,options.replay_fixture_id,options.move_fixture_id,options.obsidian_preparation_fixture_id,options.clone_publication_fixture_id,options.clone_catalog_fixture_id,options.cleanup_shortcut_id))<=1
if options.cleanup_shortcut_id:
 assert str(uuid.UUID(options.cleanup_shortcut_id))==options.cleanup_shortcut_id
 assert re.fullmatch(r'com\.bontecou\.syncmd\.RepositoryShortcutLauncherTest#[A-Za-z0-9_]+',classes)
 regression_args=('-e','cleanupShortcutId',options.cleanup_shortcut_id)
if options.cleanup_navigation_id:
 assert str(uuid.UUID(options.cleanup_navigation_id))==options.cleanup_navigation_id
 assert re.fullmatch(r'com\.bontecou\.syncmd\.FileNavigationTest#[A-Za-z0-9_]+',classes)
 regression_args=('-e','cleanupNavigationId',options.cleanup_navigation_id)
if options.replay_fixture_id:
 assert str(uuid.UUID(options.replay_fixture_id))==options.replay_fixture_id
 assert re.fullmatch(r'com\.bontecou\.syncmd\.ReplayCheckoutColdStartTest#(?:prepareBlockedCheckout|retryBlockedCheckoutInFreshProcess)',classes)
 regression_args=('-e','replayFixture',options.replay_fixture_id)
if options.move_fixture_id:
 assert str(uuid.UUID(options.move_fixture_id))==options.move_fixture_id
 assert re.fullmatch(r'com\.bontecou\.syncmd\.RepositoryMoveColdStartTest#(?:prepareInterruptedPublication|recoverInFreshProcessAndCleanFixture)',classes)
 regression_args=('-e','moveFixture',options.move_fixture_id)
if options.obsidian_preparation_fixture_id:
 assert str(uuid.UUID(options.obsidian_preparation_fixture_id))==options.obsidian_preparation_fixture_id
 assert re.fullmatch(r'com\.bontecou\.syncmd\.ObsidianPreparationColdStartTest#(?:prepareBeforePublication|prepareAfterPublication|recoverAndClean)',classes)
 regression_args=('-e','obsidianPreparationFixture',options.obsidian_preparation_fixture_id)
if options.clone_publication_fixture_id:
 assert str(uuid.UUID(options.clone_publication_fixture_id))==options.clone_publication_fixture_id
 assert re.fullmatch(r'com\.bontecou\.syncmd\.ClonePublicationColdStartTest#(?:prepareStaged|preparePublished|recoverAndClean)',classes)
 regression_args=('-e','clonePublicationFixture',options.clone_publication_fixture_id)
if options.clone_catalog_fixture_id:
 assert str(uuid.UUID(options.clone_catalog_fixture_id))==options.clone_catalog_fixture_id
 assert re.fullmatch(r'com\.bontecou\.syncmd\.CloneCatalogColdStartTest#(?:prepareUnacknowledgedCatalog|prepareRemovedCatalog|preparePendingMarker|recoverCatalogAndClean)',classes)
 regression_args=('-e','cloneCatalogFixture',options.clone_catalog_fixture_id)
if options.clone_motion_capture:
 assert classes=='com.bontecou.syncmd.CloneCompletionTest#githubRegistrationFailureCanRetryWithoutRecloningOrDuplicatingTheCatalog'
 assert not any((options.cleanup_navigation_id,options.replay_fixture_id,options.move_fixture_id,options.obsidian_preparation_fixture_id,options.clone_publication_fixture_id,options.clone_catalog_fixture_id,options.cleanup_shortcut_id))
 regression_args=('-e','cloneMotionCapture','true')
if options.clone_recovery_motion:
 assert classes=='com.bontecou.syncmd.CloneRecoveryManagementTest#cancellingThenConfirmingDismissalKeepsBothFoldersAndSavedConnection'
 assert not options.clone_motion_capture and not regression_args
 regression_args=('-e','cloneRecoveryMotion','true')
if options.shortcut_language:
 assert classes=='com.bontecou.syncmd.RepositoryShortcutLanguageTest#everyLocaleSupportsShortcutConsentResultsAndRevocationAtLargeSystemText'
 assert not regression_args and not options.background_language and not options.repository_action_language
 locales=json.loads((pathlib.Path(__file__).resolve().parents[2]/'app/src/androidTest/assets/localization_expected.json').read_text())['locales']
 assert options.shortcut_language in locales,'Unknown launcher shortcut fixture language'
 regression_args=('-e','shortcutLanguage',options.shortcut_language)
if options.repository_action_language:
 assert classes=='com.bontecou.syncmd.RepositoryActionsLanguageTest#everyLocaleSupportsReviewedActionsAndRecoveryAtLargeSystemText'
 assert not regression_args and not options.background_language
 locales=json.loads((pathlib.Path(__file__).resolve().parents[2]/'app/src/androidTest/assets/localization_expected.json').read_text())['locales']
 assert options.repository_action_language in locales,'Unknown Repository Actions fixture language'
 regression_args=('-e','repositoryActionLanguage',options.repository_action_language)
if options.background_language:
 assert classes=='com.bontecou.syncmd.BackgroundSyncLanguageTest#everyLocaleSupportsAutomaticConsentPoliciesHealthAndRecoveryAtLargeSystemText'
 assert not regression_args
 locales=json.loads((pathlib.Path(__file__).resolve().parents[2]/'app/src/androidTest/assets/localization_expected.json').read_text())['locales']
 assert options.background_language in locales,'Unknown Background Sync fixture language'
 regression_args=('-e','backgroundLanguage',options.background_language)
base=require_owned_emulator(ADB, DEVICE, options.avd)
def stop_owned_processes():
 for package in (PACKAGE,PACKAGE+'.test'):
  process=subprocess.Popen(base+['shell','am','force-stop',package])
  try: code=process.wait(timeout=15)
  except subprocess.TimeoutExpired:
   print('Owned stop command is still pending; waiting on the same process:',package,flush=True)
   code=process.wait()
  if code: raise subprocess.CalledProcessError(code,process.args)
stop_owned_processes()
logs=pathlib.Path(tempfile.mkdtemp(prefix='syncmd-protected-native-',dir='/tmp'))
def run(name,selection,arguments=(),timeout=60):
 path=logs/(name+'.log')
 with path.open('w') as output:
  process=subprocess.run(base+['shell','am','instrument','-w',*(['-r'] if options.raw_status else []),*arguments,'-e','class',selection,RUNNER],stdout=output,stderr=subprocess.STDOUT,timeout=timeout)
 text=path.read_text();match=re.search(r'^OK \((\d+) tests?\)\s*$',text,re.M)
 good=process.returncode==0 and match is not None and int(match[1])>0 and 'Process crashed' not in text and 'FAILURES!!!' not in text
 return good,text,int(match[1]) if match else 0
captured,text,_=run('baseline-capture',PREFIX+'FixtureSafetyTest#capturePrivateBaselineBeforeSuite',('-e','privateFixtureBaseline','capture'))
assert captured and 'Encrypted fixture baseline saved inside private no-backup storage.' in text,'Capture failed or an old journal needs recovery: '+str(logs)
passed=False;count=0
try:
 try:passed,text,count=run('regression',classes,arguments=regression_args,timeout=options.timeout)
 except BaseException:
  # Stop only this owned debug process before starting recovery in another process.
  stop_owned_processes()
  raise
finally:
 stop_owned_processes()
 restored,text,_=run('baseline-restore',PREFIX+'FixtureSafetyTest#restorePrivateBaselineAfterSuiteOrCrash',('-e','privateFixtureBaseline','restore'))
 if not restored or 'Private preference maps restored;' not in text:
  raise RuntimeError('Private recovery needs retry; do not clear app data or uninstall. Logs: '+str(logs))
 stop_owned_processes()
 print('Private baseline restored and removed; no app data exported. Logs:',logs,flush=True)
assert passed,'Native regression failed: '+str(logs/'regression.log')
print('Native checks passed:',count)
