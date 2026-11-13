"""Read-only identity checks before mutating an explicitly owned GitSync emulator."""
import re
import subprocess

OWNED_AVDS = {
    'gitsync-parity-api28-20261031': 28,
    'gitsync-parity-api34-20261031': 34,
    'gitsync-paid-review-20261026': 35,
    'gitsync-parity-api36-20261031': 36,
}


def require_owned_emulator(adb, serial, avd):
    assert re.fullmatch(r'emulator-[0-9]+', serial), 'Only an emulator serial is permitted'
    assert avd in OWNED_AVDS, 'Select an explicitly owned GitSync qualification AVD'
    base = [adb, '-s', serial]
    actual = subprocess.check_output(base + ['emu', 'avd', 'name'], text=True, timeout=15).splitlines()[0]
    assert actual == avd, 'Emulator identity does not match the selected GitSync AVD'
    api = subprocess.check_output(base + ['shell', 'getprop', 'ro.build.version.sdk'], text=True, timeout=15).strip()
    assert api == str(OWNED_AVDS[avd]), 'The selected GitSync AVD has an unexpected Android API level'
    return base
