"""Offline guard tests; never invokes ADB or contacts a device."""
import argparse
import unittest
from unittest.mock import patch
from tv_common import TV, canonical

class GuardTests(unittest.TestCase):
    def args(self, **changes):
        values = dict(serial='test-device', model='Example TV', sdk=31, user=0)
        values.update(changes)
        return argparse.Namespace(**values)

    def test_model_mismatch_stops_before_inventory(self):
        tv = TV(self.args())
        with patch.object(tv, 'adb', side_effect=['device', 'Other TV']) as adb:
            with self.assertRaisesRegex(RuntimeError, 'model mismatch'):
                tv.check()
            self.assertEqual(2, adb.call_count)

    def test_sdk_and_user_are_required_matches(self):
        tv = TV(self.args())
        with patch.object(tv, 'adb', side_effect=['device', 'Example TV', '32']):
            with self.assertRaisesRegex(RuntimeError, 'SDK mismatch'):
                tv.check()
        with patch.object(tv, 'adb', side_effect=['device', 'Example TV', '31', 'UserInfo{10:Guest:0}']):
            with self.assertRaisesRegex(RuntimeError, 'does not exist'):
                tv.check()
        with patch.object(tv, 'adb', side_effect=['device', 'Example TV', '31', 'UserInfo{0:Owner:13}']):
            tv.check()

    def test_component_rejects_remote_shell_syntax(self):
        self.assertEqual('com.example/com.example.Home', canonical('com.example/.Home'))
        for component in ('com.example/.Home;reboot', 'com.example/$HOME',
                          'com.example/$(id)', 'com.example/.Home other', 'com.example/..'):
            with self.subTest(component=component), self.assertRaises(ValueError):
                canonical(component)

    def test_serial_never_defaults_or_becomes_an_option(self):
        for serial in ('', '-e', 'two devices'):
            with self.assertRaises(ValueError):
                TV(self.args(serial=serial))

if __name__ == '__main__':
    unittest.main()
