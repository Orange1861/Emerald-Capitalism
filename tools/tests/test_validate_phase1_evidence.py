import json
import tempfile
import unittest
from pathlib import Path

from tools import validate_phase1_evidence as validator


COMMIT = "fixture-commit"
JAVA = 'openjdk version "21.0.9" 2025-10-21 LTS'
TARGETS = validator.TARGETS
TESTS = ["a", "b", "c"]


class Phase1EvidenceValidatorTests(unittest.TestCase):
    def setUp(self):
        self.tempdir = tempfile.TemporaryDirectory(dir=Path.cwd())
        self.root = Path(self.tempdir.name)

    def tearDown(self):
        self.tempdir.cleanup()

    def validate(self):
        return validator.validate_phase1(self.root, commit=COMMIT, host_java=JAVA)

    def write_fixture(
        self,
        *,
        expected=None,
        gametest_failures=None,
        audit_failures=None,
        statuses=None,
        discovered=None,
        completion=True,
        executed=None,
        optional_failures=None,
        audit_overrides=None,
    ):
        discovered = discovered or TESTS
        gametest_failures = gametest_failures or {target: [] for target in TARGETS}
        audit_failures = audit_failures or {target: [] for target in TARGETS}
        optional_failures = optional_failures or {target: 0 for target in TARGETS}
        audit_overrides = audit_overrides or {}
        phase1 = self.root / "build" / "reports" / "phase1"
        phase1.mkdir(parents=True)
        (self.root / "gradle").mkdir()
        baseline = {
            "schemaVersion": 1,
            "targets": {
                target: {
                    "staticGameTestsDiscovered": len(discovered),
                    "gameTestsExecuted": executed if executed is not None else len(discovered),
                    "discoveredTestsSha256": validator.test_identity_hash(discovered),
                }
                for target in TARGETS
            },
            "expectedGameplayFailures": expected if expected is not None else [],
        }
        (self.root / "gradle" / "navigation-regression-baseline.json").write_text(
            json.dumps(baseline), encoding="utf-8"
        )
        (phase1 / "verify-port-setup.log").write_text(
            f"Verified regression coverage manifest: {len(discovered)} GameTests discovered.\n",
            encoding="utf-8",
        )

        default_statuses = {
            "verify-port-setup": 0,
            "unit-tests": 0,
            "compile-1.21.1": 0,
            "compile-1.21.4": 0,
            "build": 0,
            "diff-check": 0,
        }
        for target in TARGETS:
            target_executed = executed if executed is not None else len(discovered)
            required_failures = gametest_failures.get(target, [])
            optional_count = optional_failures.get(target, 0)
            if completion:
                gametest = [
                    f"Phase 1 evidence commit: {COMMIT}",
                    "[main/INFO] GameTestServer started",
                    f"========== {target_executed} GAME TESTS COMPLETE IN 1 sec ==========",
                ]
                if required_failures:
                    gametest.append(f"{len(required_failures)} required tests failed :(")
                    gametest.extend(f"[GameTestServer] - {test}" for test in required_failures)
                    gametest.append("====================================================")
                else:
                    gametest.append(f"All {target_executed - optional_count} required tests passed")
                gametest.append(f"{optional_count} optional tests failed")
            else:
                gametest = ["[main/INFO] GameTestServer started"]
            (phase1 / f"gametest-{target}.log").write_text("\n".join(gametest) + "\n", encoding="utf-8")

            audit_dir = self.root / "versions" / target / "build" / "reports" / "mixin-audit"
            audit_dir.mkdir(parents=True)
            audit_log = audit_dir / "baseline.log"
            audit_log.write_text("baseline audit log\n", encoding="utf-8")
            audit_failure_list = audit_failures.get(target, [])
            baseline_result = {
                "unit": "baseline",
                "classification": "BASELINE_FAILED" if audit_failure_list else "BASELINE_PASSED",
                "completed": True,
                "selectorConfirmed": True,
                "mixinEvidenceValid": True,
                "allRequiredPassed": not audit_failure_list,
                "failedTests": audit_failure_list,
                "missingMixinDecisions": [],
                "unappliedRequiredMixins": [],
                "disabledMixinsStillApplied": [],
                "logSha256": "fixture-log-sha",
                "log": str(audit_log.relative_to(self.root)).replace("\\", "/"),
            }
            baseline_result.update(audit_overrides.get(target, {}))
            report = {
                "schemaVersion": 2,
                "gitCommit": COMMIT,
                "javaVersion": JAVA,
                "generatedAt": "2026-10-21T00:00:00Z",
                "artifacts": {"mergedMinecraftJar": {"sha256": "fixture-jar-sha"}},
                "discoveredTests": discovered,
                "configuredMixins": [],
                "serverMixins": [],
                "results": [baseline_result],
            }
            (audit_dir / "report.json").write_text(json.dumps(report), encoding="utf-8")

            default_statuses[f"gametest-{target}"] = 1 if required_failures else 0
            default_statuses[f"audit-{target}"] = 1 if audit_failure_list else 0

        for task_id, status in {**default_statuses, **(statuses or {})}.items():
            (phase1 / f"{task_id}.exit").write_text(str(status), encoding="utf-8")

    def audit_report(self, target):
        path = self.root / "versions" / target / "build" / "reports" / "mixin-audit" / "report.json"
        return path, json.loads(path.read_text(encoding="utf-8"))

    def errors(self, evidence):
        return "\n".join(evidence["errors"])

    def test_exact_assigned_failures_pass_infrastructure_but_fail_full_regression(self):
        expected = [
            {"target": "1.21.1", "suite": "runtime-gametest", "test": "a", "owningPhase": 12, "owner": "movement"},
            {"target": "1.21.1", "suite": "mixin-audit-baseline", "test": "b", "owningPhase": 14, "owner": "routes"},
        ]
        self.write_fixture(
            expected=expected,
            gametest_failures={"1.21.1": ["a"], "1.21.4": []},
            audit_failures={"1.21.1": ["b"], "1.21.4": []},
        )
        evidence, _ = self.validate()
        self.assertEqual(evidence["infrastructureGate"], "PASS")
        self.assertEqual(evidence["baselineReview"], "PASS")
        self.assertEqual(evidence["fullRegressionGate"], "FAIL")

    def test_clean_valid_run_passes_both_gates(self):
        self.write_fixture()
        evidence, _ = self.validate()
        self.assertEqual(evidence["infrastructureGate"], "PASS")
        self.assertEqual(evidence["fullRegressionGate"], "PASS")
        self.assertTrue(evidence["fullRegressionChecks"]["1.21.1"]["mixinAudit"])

    def test_new_failure_fails_infrastructure_gate(self):
        self.write_fixture(gametest_failures={"1.21.1": ["a"], "1.21.4": []})
        evidence, _ = self.validate()
        self.assertEqual(evidence["infrastructureGate"], "FAIL")
        self.assertIn("new or unassigned failures", self.errors(evidence))

    def test_expected_failure_disappearing_requires_review(self):
        expected = [{"target": "1.21.1", "suite": "runtime-gametest", "test": "a", "owningPhase": 12, "owner": "movement"}]
        self.write_fixture(expected=expected)
        evidence, _ = self.validate()
        self.assertEqual(evidence["infrastructureGate"], "FAIL")
        self.assertEqual(evidence["baselineReview"], "REVIEW_REQUIRED")
        self.assertIn("passed/disappeared", self.errors(evidence))

    def test_renamed_test_identity_hash_fails(self):
        self.write_fixture()
        path, report = self.audit_report("1.21.1")
        report["discoveredTests"] = ["a", "renamed", "c"]
        path.write_text(json.dumps(report), encoding="utf-8")
        evidence, _ = self.validate()
        self.assertEqual(evidence["infrastructureGate"], "FAIL")
        self.assertIn("identity set changed", self.errors(evidence))

    def test_missing_test_identity_hash_fails(self):
        self.write_fixture()
        path, report = self.audit_report("1.21.1")
        report["discoveredTests"] = ["a", "b"]
        path.write_text(json.dumps(report), encoding="utf-8")
        evidence, _ = self.validate()
        self.assertEqual(evidence["infrastructureGate"], "FAIL")
        self.assertIn("identity set changed", self.errors(evidence))

    def test_missing_completion_marker_fails_and_cannot_pass_full_regression(self):
        self.write_fixture(completion=False, statuses={"gametest-1.21.1": 0, "gametest-1.21.4": 0})
        evidence, _ = self.validate()
        self.assertEqual(evidence["infrastructureGate"], "FAIL")
        self.assertEqual(evidence["fullRegressionGate"], "FAIL")
        self.assertIn("completion marker missing", self.errors(evidence))

    def test_missing_commit_marker_cannot_pass_full_regression(self):
        self.write_fixture()
        path = self.root / "build" / "reports" / "phase1" / "gametest-1.21.1.log"
        path.write_text("\n".join(path.read_text(encoding="utf-8").splitlines()[1:]) + "\n", encoding="utf-8")
        evidence, _ = self.validate()
        self.assertEqual(evidence["infrastructureGate"], "FAIL")
        self.assertEqual(evidence["fullRegressionGate"], "FAIL")
        self.assertIn("exact commit marker", self.errors(evidence))

    def test_zero_executed_tests_fails(self):
        self.write_fixture(executed=0)
        evidence, _ = self.validate()
        self.assertEqual(evidence["infrastructureGate"], "FAIL")
        self.assertIn("executed zero tests", self.errors(evidence))

    def test_stale_audit_commit_fails(self):
        self.write_fixture()
        path, report = self.audit_report("1.21.1")
        report["gitCommit"] = "stale-commit"
        path.write_text(json.dumps(report), encoding="utf-8")
        evidence, _ = self.validate()
        self.assertEqual(evidence["infrastructureGate"], "FAIL")
        self.assertIn("stale or incompatible", self.errors(evidence))

    def test_missing_audit_report_or_log_fails(self):
        self.write_fixture()
        report_path, report = self.audit_report("1.21.1")
        report_path.unlink()
        evidence, _ = self.validate()
        self.assertEqual(evidence["infrastructureGate"], "FAIL")
        self.assertIn("missing mixin audit report", self.errors(evidence))

        self.tearDown()
        self.setUp()
        self.write_fixture()
        _, report = self.audit_report("1.21.1")
        log_path = self.root / report["results"][0]["log"]
        log_path.unlink()
        evidence, _ = self.validate()
        self.assertEqual(evidence["infrastructureGate"], "FAIL")
        self.assertIn("baseline log missing", self.errors(evidence))

    def test_missing_selector_confirmation_fails(self):
        self.write_fixture(audit_overrides={"1.21.1": {"selectorConfirmed": False}})
        evidence, _ = self.validate()
        self.assertEqual(evidence["infrastructureGate"], "FAIL")
        self.assertIn("selector was not confirmed", self.errors(evidence))

    def test_missing_required_mixin_evidence_fails(self):
        self.write_fixture(audit_overrides={"1.21.1": {"mixinEvidenceValid": False}})
        evidence, _ = self.validate()
        self.assertEqual(evidence["infrastructureGate"], "FAIL")
        self.assertIn("application evidence failed", self.errors(evidence))

    def test_nonempty_mixin_decision_lists_fail(self):
        for field in ("missingMixinDecisions", "unappliedRequiredMixins", "disabledMixinsStillApplied"):
            with self.subTest(field=field):
                self.tearDown()
                self.setUp()
                self.write_fixture(audit_overrides={"1.21.1": {field: ["fixture"]}})
                evidence, _ = self.validate()
                self.assertEqual(evidence["infrastructureGate"], "FAIL")
                self.assertIn(field, self.errors(evidence))

    def test_mismatched_task_status_and_parsed_result_fails(self):
        self.write_fixture(
            gametest_failures={"1.21.1": ["a"], "1.21.4": []},
            statuses={"gametest-1.21.1": 0},
        )
        evidence, _ = self.validate()
        self.assertEqual(evidence["infrastructureGate"], "FAIL")
        self.assertIn("outcome is inconsistent", self.errors(evidence))

    def test_optional_failures_fail(self):
        self.write_fixture(
            executed=4,
            optional_failures={"1.21.1": 1, "1.21.4": 0},
            statuses={"gametest-1.21.1": 1},
        )
        evidence, _ = self.validate()
        self.assertEqual(evidence["infrastructureGate"], "FAIL")
        self.assertIn("optional failures", self.errors(evidence))

    def test_missing_optional_result_evidence_cannot_pass_full_regression(self):
        self.write_fixture()
        for target in TARGETS:
            path = self.root / "build" / "reports" / "phase1" / f"gametest-{target}.log"
            lines = [line for line in path.read_text(encoding="utf-8").splitlines() if "optional tests failed" not in line]
            path.write_text("\n".join(lines) + "\n", encoding="utf-8")
        evidence, _ = self.validate()
        self.assertEqual(evidence["infrastructureGate"], "FAIL")
        self.assertEqual(evidence["fullRegressionGate"], "FAIL")
        self.assertIn("optional GameTest result missing for 1.21.1", self.errors(evidence))
        self.assertIn("gametest-1.21.1.log", self.errors(evidence))

    def test_audit_failures_cannot_claim_all_required_passed(self):
        self.write_fixture(
            audit_failures={"1.21.1": ["a"], "1.21.4": []},
            audit_overrides={"1.21.1": {"allRequiredPassed": True}},
        )
        evidence, _ = self.validate()
        self.assertEqual(evidence["infrastructureGate"], "FAIL")
        self.assertIn("allRequiredPassed=False", self.errors(evidence))

    def test_audit_without_failures_cannot_claim_required_failure(self):
        self.write_fixture(audit_overrides={"1.21.1": {"allRequiredPassed": False}})
        evidence, _ = self.validate()
        self.assertEqual(evidence["infrastructureGate"], "FAIL")
        self.assertIn("allRequiredPassed=True", self.errors(evidence))

    def test_audit_failures_cannot_be_classified_passed(self):
        self.write_fixture(
            audit_failures={"1.21.1": ["a"], "1.21.4": []},
            audit_overrides={"1.21.1": {"classification": "BASELINE_PASSED"}},
        )
        evidence, _ = self.validate()
        self.assertEqual(evidence["infrastructureGate"], "FAIL")
        self.assertIn("classification='BASELINE_FAILED'", self.errors(evidence))

    def test_audit_without_failures_cannot_be_classified_failed(self):
        self.write_fixture(audit_overrides={"1.21.1": {"classification": "BASELINE_FAILED"}})
        evidence, _ = self.validate()
        self.assertEqual(evidence["infrastructureGate"], "FAIL")
        self.assertIn("classification='BASELINE_PASSED'", self.errors(evidence))

    def test_passing_audit_requires_zero_task_exit(self):
        self.write_fixture(statuses={"audit-1.21.1": 1})
        evidence, _ = self.validate()
        self.assertEqual(evidence["infrastructureGate"], "FAIL")
        self.assertIn("requires audit task exit 0", self.errors(evidence))

    def test_failing_audit_requires_failure_task_exit(self):
        self.write_fixture(
            audit_failures={"1.21.1": ["a"], "1.21.4": []},
            statuses={"audit-1.21.1": 0},
        )
        evidence, _ = self.validate()
        self.assertEqual(evidence["infrastructureGate"], "FAIL")
        self.assertIn("requires audit task exit 1", self.errors(evidence))

    def test_malformed_evidence_cannot_pass_full_regression(self):
        self.write_fixture(completion=False)
        evidence, _ = self.validate()
        self.assertEqual(evidence["fullRegressionGate"], "FAIL")
        self.assertNotEqual(evidence["infrastructureGate"], "PASS")

    def test_malformed_or_duplicate_baseline_entries_fail(self):
        malformed = {"target": "bad", "suite": "bad", "test": "", "owningPhase": 0, "owner": ""}
        self.write_fixture(expected=[malformed])
        evidence, _ = self.validate()
        self.assertEqual(evidence["infrastructureGate"], "FAIL")

        self.tearDown()
        self.setUp()
        duplicate = {"target": "1.21.1", "suite": "runtime-gametest", "test": "a", "owningPhase": 12, "owner": "movement"}
        self.write_fixture(expected=[duplicate, duplicate], gametest_failures={"1.21.1": ["a"], "1.21.4": []})
        evidence, _ = self.validate()
        self.assertEqual(evidence["infrastructureGate"], "FAIL")
        self.assertIn("duplicate baseline", self.errors(evidence))

    def test_valid_baseline_entry_requires_and_accepts_all_ownership_fields(self):
        entry = {"target": "1.21.1", "suite": "runtime-gametest", "test": "a", "owningPhase": 12, "owner": "movement"}
        self.write_fixture(expected=[entry], gametest_failures={"1.21.1": ["a"], "1.21.4": []})
        evidence, _ = self.validate()
        self.assertEqual(evidence["infrastructureGate"], "PASS")
        self.assertTrue(evidence["gameplayFailures"][0]["observed"])

    def test_missing_audit_required_pass_field_fails(self):
        self.write_fixture()
        path, report = self.audit_report("1.21.1")
        del report["results"][0]["allRequiredPassed"]
        path.write_text(json.dumps(report), encoding="utf-8")
        evidence, _ = self.validate()
        self.assertEqual(evidence["infrastructureGate"], "FAIL")
        self.assertIn("required-pass field", self.errors(evidence))

    def test_malformed_audit_boolean_and_log_fields_fail(self):
        self.write_fixture()
        path, report = self.audit_report("1.21.1")
        report["results"][0]["completed"] = "true"
        report["results"][0]["logSha256"] = ""
        path.write_text(json.dumps(report), encoding="utf-8")
        evidence, _ = self.validate()
        self.assertEqual(evidence["infrastructureGate"], "FAIL")
        self.assertIn("completed field", self.errors(evidence))
        self.assertIn("log fingerprint", self.errors(evidence))


if __name__ == "__main__":
    unittest.main()
