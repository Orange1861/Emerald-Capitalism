#!/usr/bin/env python3
"""Validate and summarize the Phase 1 infrastructure evidence.

The validator intentionally treats the tracked gameplay-failure baseline as a
set that must match exactly. A known failure passing, a new failure appearing,
or a test identity disappearing is review-required; only infrastructure
failures are allowed to be absent from the expected gameplay set.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
from pathlib import Path
from typing import Any


TARGETS = ("1.21.1", "1.21.4")
ALLOWED_SUITES = {"runtime-gametest", "mixin-audit-baseline"}
TEST_IDENTITY = re.compile(r"[a-z0-9_.:-]+")
TASK_IDS = (
    "verify-port-setup",
    "unit-tests",
    "compile-1.21.1",
    "compile-1.21.4",
    "gametest-1.21.1",
    "gametest-1.21.4",
    "audit-1.21.1",
    "audit-1.21.4",
    "build",
    "diff-check",
)
INFRASTRUCTURE_TASKS = {
    "verify-port-setup",
    "unit-tests",
    "compile-1.21.1",
    "compile-1.21.4",
    "build",
    "diff-check",
}


def read_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def read_status(path: Path, errors: list[str]) -> int | None:
    if not path.is_file():
        errors.append(f"missing final Gradle task outcome: {path}")
        return None
    try:
        return int(path.read_text(encoding="utf-8").strip())
    except ValueError:
        errors.append(f"invalid final Gradle task outcome: {path}")
        return None


def normalize_tests(values: Any) -> list[str]:
    if not isinstance(values, list):
        return []
    return sorted({str(value).lower() for value in values})


def test_identity_hash(values: list[str]) -> str:
    payload = "".join(f"{value}\n" for value in normalize_tests(values))
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def parse_static_discovered(path: Path, errors: list[str]) -> int | None:
    text = path.read_text(encoding="utf-8", errors="replace") if path.is_file() else ""
    matches = re.findall(r"(\d+) GameTests discovered", text)
    if not matches:
        errors.append(f"could not parse static GameTest discovery from evidence: {path}")
        return None
    return int(matches[-1])


def parse_gametest(path: Path, target: str, commit: str, errors: list[str]) -> dict[str, Any]:
    text = path.read_text(encoding="utf-8", errors="replace") if path.is_file() else ""
    if not text:
        errors.append(f"missing GameTest log: {path}")
        return {
            "log": str(path),
            "launched": False,
            "executed": None,
            "requiredPassed": None,
            "requiredFailed": None,
            "optionalFailed": None,
            "failedTests": [],
            "completionMarker": False,
            "commitMarkerValid": False,
            "optionalEvidenceValid": False,
        }

    commit_marker_valid = f"Phase 1 evidence commit: {commit}" in text
    if not commit_marker_valid:
        errors.append(f"GameTest log is missing the exact commit marker for {target}: {path}")

    complete = list(re.finditer(r"=+\s+(\d+) GAME TESTS COMPLETE IN", text))
    executed = int(complete[-1].group(1)) if complete else None
    completion_marker = executed is not None
    if executed is None:
        errors.append(f"GameTest suite completion marker missing: {path}")

    failed_matches = list(re.finditer(r"(\d+) required tests failed", text))
    passed_matches = list(re.finditer(r"All (\d+) required tests passed", text))
    optional_matches = list(re.finditer(r"(\d+) optional tests failed", text))
    required_failed = int(failed_matches[-1].group(1)) if failed_matches else 0
    optional_failed = int(optional_matches[-1].group(1)) if optional_matches else 0
    if passed_matches:
        required_passed = int(passed_matches[-1].group(1))
    elif failed_matches and executed is not None:
        required_passed = executed - required_failed - optional_failed
    else:
        required_passed = None
        errors.append(f"could not parse required GameTest result: {path}")

    if not optional_matches:
        optional_evidence_valid = False
        errors.append(f"optional GameTest result missing for {target}: {path}")
    else:
        optional_evidence_valid = True

    failed_tests: list[str] = []
    if failed_matches:
        block = text[failed_matches[-1].end():]
        block = block.split("====================================================", 1)[0]
        failed_tests = normalize_tests(re.findall(r"(?:\]|:)\s+-\s+([A-Za-z0-9_.:-]+)", block))
        if len(failed_tests) != required_failed:
            errors.append(
                f"GameTest failure-name count does not match required failure total for "
                f"{target}: {len(failed_tests)} != {required_failed}"
            )

    launched = "GameTestServer" in text and executed is not None
    if not launched:
        errors.append(f"GameTest launch evidence missing: {path}")
    if executed == 0:
        errors.append(f"GameTest server executed zero tests: {path}")

    if executed is not None and required_passed is not None:
        result_total = required_passed + required_failed + optional_failed
        if result_total != executed:
            errors.append(
                f"GameTest result counts do not match executed count for {target}: "
                f"{required_passed} + {required_failed} + {optional_failed} != {executed}"
            )

    return {
        "log": str(path),
        "launched": launched,
        "executed": executed,
        "requiredPassed": required_passed,
        "requiredFailed": required_failed,
        "optionalFailed": optional_failed,
        "failedTests": failed_tests,
        "passCountDerived": not bool(passed_matches),
        "completionMarker": completion_marker,
        "commitMarkerValid": commit_marker_valid,
        "optionalEvidenceValid": optional_evidence_valid,
    }


def parse_audit(root: Path, target: str, commit: str, errors: list[str]) -> dict[str, Any]:
    report_path = root / "versions" / target / "build" / "reports" / "mixin-audit" / "report.json"
    if not report_path.is_file():
        errors.append(f"missing mixin audit report: {report_path}")
        return {"report": str(report_path), "fresh": False, "baseline": {}}
    try:
        report = read_json(report_path)
    except (OSError, json.JSONDecodeError) as exc:
        errors.append(f"invalid mixin audit report {report_path}: {exc}")
        return {"report": str(report_path), "fresh": False, "baseline": {}}

    fresh = report.get("schemaVersion") == 2 and report.get("gitCommit") == commit
    if not fresh:
        errors.append(
            f"stale or incompatible mixin audit report for {target}: "
            f"schema={report.get('schemaVersion')} commit={report.get('gitCommit')} expected={commit}"
        )
    if not report.get("javaVersion"):
        errors.append(f"mixin audit report has no Java identity: {report_path}")
    if not report.get("generatedAt"):
        errors.append(f"mixin audit report has no generation timestamp: {report_path}")
    artifacts = report.get("artifacts")
    merged_jar = artifacts.get("mergedMinecraftJar") if isinstance(artifacts, dict) else None
    if not isinstance(merged_jar, dict) or not merged_jar.get("sha256"):
        errors.append(f"mixin audit report has no merged-jar fingerprint: {report_path}")

    results = report.get("results")
    if not isinstance(results, list):
        errors.append(f"mixin audit results are missing or malformed: {report_path}")
        results = []
    baseline = next((item for item in results if isinstance(item, dict) and item.get("unit") == "baseline"), None)
    if baseline is None:
        errors.append(f"mixin audit baseline result missing: {report_path}")
        baseline = {}
    for key in (
        "classification",
        "completed",
        "selectorConfirmed",
        "mixinEvidenceValid",
        "allRequiredPassed",
        "failedTests",
        "missingMixinDecisions",
        "unappliedRequiredMixins",
        "disabledMixinsStillApplied",
        "logSha256",
    ):
        if key not in baseline:
            errors.append(f"mixin audit baseline evidence missing {key}: {report_path}")
    if not isinstance(baseline.get("allRequiredPassed"), bool):
        errors.append(f"mixin audit required-pass field is missing or malformed: {report_path}")
    if not isinstance(baseline.get("failedTests"), list):
        errors.append(f"mixin audit failedTests field is missing or malformed: {report_path}")
    for key in ("completed", "selectorConfirmed", "mixinEvidenceValid"):
        if not isinstance(baseline.get(key), bool):
            errors.append(f"mixin audit {key} field is missing or malformed: {report_path}")
    if not isinstance(baseline.get("classification"), str):
        errors.append(f"mixin audit classification field is missing or malformed: {report_path}")
    if not isinstance(baseline.get("logSha256"), str) or not baseline.get("logSha256"):
        errors.append(f"mixin audit log fingerprint is missing or malformed: {report_path}")
    if not baseline.get("completed"):
        errors.append(f"mixin audit baseline did not complete for {target}")
    if not baseline.get("selectorConfirmed"):
        errors.append(f"mixin audit selector was not confirmed for {target}")
    if not baseline.get("mixinEvidenceValid"):
        errors.append(f"mixin audit required mixin application evidence failed for {target}")
    for key in ("missingMixinDecisions", "unappliedRequiredMixins", "disabledMixinsStillApplied"):
        if not isinstance(baseline.get(key), list):
            errors.append(f"mixin audit {key} is missing or malformed for {target}: {report_path}")
        elif baseline.get(key):
            errors.append(f"mixin audit {key} is non-empty for {target}: {baseline[key]}")

    log_value = baseline.get("log")
    if not log_value:
        errors.append(f"mixin audit baseline log path missing: {report_path}")
    else:
        log_path = Path(str(log_value))
        if not log_path.is_absolute():
            log_path = root / log_path
        if not log_path.is_file():
            errors.append(f"mixin audit baseline log missing: {log_path}")

    discovered = normalize_tests(report.get("discoveredTests"))
    return {
        "report": str(report_path),
        "fresh": fresh,
        "gitCommit": report.get("gitCommit"),
        "javaVersion": report.get("javaVersion"),
        "generatedAt": report.get("generatedAt"),
        "staticDiscovered": len(discovered),
        "discoveredTestsSha256": test_identity_hash(discovered),
        "configuredMixins": len(report.get("configuredMixins", [])),
        "serverMixins": len(report.get("serverMixins", [])),
        "baseline": {
            "classification": baseline.get("classification"),
            "completed": baseline.get("completed"),
            "selectorConfirmed": baseline.get("selectorConfirmed"),
            "mixinEvidenceValid": baseline.get("mixinEvidenceValid"),
            "requiredPassed": baseline.get("allRequiredPassed"),
            "requiredFailed": normalize_tests(baseline.get("failedTests")),
            "missingMixinDecisions": baseline.get("missingMixinDecisions", []),
            "unappliedRequiredMixins": baseline.get("unappliedRequiredMixins", []),
            "disabledMixinsStillApplied": baseline.get("disabledMixinsStillApplied", []),
            "log": baseline.get("log"),
        },
    }


def git_commit(root: Path) -> str:
    return subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()


def java_version() -> str:
    result = subprocess.run(
        ["java", "-version"], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, check=True
    )
    lines = result.stdout.strip().splitlines()
    return next((line for line in lines if "version" in line), lines[0] if lines else "unknown")


def parse_expected_failures(baseline: dict[str, Any], errors: list[str]) -> dict[tuple[str, str], dict[str, Any]]:
    expected: dict[tuple[str, str], dict[str, Any]] = {}
    entries = baseline.get("expectedGameplayFailures")
    if not isinstance(entries, list):
        errors.append("expectedGameplayFailures must be a list")
        return expected

    for entry in entries:
        if not isinstance(entry, dict):
            errors.append(f"invalid baseline failure entry: {entry!r}")
            continue
        target = entry.get("target")
        suite = entry.get("suite")
        test = entry.get("test")
        owning_phase = entry.get("owningPhase")
        owner = entry.get("owner")
        valid_test = isinstance(test, str) and bool(TEST_IDENTITY.fullmatch(test))
        valid_phase = isinstance(owning_phase, int) and not isinstance(owning_phase, bool) and owning_phase > 0
        valid_owner = isinstance(owner, str) and bool(owner.strip())
        if target not in TARGETS or suite not in ALLOWED_SUITES or not valid_test:
            errors.append(f"invalid baseline failure identity: {entry}")
            continue
        if not valid_phase or not valid_owner:
            errors.append(f"baseline failure lacks valid owning phase/owner: {entry}")
            continue
        key = (f"{target}:{suite}", test)
        if key in expected:
            errors.append(f"duplicate baseline failure identity: {target}/{suite}/{test}")
            continue
        expected[key] = entry
    return expected


def passing_gametest_evidence(game: dict[str, Any], status: int | None) -> bool:
    return (
        status == 0
        and game.get("launched") is True
        and game.get("completionMarker") is True
        and game.get("commitMarkerValid") is True
        and isinstance(game.get("executed"), int)
        and game.get("executed", 0) > 0
        and isinstance(game.get("requiredPassed"), int)
        and game.get("requiredFailed") == 0
        and game.get("optionalFailed") == 0
        and game.get("optionalEvidenceValid") is True
        and not game.get("failedTests")
    )


def passing_audit_evidence(audit: dict[str, Any], status: int | None) -> bool:
    baseline = audit.get("baseline", {})
    return (
        status == 0
        and audit.get("fresh") is True
        and baseline.get("classification") == "BASELINE_PASSED"
        and baseline.get("completed") is True
        and baseline.get("selectorConfirmed") is True
        and baseline.get("mixinEvidenceValid") is True
        and baseline.get("requiredPassed") is True
        and not baseline.get("requiredFailed")
        and not baseline.get("missingMixinDecisions")
        and not baseline.get("unappliedRequiredMixins")
        and not baseline.get("disabledMixinsStillApplied")
        and bool(baseline.get("log"))
    )


def validate_audit_consistency(
    audit: dict[str, Any], target: str, status: int | None, errors: list[str]
) -> None:
    baseline = audit.get("baseline", {})
    failed_tests = baseline.get("requiredFailed")
    required_passed = baseline.get("requiredPassed")
    classification = baseline.get("classification")
    if not isinstance(failed_tests, list):
        return

    has_failures = bool(failed_tests)
    expected_required_passed = not has_failures
    expected_classification = "BASELINE_FAILED" if has_failures else "BASELINE_PASSED"
    expected_status = 1 if has_failures else 0
    if required_passed is not expected_required_passed:
        errors.append(
            f"{target} mixin audit consistency: failedTests={'non-empty' if has_failures else 'empty'} "
            f"requires allRequiredPassed={expected_required_passed!r}, found {required_passed!r}"
        )
    if classification != expected_classification:
        errors.append(
            f"{target} mixin audit consistency: failedTests={'non-empty' if has_failures else 'empty'} "
            f"requires classification={expected_classification!r}, found {classification!r}"
        )
    if status != expected_status:
        errors.append(
            f"{target} mixin audit consistency: failedTests={'non-empty' if has_failures else 'empty'} "
            f"requires audit task exit {expected_status}, found {status!r}"
        )


def render_markdown(evidence: dict[str, Any]) -> str:
    targets = evidence.get("targets", {})
    lines = [
        "## Phase 1 verification evidence",
        "",
        f"- Git commit: `{evidence.get('gitCommit')}`",
        f"- Java: `{evidence.get('javaVersion')}`",
        f"- Baseline: `{evidence.get('baseline')}`",
        f"- Infrastructure gate: **{evidence.get('infrastructureGate')}**",
        f"- Developer manual verification: **{evidence.get('developerManualVerification')}**",
        f"- Full regression gate: **{evidence.get('fullRegressionGate')}**",
        f"- Baseline review: **{evidence.get('baselineReview')}**",
        f"- Static GameTests discovered: `{evidence.get('staticGameTestsDiscovered')}`",
        "",
        "| Target | Commit | Java | Launched | Discovered | Executed | Passed | Failed | Log |",
        "| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | --- |",
    ]
    for target in TARGETS:
        item = targets.get(target, {}).get("gameTest", {})
        audit = targets.get(target, {}).get("mixinAudit", {})
        lines.append(
            f"| {target} | `{evidence.get('gitCommit')}` | `{audit.get('javaVersion')}` | "
            f"{item.get('launched')} | {evidence.get('staticGameTestsDiscovered')} | "
            f"{item.get('executed')} | {item.get('requiredPassed')} | {item.get('requiredFailed')} | "
            f"`{item.get('log')}` |"
        )
    lines.extend([
        "",
        "| Target | Audit classification | Selector confirmed | Required-mixin evidence | Failed baseline tests | Audit report |",
        "| --- | --- | --- | --- | ---: | --- |",
    ])
    for target in TARGETS:
        audit = targets.get(target, {}).get("mixinAudit", {})
        base = audit.get("baseline", {})
        lines.append(
            f"| {target} | {base.get('classification')} | {base.get('selectorConfirmed')} | "
            f"{base.get('mixinEvidenceValid')} | {len(base.get('requiredFailed', []))} | "
            f"`{audit.get('report')}` |"
        )
    lines.extend([
        "",
        "### Assigned gameplay failures",
        "",
        "| Target | Suite | Test identity | Owning phase | Observed |",
        "| --- | --- | --- | ---: | --- |",
    ])
    for failure in sorted(evidence.get("gameplayFailures", []), key=lambda value: (value["target"], value["suite"], value["test"])):
        lines.append(
            f"| {failure['target']} | {failure['suite']} | `{failure['test']}` | "
            f"Phase {failure['owningPhase']} ({failure['owner']}) | {failure['observed']} |"
        )
    lines.extend(["", "### Evidence errors", ""])
    errors = evidence.get("errors", [])
    lines.extend(f"- {error}" for error in errors) if errors else lines.append("- None.")
    return "\n".join(lines) + "\n"


def validate_phase1(
    root: Path,
    *,
    commit: str | None = None,
    host_java: str | None = None,
) -> tuple[dict[str, Any], str]:
    root = root.resolve()
    evidence_root = root / "build" / "reports" / "phase1"
    baseline_path = root / "gradle" / "navigation-regression-baseline.json"
    errors: list[str] = []

    try:
        baseline = read_json(baseline_path)
    except (OSError, json.JSONDecodeError) as exc:
        baseline = {}
        errors.append(f"Unable to read Phase 1 baseline {baseline_path}: {exc}")
    if baseline.get("schemaVersion") != 1:
        errors.append("navigation-regression-baseline.json must use schemaVersion 1")
    target_config = baseline.get("targets", {})
    if not isinstance(target_config, dict):
        errors.append("baseline targets must be an object")
        target_config = {}
    if set(target_config) != set(TARGETS):
        errors.append(f"baseline targets must be exactly {TARGETS}, found {sorted(target_config)}")
    expected = parse_expected_failures(baseline, errors)

    statuses = {
        task_id: read_status(evidence_root / f"{task_id}.exit", errors)
        for task_id in TASK_IDS
    }
    try:
        commit = commit or git_commit(root)
        host_java = host_java or java_version()
    except (OSError, subprocess.CalledProcessError) as exc:
        errors.append(f"could not identify commit or Java runtime: {exc}")
        commit = "unknown"
        host_java = "unknown"

    static_discovered = parse_static_discovered(evidence_root / "verify-port-setup.log", errors)
    gametests = {
        target: parse_gametest(evidence_root / f"gametest-{target}.log", target, commit, errors)
        for target in TARGETS
    }
    audits = {target: parse_audit(root, target, commit, errors) for target in TARGETS}
    for target in TARGETS:
        validate_audit_consistency(
            audits[target], target, statuses.get(f"audit-{target}"), errors
        )

    baseline_mismatches: list[dict[str, Any]] = []
    for target in TARGETS:
        config = target_config.get(target, {})
        if not isinstance(config, dict):
            errors.append(f"baseline target configuration is malformed for {target}")
            config = {}
        if static_discovered != config.get("staticGameTestsDiscovered"):
            errors.append(
                f"static GameTest discovery changed for {target}: "
                f"{static_discovered} != {config.get('staticGameTestsDiscovered')}"
            )
        game = gametests[target]
        if game.get("executed") != config.get("gameTestsExecuted"):
            errors.append(
                f"executed GameTest count changed for {target}: "
                f"{game.get('executed')} != {config.get('gameTestsExecuted')}"
            )
        audit = audits[target]
        if audit.get("staticDiscovered") != config.get("staticGameTestsDiscovered"):
            errors.append(
                f"mixin audit discovery count changed for {target}: "
                f"{audit.get('staticDiscovered')} != {config.get('staticGameTestsDiscovered')}"
            )
        if audit.get("discoveredTestsSha256") != config.get("discoveredTestsSha256"):
            errors.append(f"GameTest identity set changed for {target}; review new/removed/renamed tests")

        observed_by_suite = {
            "runtime-gametest": set(game.get("failedTests", [])),
            "mixin-audit-baseline": set(audit.get("baseline", {}).get("requiredFailed", [])),
        }
        for suite, observed in observed_by_suite.items():
            identity = f"{target}:{suite}"
            expected_tests = {
                test for (key, test), entry in expected.items() if key == identity
            }
            missing = sorted(expected_tests - observed)
            unexpected = sorted(observed - expected_tests)
            if missing or unexpected:
                mismatch = {
                    "target": target,
                    "suite": suite,
                    "expected": sorted(expected_tests),
                    "observed": sorted(observed),
                    "expectedFailuresNowAbsent": missing,
                    "unexpectedFailures": unexpected,
                }
                baseline_mismatches.append(mismatch)
                if missing:
                    errors.append(
                        f"{target} {suite} expected failures passed/disappeared: {', '.join(missing)}"
                    )
                if unexpected:
                    errors.append(
                        f"{target} {suite} has new or unassigned failures: {', '.join(unexpected)}"
                    )

        expected_audit_classification = "BASELINE_FAILED" if any(
            key == f"{target}:mixin-audit-baseline" for key, _ in expected
        ) else "BASELINE_PASSED"
        if audit.get("baseline", {}).get("classification") != expected_audit_classification:
            errors.append(
                f"{target} audit classification changed: "
                f"{audit.get('baseline', {}).get('classification')} != {expected_audit_classification}"
            )

    for task_id, status in statuses.items():
        if task_id in INFRASTRUCTURE_TASKS and status != 0:
            errors.append(f"infrastructure task {task_id} finished with {status!r}, expected 0")
    for target in TARGETS:
        # The set comparison above is authoritative; this check only catches a
        # launch/report status inconsistent with the exact observed result.
        if statuses.get(f"gametest-{target}") != (1 if gametests[target].get("failedTests") else 0):
            errors.append(f"{target} GameTest task outcome is inconsistent with its parsed failure set")
        if gametests[target].get("optionalFailed") not in (0, None):
            errors.append(f"{target} GameTest suite has optional failures; optional failures are not accepted")

    target_java_versions = {target: audits[target].get("javaVersion") for target in TARGETS}
    java_versions = {value for value in target_java_versions.values() if value}
    if len(java_versions) != 1:
        errors.append(f"per-target mixin audit Java identities are incomplete or disagree: {target_java_versions}")
    phase_failures = []
    for (identity, test), entry in expected.items():
        target, suite = identity.split(":", 1)
        observed = (
            test in gametests[target].get("failedTests", [])
            if suite == "runtime-gametest"
            else test in audits[target].get("baseline", {}).get("requiredFailed", [])
        )
        phase_failures.append({
            "target": target,
            "suite": suite,
            "test": test,
            "owningPhase": entry.get("owningPhase"),
            "owner": entry.get("owner"),
            "observed": observed,
        })

    infrastructure_gate = not errors
    full_regression_checks = {
        target: {
            "gameTest": passing_gametest_evidence(gametests[target], statuses.get(f"gametest-{target}")),
            "mixinAudit": passing_audit_evidence(audits[target], statuses.get(f"audit-{target}")),
        }
        for target in TARGETS
    }
    full_regression_gate = infrastructure_gate and all(
        checks["gameTest"] and checks["mixinAudit"]
        for checks in full_regression_checks.values()
    )
    evidence = {
        "schemaVersion": 2,
        "gitCommit": commit,
        "javaVersion": next(iter(java_versions), host_java),
        "baseline": str(baseline_path.relative_to(root)).replace("\\", "/"),
        "infrastructureGate": "PASS" if infrastructure_gate else "FAIL",
        "developerManualVerification": "BLOCKED",
        "fullRegressionGate": "PASS" if full_regression_gate else "FAIL",
        "baselineReview": "PASS" if not baseline_mismatches else "REVIEW_REQUIRED",
        "staticGameTestsDiscovered": static_discovered,
        "taskOutcomes": statuses,
        "fullRegressionChecks": full_regression_checks,
        "targets": {
            target: {
                "target": target,
                "gitCommit": commit,
                "javaVersion": audits[target].get("javaVersion") or host_java,
                "gameTest": gametests[target],
                "mixinAudit": audits[target],
            }
            for target in TARGETS
        },
        "gameplayFailures": phase_failures,
        "baselineMismatches": baseline_mismatches,
        "errors": errors,
    }
    evidence_path = evidence_root / "phase-1-evidence.json"
    evidence_path.parent.mkdir(parents=True, exist_ok=True)
    evidence_path.write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
    return evidence, render_markdown(evidence)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    evidence, output = validate_phase1(args.root)
    print(output, end="")
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        Path(summary).open("a", encoding="utf-8").write(output)
    return 0 if evidence["infrastructureGate"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
