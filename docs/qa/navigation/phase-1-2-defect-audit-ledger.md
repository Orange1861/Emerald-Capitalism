# Navigation Refactor Phases 1 and 2 defect-audit ledger

This ledger preserves the completion-oriented correction request verbatim at
the requirement level. It is created before implementation and is updated
only with tracked tests, production call traces, generated artifacts, or
explicit developer evidence. A passing check authored by this correction set
is not treated as independent evidence. Final-commit Linux evidence and
developer inspection remain `BLOCKED` until those external actions occur.

| ID | Original requirement | Initial evidence and planned correction | Negative coverage / boundary | Status before implementation |
| --- | --- | --- | --- | --- |
| 1 | Correct optional GameTest parsing: a completed suite with a valid required result and no optional-failure line means zero optional failures; accept explicit zero; reject explicit nonzero; reject missing completion or malformed required results; keep required, optional, and executed totals consistent. | `tools/validate_phase1_evidence.py:parse_gametest` currently marks an omitted optional line invalid. Replace that contract and update the validator tests. | Add omitted-line, explicit-zero, nonzero, incomplete, malformed-required, and inconsistent-total cases. | FAIL |
| 2 | Reaudit the mixin-audit artifact correction: prove both target tasks create the exact durable JAR, it remains through report writing, it is valid for the launched classpath, each audit writes a current-commit report, early failure writes `audit-failure.json` while preserving the original failure, normal baseline failure writes a normal report, and validator derivatives are suppressed only when no usable report exists. | `HEAD` already configures `neoforge-<version>.jar`, orders `createMinecraftArtifacts`, fingerprints before/after variants, and emits a structured diagnostic. Existing permanent coverage is helper-level only; task lifecycle evidence and fresh target runs are still required. | Add narrow task/report lifecycle coverage; run both actual audit tasks and inspect artifact, classpath, report, diagnostic, and exit behavior. | FAIL |
| 3 | Generate fresh evidence and do not claim Phase 1 success from stale artifacts; authoritative Linux evidence must come from the final committed GitHub Actions revision, otherwise leave the item blocked with the exact developer action. | Existing local/report artifacts are not sufficient for final-commit Linux evidence. Regenerate local evidence after implementation and identify the exact CI action needed. | Validator must reject stale commit identities; final CI run must be attached to the final commit. | BLOCKED |
| 4 | Resolve or accurately classify unstable failures by repeatedly running the four named tests on both target versions, investigating state leaks/order/races/arbitrary delays/version expectations, fixing only Phase 1 setup/synchronization defects, and retaining later-phase ownership for genuine unimplemented gameplay. | Existing baseline and docs show cross-version/run variation; no repeat matrix has yet been completed for this correction. | Repeated full target GameTest runs and failure-name comparison; no baseline rewrite to match one run. | FAIL |
| 5 | Correct Phase 1 reporting from fresh evidence; correct stale-owner assignment to 1.21.4; keep infrastructure, developer manual verification, and full regression gates separate; only the developer may mark manual verification PASS; full regression remains FAIL while assigned gameplay failures remain. | Existing evidence/docs are tied to prior revisions and are not a fresh post-correction record. Regenerate the report and retain separate gate values. | Validator and report must show exact commit identity, separate gates, and assigned failures without hiding new failures. | FAIL |
| 6 | Preserve and validate the frozen pumpkin decisions and phase placement; do not implement Phase 15 pumpkin gameplay during this Phase 1/2 correction. | The planning manifest already records village discovery/work-cell dispatch and Phase 15 ownership; source review and architecture verification still need to confirm the current/target boundary. | Architecture report must show the pipeline and the old per-farmer scanner as a Phase 15 legacy path; no Phase 15 production implementation may be added. | FAIL |
| 7 | Preserve and validate bank-employee exclusion: authoritative bank employees stay out of farmer rosters and assignments, leases/claims release on employment, and eligibility returns after removal; Phase 11 owns enforcement and Phase 15 owns end-to-end verification. Confirm current code does not already satisfy this and document the violation. | Current `VillageRecord.isEligibleFarmer` checks profession and village membership but not authoritative bank employment. Preserve/document that as a Phase 11 gap; do not claim it is implemented in Phase 1/2. | Architecture report must show the exclusion decision and current missing check; later production-backed negative/positive tests are assigned to Phase 11/15. | FAIL |
| 8 | Regenerate and validate Phase 2 artifacts, including the visible pumpkin pipeline, bank-employee exclusion, Phase 15 legacy scanner, and Phase 11 violation; perform fresh semantic source review; leave developer manual inspection blocked with exact inspection instructions. | Generated artifacts may reflect an earlier revision. Run `verifyNavigationArchitecture`, regenerate the report/ledger as applicable, and review source semantics independently of the generated manifest. | Permanent architecture scanner tests plus explicit developer checklist; no claim of independent review or manual PASS. | FAIL |

## Final status update protocol

After implementation, each row must be updated with exact command/artifact
evidence and one of `PASS`, `FAIL`, or `BLOCKED`. The final report must also
list the required verification commands, their exact outcomes, the negative
coverage that protects each new boundary, and the exact developer action
needed for any remaining blocked item.

## Final correction-set assessment

| ID | Final status | Evidence and boundary result |
| --- | --- | --- |
| 1 | **PASS** | `py_compile` passed and the validator suite passed 35 tests. The six required parser cases cover omitted optional zero, explicit zero, nonzero failure, incomplete suite, malformed required result, and inconsistent totals. |
| 2 | **PASS** | Both target `auditMixins` tasks used the durable `neoforge-<version>.jar`, wrote current-commit reports after fingerprinting, and returned their expected baseline failure. BuildSrc now has 3 task-lifecycle tests covering normal report writing, missing-input diagnostic preservation, exact artifact/SHA, launched classpath, and report identity. Cross-platform sign-off remains blocked under item 3. |
| 3 | **BLOCKED** | The repository has no authoritative final-commit Linux CI artifact. The latest local validator output uses the current HEAD but rejects the direct GameTest logs because they lack the workflow's exact commit markers and their derived failure set disagrees with the latest audit reports; it cannot be reused as final evidence. Developer action: commit/push this correction set, run the final Linux workflow, attach its artifacts, and rerun the validator against those artifacts. |
| 4 | **PASS** | The remaining `bankgolemrealselectortargetscontestedgovernorcandidatethroughobstruction` fixture was corrected without changing production targeting or the timeout. The test now registers and validates the real candidate, validates the bank/grace transition, applies one real damage event, observes the normal Mob selector, and removes the owned synthetic player on success or at the existing timeout boundary. Fresh completed full-suite runs on both targets executed the dedicated `ecap_governor_candidate_real_goal` batch without this identity in the required-failure set. The old failure was a fixture lifecycle/order dependency: leaked registered synthetic players and arbitrary/manual selector driving made readiness depend on prior batch state. The remaining baseline failures remain assigned to later phases; no baseline rewrite was made. |
| 5 | **BLOCKED** | The stale-owner documentation assignment is corrected to 1.21.4 and gate separation is preserved, but no authoritative final-commit Phase 1 artifact set exists. Final Linux evidence is required before the infrastructure gate can be assessed. Full regression remains FAIL while assigned failures remain. |
| 6 | **PASS** | `verifyNavigationArchitecture` passed; the regenerated report includes the frozen pumpkin work-cell pipeline and the old per-farmer scanner as a Phase 15 legacy path. No Phase 15 gameplay implementation was added. |
| 7 | **PASS** | The generated report and source review preserve `farmer-assignment-excludes-bank-employees`, and confirm `VillageRecord.isEligibleFarmer` currently lacks the authoritative bank-employee check. Enforcement remains Phase 11 and end-to-end gameplay verification remains Phase 15. |
| 8 | **BLOCKED** | Phase 2 artifacts were regenerated and semantically reviewed against current source; the report visibly contains all required pumpkin/bank/legacy/violation entries. Developer inspection is still required and cannot be replaced by generated output or agent review. |

### Exact correction-set command results

- `python -m py_compile tools/validate_phase1_evidence.py` — PASS.
- `python -m unittest discover -s tools/tests -p "test_*.py"` — PASS, 35 tests.
- `python tools/validate_phase1_evidence.py` — FAIL as required for the local
  non-CI fixture: it rejected missing workflow commit markers and the changed
  audit derivative set rather than treating direct local logs as final CI
  evidence.
- `.\gradlew -p buildSrc test --no-daemon --no-configuration-cache --console=plain` — PASS, 23 tests.
- `.\gradlew verifyNavigationArchitecture --no-daemon --no-configuration-cache --console=plain` — PASS, 17 producers, 11 transient claim scopes, 299 production sources.
- `.\gradlew :core:test test verifyRegressionCoverage verifyPortSetup :1.21.1:compileJava :1.21.4:compileJava --no-daemon --no-configuration-cache --console=plain` — PASS.
- Both `:1.21.1:auditMixins` and `:1.21.4:auditMixins` — expected nonzero baseline failures after writing valid reports; artifact lifecycle and report assertions passed.
- `git diff --check` — PASS after the correction-set documentation and source edits.

### Final local fixture evidence

The fresh local full-suite runs for the current correction worktree each reached
the dedicated `ecap_governor_candidate_real_goal` batch and completed normally:

| Target | Static discovered | Runtime executed | Required failures | Suspect identity |
| --- | ---: | ---: | ---: | --- |
| 1.21.1 | 266 | 264 | 5 assigned baseline failures | absent |
| 1.21.4 | 266 | 264 | 6 assigned baseline failures | absent |

The direct runner has no supported test-name or batch filter, so the smallest
relevant batch was verified by its dedicated batch marker inside each fresh
full run. The completed isolated diagnostic corpus also contains repeated
1.21.1 runs of that batch, including the pre-correction failure and the
post-correction passing runs in `build/gametest-isolated-1.21.1/`; the
pre-correction failure is retained as negative evidence and is not treated as
current success. The current fix does not add a baseline entry, weaken an
assertion, repeatedly call `targetSelector.tick`, or extend the 200-tick
timeout.

The interrupted local GameTest runs are retained only as diagnostic context in
the completion notes, not as passing evidence: each launched, reached a test
batch, and failed to emit a suite completion marker before termination. No
generated world lock or debug files from those runs remain in the worktree.
