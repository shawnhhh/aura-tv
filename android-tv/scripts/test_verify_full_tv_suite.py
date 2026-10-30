import tempfile
import unittest
from pathlib import Path
from xml.etree.ElementTree import Element, SubElement, ElementTree

from verify_full_tv_suite import MIN_DEVICE_TESTS, REQUIRED_CLASSES, summarize


class VerifyFullTvSuiteTest(unittest.TestCase):
    def result_dir(self, tests):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        folder = Path(temp.name)
        root = Element("testsuite")
        for classname, name, status in tests:
            item = SubElement(root, "testcase", classname="tv.herrin.app." + classname,
                              name=name)
            if status != "passed":
                SubElement(item, "skipped" if status == "skipped" else "failure",
                           message="simulated negative fixture")
        ElementTree(root).write(folder / "TEST-fixture.xml")
        return folder

    def complete_cases(self):
        cases = [(name, "positiveScenario", "passed") for name in REQUIRED_CLASSES]
        cases += [("AdditionalCoverage", f"extra{i}", "passed")
                  for i in range(MIN_DEVICE_TESTS - len(cases))]
        return cases

    def test_complete_run_requires_every_critical_class(self):
        folder = self.result_dir(self.complete_cases())
        items, reasons = summarize(folder)
        self.assertEqual(MIN_DEVICE_TESTS, len(items))
        self.assertEqual([], reasons)

    def test_green_gradle_with_missing_feature_tests_must_fail(self):
        cases = self.complete_cases()
        cases = [x if x[0] != "M454CmsOneTapRecoveryInstrumentedTest"
                 else ("AdditionalCoverage", "replacement", "passed")
                 for x in cases]
        _, reasons = summarize(self.result_dir(cases))
        self.assertIn("M454CmsOneTapRecoveryInstrumentedTest", " ".join(reasons))

    def test_skip_and_failure_cannot_be_misreported_as_pass(self):
        cases = self.complete_cases()
        cases[0] = (cases[0][0], cases[0][1], "skipped")
        cases[1] = (cases[1][0], cases[1][1], "failed")
        _, reasons = summarize(self.result_dir(cases))
        self.assertIn("2 failed/skipped", " ".join(reasons))

    def test_empty_results_are_rejected(self):
        _, reasons = summarize(self.result_dir([]))
        self.assertTrue(any("only 0 tests" in reason for reason in reasons))


if __name__ == "__main__":
    unittest.main()
