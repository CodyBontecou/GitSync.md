import unittest
from unittest.mock import patch
from device_identity import OWNED_AVDS, require_owned_emulator


class DeviceIdentityTest(unittest.TestCase):
    def test_each_owned_platform_requires_matching_avd_and_api(self):
        for avd, api in OWNED_AVDS.items():
            with self.subTest(avd=avd), patch('device_identity.subprocess.check_output', side_effect=[avd+'\nOK\n', str(api)+'\n']) as read:
                self.assertEqual(['adb', '-s', 'emulator-5558'], require_owned_emulator('adb', 'emulator-5558', avd))
                self.assertEqual(2, read.call_count)
    def test_physical_device_and_unknown_avd_are_rejected_before_any_read(self):
        for serial, avd in [('physical-device', next(iter(OWNED_AVDS))), ('emulator-5558', 'another-project')]:
            with self.subTest(serial=serial, avd=avd), patch('device_identity.subprocess.check_output') as read:
                with self.assertRaises(AssertionError): require_owned_emulator('adb', serial, avd)
                read.assert_not_called()
    def test_wrong_running_avd_stops_before_further_commands(self):
        with patch('device_identity.subprocess.check_output', return_value='another-project\nOK\n') as read:
            with self.assertRaises(AssertionError): require_owned_emulator('adb', 'emulator-5558', next(iter(OWNED_AVDS)))
            self.assertEqual(1, read.call_count)
    def test_wrong_api_cannot_qualify_as_the_requested_platform(self):
        avd='gitsync-parity-api36-20261031'
        with patch('device_identity.subprocess.check_output', side_effect=[avd+'\nOK\n','35\n']):
            with self.assertRaises(AssertionError): require_owned_emulator('adb','emulator-5558',avd)


if __name__ == '__main__': unittest.main()
