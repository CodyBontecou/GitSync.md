#!/usr/bin/env python3
"""Scoped Pull All QA on explicitly owned GitSync emulators. Preference recovery stays inside the existing private runner.
This wrapper additionally restores system font or Quick Settings layout after interrupted QA.
"""
import argparse
import hashlib
import json
import pathlib
import shlex
import subprocess
import sys
import tempfile
import time
from device_identity import OWNED_AVDS, require_owned_emulator

ROOT = pathlib.Path(__file__).resolve().parents[2]
ADB = str(pathlib.Path.home() / 'Library/Android/sdk/platform-tools/adb')
PREFIX = 'com.bontecou.syncmd.'
parser = argparse.ArgumentParser(description=__doc__)
modes = parser.add_mutually_exclusive_group(required=True)
modes.add_argument('--tile', action='store_true')
modes.add_argument('--large-text', action='store_true')
modes.add_argument('--cold', action='store_true')
parser.add_argument('--device', default='emulator-5556', help='Owned emulator serial')
parser.add_argument('--avd', choices=sorted(OWNED_AVDS), default='gitsync-paid-review-20261026')
options = parser.parse_args()
BASE = require_owned_emulator(ADB, options.device, options.avd)
output = pathlib.Path(tempfile.mkdtemp(prefix='syncmd-pull-all-system-', dir='/tmp'))
state = {'mode': 'tile' if options.tile else 'large-text' if options.large_text else 'cold', 'runs': [], 'device': options.device, 'avd': options.avd, 'api': OWNED_AVDS[options.avd]}
print('System-check evidence:', output, flush=True)


def setting(namespace, key):
    lines = subprocess.check_output(BASE + ['shell', 'settings', 'list', namespace], text=True).splitlines()
    values = [line.split('=', 1)[1] for line in lines if line.startswith(key + '=')]
    assert len(values) <= 1
    return values[0] if values else None


def digest(value):
    return hashlib.sha256(json.dumps(value).encode()).hexdigest()


def run(selection, name):
    log = output / (name + '.log')
    with log.open('w') as stream:
        result = subprocess.run([sys.executable, str(ROOT / 'tools/testing/run_native_regression.py'),
            '--device', options.device, '--avd', options.avd, '--classes', selection, '--raw-status', '--timeout', '360'], cwd=ROOT,
            stdout=stream, stderr=subprocess.STDOUT, timeout=600)
    state['runs'].append({'selection': selection, 'returncode': result.returncode, 'log': str(log)})
    assert result.returncode == 0, 'Native check failed: ' + str(log)
    print('Passed:', name, flush=True)


try:
    if options.tile:
        before = setting('secure', 'sysui_qs_tiles')
        (output / 'original-tile-layout.json').write_text(json.dumps(before) + '\n')
        state['before_hash'] = digest(before)
        try:
            run(PREFIX + 'PullAllTileTransportTest#actualQuickSettingsTileStartsPullAllWithoutOpeningMainActivity', 'tile')
        finally:
            if before is None:
                subprocess.run(BASE + ['shell', 'settings', 'delete', 'secure', 'sysui_qs_tiles'], check=True, stdout=subprocess.DEVNULL)
            else:
                # Update System UI's host, rather than racing its cached grid via a settings write.
                subprocess.run(BASE + ['shell', 'cmd', 'statusbar', 'set-tiles', shlex.quote(before)], check=True)
            stable = 0
            deadline = time.monotonic() + 15
            while stable < 4 and time.monotonic() < deadline:
                after = setting('secure', 'sysui_qs_tiles')
                stable = stable + 1 if after == before else 0
                time.sleep(.25)
            state['after_hash'] = digest(after)
            state['restored'] = stable == 4
            assert state['restored'], 'Tile layout recovery needs retry using the saved original layout'
    elif options.large_text:
        before = setting('system', 'font_scale')
        assert .5 <= float(before or '1') <= 3
        state['before'] = before
        try:
            subprocess.run(BASE + ['shell', 'settings', 'put', 'system', 'font_scale', '1.6'], check=True)
            run(PREFIX + 'PullAllBackgroundTransportTest#actualWidgetPendingIntentRunsTheServiceWithoutMainAndReviewDoesNotPullAgain,' +
                PREFIX + 'PullAllScreenTest#savedBackgroundSummaryReviewsWithoutGitAndOnlyConfirmedClearDeletesIt', 'large-text')
            for name in ('pull-all-headless-review.png', 'pull-all-background-summary-light.png', 'pull-all-background-summary-dark-xl.png'):
                data = subprocess.check_output(BASE + ['exec-out', 'run-as', 'com.bontecou.syncmd.debug', 'cat', 'files/parity-evidence/' + name])
                assert data.startswith(b'\x89PNG')
                (output / name).write_bytes(data)
        finally:
            if before is None:
                subprocess.run(BASE + ['shell', 'settings', 'delete', 'system', 'font_scale'], check=True, stdout=subprocess.DEVNULL)
            else:
                subprocess.run(BASE + ['shell', 'settings', 'put', 'system', 'font_scale', shlex.quote(before)], check=True)
            state['after'] = setting('system', 'font_scale')
            state['restored'] = before == state['after']
            assert state['restored'], 'Original font scale needs recovery'
    else:
        for i, method in enumerate(('prepareQueuedSummaryBeforeProcessExit', 'recoverInFreshProcessRejectReplayAndCleanOnlyTheOwnedSummary',
            'prepareClaimedPartialSummaryBeforeProcessExit', 'recoverInFreshProcessRejectReplayAndCleanOnlyTheOwnedSummary')):
            run(PREFIX + 'PullAllBackgroundColdStartTest#' + method, 'cold-' + str(i + 1))
finally:
    (output / 'proof.json').write_text(json.dumps(state, indent=2) + '\n')
print('Scoped system checks finished; proof:', output / 'proof.json')
