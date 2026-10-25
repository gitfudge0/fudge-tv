"""The opt-in emulator runner must fail on Android failures even when adb exits zero."""

from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest


class EmulatorVerificationTest(unittest.TestCase):
    def run_runner(self, instrumentation_output, serial="emulator-5560"):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            shutil.copy2(Path(__file__).resolve().parent.parent / "verify-android.sh", root)
            (root / "build.sh").write_text("#!/bin/sh\nexit 0\n")
            (root / "build.sh").chmod(0o755)
            adb = root / "sdk/platform-tools/adb"
            adb.parent.mkdir(parents=True)
            adb.write_text(
                "#!/bin/sh\n"
                'shift 2\n'
                'case "$*" in\n'
                '  get-state) echo device ;;\n'
                '  "shell getprop ro.kernel.qemu") echo 1 ;;\n'
                '  install*) echo Success ;;\n'
                '  "shell am instrument"*) printf "%s\\n" "$TEST_INSTRUMENTATION_OUTPUT" ;;\n'
                '  *) exit 2 ;;\n'
                'esac\n'
            )
            adb.chmod(0o755)
            environment = dict(os.environ, ANDROID_SDK_ROOT=str(root / "sdk"),
                               TEST_INSTRUMENTATION_OUTPUT=instrumentation_output)
            result = subprocess.run([str(root / "verify-android.sh"), "--serial", serial],
                                    env=environment, text=True, capture_output=True)
            log = root / ".local/native-verification" / f"instrumentation-{serial}.log"
            return result, log.read_text() if log.exists() else None

    def test_success_requires_a_completed_nonempty_test_run(self):
        result, log = self.run_runner("OK (12 tests)\nINSTRUMENTATION_CODE: -1")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("OK (12 tests)", log)

    def test_failure_and_crash_outputs_fail_even_when_adb_exits_zero(self):
        for output in ("FAILURES!!!\nTests run: 12, Failures: 1", "INSTRUMENTATION_FAILED: crash",
                       "INSTRUMENTATION_RESULT: shortMsg=Process crashed", "OK (0 tests)", ""):
            with self.subTest(output=output):
                result, log = self.run_runner(output)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual(output + "\n", log)

    def test_physical_and_malformed_targets_are_rejected(self):
        for serial in ("192.0.2.1:5555", "emulator-5560/unsafe", "emulator-"):
            with self.subTest(serial=serial):
                result, log = self.run_runner("OK (1 test)", serial)
                self.assertEqual(2, result.returncode)
                self.assertIsNone(log)


if __name__ == "__main__":
    unittest.main()
