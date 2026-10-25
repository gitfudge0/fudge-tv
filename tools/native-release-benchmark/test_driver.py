"""Driver contracts: benchmark success comes from completed measured work, never shell status."""
import importlib.util
import json
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location("benchmark_driver", Path(__file__).parents[1] / "benchmark-native-release.py")
DRIVER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(DRIVER)


class DriverResultTest(unittest.TestCase):
    def result(self):
        return {"success": True, "status": "complete", "warmupCompletedMovements": 60,
                "measuredCompletedMovements": 180, "collectorDrained": True,
                "frozenFocusDescriptions": [f"Title {i}" for i in range(11)],
                "samples": [{"completedMovements": 60, "frameCount": 100} for _ in range(4)]}

    def parse(self, result):
        return DRIVER.parse_result("INSTRUMENTATION_RESULT: nativeReleaseBenchmark=" + json.dumps(result) + "\nINSTRUMENTATION_CODE: -1\n")

    def test_completed_measured_work_is_required(self):
        self.assertTrue(self.parse(self.result())[1])
        for change in ({"success": False}, {"status": "blocked"}, {"measuredCompletedMovements": 179},
                       {"collectorDrained": False}, {"frozenFocusDescriptions": ["duplicate"] * 11}):
            result = self.result(); result.update(change)
            self.assertFalse(self.parse(result)[1], change)

    def test_missing_frames_or_incomplete_sample_is_not_a_win(self):
        for change in ({"completedMovements": 59}, {"frameCount": 0}):
            result = self.result(); result["samples"][2].update(change)
            self.assertFalse(self.parse(result)[1])

    def test_shell_success_or_ambiguous_results_do_not_count(self):
        with self.assertRaises(RuntimeError):
            DRIVER.parse_result("INSTRUMENTATION_CODE: -1\n")
        raw = "INSTRUMENTATION_RESULT: nativeReleaseBenchmark={}\n" * 2
        with self.assertRaises(RuntimeError):
            DRIVER.parse_result(raw)


if __name__ == "__main__":
    unittest.main()
