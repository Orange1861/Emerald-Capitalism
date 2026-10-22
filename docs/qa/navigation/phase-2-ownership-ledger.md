# Phase 2 scheduler, scope, and ownership ledger

This tracked ledger covers every Phase 2 Work, Luna automated-verification,
Developer manual-verification, and Gate requirement in
[`navigation-refactor-implementation.md`](../../plans/navigation-refactor-implementation.md).
It was created before the Phase 2 corrections and is updated only with
repository evidence. The generated report and the agent semantic self-review
are separate artifacts, but neither replaces this ledger, constitutes an
independent review, or counts as developer verification or sign-off.

| ID | Original requirement | Implementation or architectural decision | Positive evidence | Negative or adversarial evidence | Developer inspection | Status |
| --- | --- | --- | --- | --- | --- | --- |
| W2.1 | Brain is the only villager scheduler; tasks, movement intent, and routes are distinct. | The manifest freezes Brain scheduling, Brain-owned intent, and vanilla route execution; GoalSelector remains limited to explicit non-villager exceptions during migration. | `verifyNavigationArchitecture` passes and the generated report contains the current/target boundary table. | Permanent scanner tests reject an uninventoried route operation and transitional caller. | Luna compared the report and tracked `net/minecraft/world/entity/ai/behavior/VillagerGoalPackages.java` with the plan target architecture. | PASS |
| W2.2 | Inventory every current villager goal, registered behavior, request owner, memory mutation, direct navigation call, and persistent claim. | The manifest inventories 17 producers, Brain behaviors, request owners, memory mutations, operation-specific navigation references, registrars, transitional references, persistent state, and transient claims. | The verifier reports 17 producers, 11 transient claim scopes, and 299 production sources scanned. | Permanent tests reject unlisted manager/binding, navigation, PATH, and GoalSelector fixtures. | Luna checked inventory source paths against production searches. | PASS |
| W2.3 | Assign each item a target Brain activity, village/profession scope, and durable/transient classification. | Target activity, scope, and persistence fields are required; frozen decisions use one activity and one ownership scope. | The report renders one target scheduler/activity/scope/persistence row for each producer. | The permanent frozen-text test rejects `WORK or IDLE`. | Luna reviewed farmer, lumberjack, mayor, banker, smith, avoidance, begging, and wanted-item rows. | PASS |
| W2.4 | Prohibit new callers of the transitional movement stack. | Exact per-source occurrence inventories cover both `MovementRequestManager` and `VillagerMovementRequestBinding`; implementation files are separately inventoried from callers. | The clean production tree passes exact inventories. | Permanent tests independently reject a new manager caller and a new binding caller. | Caller paths, owner IDs, categories, phases, and legacy paths were inspected. | PASS |
| A2.1 | Fail on direct PATH writes, `PathNavigation.moveTo`, or new mod GoalSelector registrations outside an explicit allowlist. | `NavigationArchitectureScanner` scans receiver-independent registrations, chained navigation, typed/`var`/field/parameter aliases, operation-specific route calls, direct/PATH-alias memory writes, and exact inventories. | The scanner tests run as part of `verifyNavigationArchitecture`; the clean production scan passes. | Permanent tests reject chained and aliased navigation, direct and aliased PATH writes, an unauthorized registration, and an additional registration in an inventoried registrar. | Scanner limitations are limited to the Java declaration forms represented in the helper; supported local, field, parameter, typed, and `var` forms are covered by tests. | PASS |
| A2.2 | Freeze village/profession scope, require the existing real village-isolation test, and assign the absent profession-keyed production claim contract to Phase 11. | Existing `VillageRecord`/`VillageWorkCell` remains the only current production lease API inspected; its lease is village-instance isolated but has no profession key. No unused helper is treated as evidence. | `VillageWorkCellTest.identicalTargetsInSeparateVillagesDoNotShareFarmerLeases` exercises the real two-village API. The missing profession-keyed contract is explicitly recorded as a Phase 11 implementation gap. | Production search finds no profession-keyed current claim API; `LumberjackGoal` still selects a nearest usable furnace without an owner reservation. Phase 2 does not manufacture profession evidence. | No developer inspection can turn missing future functionality into evidence. | PASS |
| M2.1 | Generate an ownership report with one row per farmer, lumberjack, mayor, banker, smith, avoidance, and begging producer; inspect executor, target activity, writes, scope, persistence, phase, legacy path, and one scheduler. | `verifyNavigationArchitecture` generates `build/reports/phase2/navigation-ownership.md`, including the producer table, the explicit `begging-social-task` → `beg-for-food` relationship, frozen decisions, exact transitional references, operation-specific navigation counts, and GoalSelector references. | The report is generated from the current worktree and contains all required producer rows and columns. The permanent scanner has a positive Brain WORK + movement-adapter fixture and a negative IDLE/MEET-vs-WORK fixture. | A generated report is not proof that the developer opened it; no manual approval is claimed here. | **BLOCKED pending developer action.** Run `.\gradlew verifyNavigationArchitecture --no-daemon --no-configuration-cache --console=plain`, open `build/reports/phase2/navigation-ownership.md`, inspect producer rows `farmer`, `lumberjack`, `mayor` (door repair and governor follow), `banker`, `smith` (processor and construction), `avoidance`, `begging`, and `wanted-item-pursuit`. Inspect these exact columns: `ID / role`, `Registered Brain behavior`, `Sources`, `Current executor / scheduler / activity`, `Target scheduler`, `Target activity / finality / priority`, `Memory reads / writes`, `Direct navigation`, `Current scope`, `Target scope`, `Durable / transient current`, `Durable / transient target`, `Cleanup current / target`, `Phase`, `Legacy path`, and `Known gap`. Confirm exactly one scheduler per row, no unexplained direct navigation, no unexplained global claim, and that the missing profession-keyed claim contract is assigned to Phase 11 rather than shown as implemented. The developer must explicitly confirm this inspection; generated files and Luna's review cannot mark it PASS. | BLOCKED |
| G2.1 | Map every current villager producer to the correct gameplay feature. | Producer inventory maps the current villager goals/behaviors; wanted-item pursuit maps the external vanilla Brain chain and actual mod pickup seams. Skrimisher pickup remains an exception. | Source review confirms the mirrored vanilla package and mod pickup hooks. | The permanent wanted-item producer test rejects an exception source substituted for the villager producer. | Luna compared each required role with source behavior, not only manifest IDs. | PASS |
| G2.2 | A non-villager exception cannot satisfy a required villager producer role. | The verifier rejects producer/exception overlap and requires `VillagerPickupCompat`, `VillagerInventoryPolicy`, and both pickup mixin seams. | The clean manifest passes with villager pickup sources. | Permanent test rejects `EmeraldSkrimisherPickupGoal` as the wanted-item producer and reports the missing villager seam. | Exception inventory and producer source sets were inspected. | PASS |
| G2.3 | Every producer has exactly one target scheduler and one target Brain activity. | Brain is enforced as the scheduler; target activity/finality/scope fields reject ambiguous alternatives. | All producer rows render one Brain target scheduler and one target activity. | Permanent frozen-text test rejects an ambiguous target. | Luna reviewed the generated producer rows; route/lifecycle rows are explicitly seams rather than duplicate task schedulers. | PASS |
| G2.4 | Every task/claim has explicit village/profession scope or explicit non-village explanation. | Current and target scopes are recorded; begging is an entity-pair session, exceptions explain non-village scope, and future shared furnace semantics are frozen as village + LUMBERJACK + furnace + owner/generation. | The manifest and review document state the scopes and known current gaps. | The blocked claim evidence identifies current unscoped furnace/profession behavior rather than presenting it as implemented. | Luna reviewed furnace, begging, avoidance, and exception scope rows. | PASS |
| G2.5 | Inventory every current transitional caller. | `transitionalReferenceInventory` freezes every production occurrence with source, owner, category, phase, expected count, and legacy removal path; implementation files are separate. | Exact counts match the current production tree. | A new caller cannot hide inside a broad allowlisted package because exact per-file counts are enforced. | Implementation versus caller rows were inspected separately. | PASS |
| G2.6 | Temporary unauthorized callers of both transitional types fail. | The extracted helper counts `MovementRequestManager` and `VillagerMovementRequestBinding` independently. | `verifyNavigationScannerTests` runs the permanent in-memory rejection tests automatically under the architecture verifier. | Tests reject manager-only and binding-only new callers. | Luna inspected the independent reference-name paths. | PASS |
| G2.7 | Temporary chained and aliased direct navigation fails. | The helper records separate `moveTo`, `createPath`, `stop`, and `getPath` counts and detects chained calls plus typed, `var`, field, and parameter aliases. | The manifest contains four operation counts for every direct-navigation source; the clean verifier passes. | Permanent tests reject chained, typed-alias, `var`-alias, and field/parameter-alias operations. | Luna reviewed supported declaration forms and the generated operation-category table. | PASS |
| G2.8 | Temporary unauthorized PATH writes and villager GoalSelector registrations fail. | Direct and aliased PATH writes and receiver-independent `.addGoal(...)` calls are exact-count checked. | Permanent tests run automatically and clean production verification passes. | Tests reject direct/aliased PATH writes, an unauthorized registration, and an extra registration in an existing registrar. | Luna inspected the helper and failure categories. | PASS |
| G2.9 | The existing village-isolation test exercises a real claim API; the missing profession-keyed production contract and its tests are assigned to Phase 11. | Existing `VillageWorkCellTest` remains the real two-village test; no test-only claim model was added. Profession isolation is a documented Phase 11 implementation gap, not a Phase 2 implementation result. | The two-village `VillageRecord`/`VillageWorkCell` test passes under the supported target `test` tasks. | No current profession-isolated claim consumer exists; furnace ownership is not currently reserved. | Developer confirmation cannot supply missing future production API evidence. | PASS |
| G2.10 | Semantically compare the generated report with implementation. | `phase-2-ownership-review.md` is an agent semantic self-review based on the source and plan; it is not independent and does not derive its conclusion solely from the manifest or verifier. | The review records vanilla, pickup, begging, furnace, movement, claim, and source-scan observations. | The review explicitly records current begging/furnace gaps and the deferred profession-keyed production claim contract. | This row does not claim the developer opened the report; that is M2.1. | PASS |
| G2.11 | No required ownership decision remains in `unresolvedDecisions`. | Resolved decisions are in `frozenDecisions`; `unresolvedDecisions` is empty and ambiguous target text is rejected. | The clean verifier confirms an empty unresolved list. | Permanent ambiguous-frozen-text test fails `WORK or IDLE`. | Luna reread the completion protocol and Phase 2 decision-freezing rules. | PASS |
| G2.12 | No ambiguous global task state remains in the plan. | Mayor, avoidance, begging, furnace, wanted-item, and MoveToTargetSink boundaries have one activity/scope and migration phase; later implementation gaps are not ownership alternatives. | The plan and review contain explicit frozen decisions and no unresolved Phase 2 ownership alternative. | The helper rejects ambiguous frozen text; current begging same-village filtering is identified as a Phase 21 implementation gap, not completed behavior. | Developer report inspection remains pending under M2.1. | PASS |
| G2.13 | Phase ordering is explicit: Phase 3 owns durable/transient state separation and cleanup-key design; Phase 11 owns the production task/claim contract and production-backed profession-isolation tests. | The plan records the two assignments; the Phase 2 manifest records the current scope violations and the target furnace/claim contract without adding an unused implementation. | The Phase 2 source inventory and existing village test provide current evidence; Phase 11 is named as the owner of missing future functionality. | No Phase 2 test-only model, unused helper, compatibility API, or Phase 11 gameplay implementation was added. | Developer report inspection remains pending under M2.1. | PASS |

## Reproducible completion evidence

Successful commands and results for this correction set:

| Command | Result |
| --- | --- |
| `.\gradlew -p buildSrc test --no-daemon --no-configuration-cache --console=plain` | PASS — 20 scanner tests |
| `.\gradlew verifyNavigationArchitecture --no-daemon --no-configuration-cache --console=plain` | PASS — 17 producers, 11 transient claim scopes, 299 production sources |
| `.\gradlew :core:test --no-daemon --no-configuration-cache --console=plain` | PASS |
| `.\gradlew test --no-daemon --no-configuration-cache --console=plain` | PASS — both supported target test tasks |
| `.\gradlew verifyRegressionCoverage --no-daemon --no-configuration-cache --console=plain` | PASS — 7 risk boundaries, 265 GameTests discovered |
| `.\gradlew verifyPortSetup --no-daemon --no-configuration-cache --console=plain` | PASS |
| `.\gradlew :1.21.1:compileJava :1.21.4:compileJava --no-daemon --no-configuration-cache --console=plain` | PASS |
| `python -m py_compile tools/validate_phase1_evidence.py` | PASS |
| `python -m unittest discover -s tools/tests -p "test_*.py"` | PASS — 28 validator tests |
| `git diff --check` | PASS |
| `git status --short` | PASS — expected dirty worktree; no commit created |

The buildSrc test report records 20 passing scanner tests. The permanent Python
validator suite records 28 passing tests covering malformed, stale, incomplete,
optional, identity, baseline, and exit-status evidence. The architecture
verifier report records 17 producers, 11 transient claim scopes, 19
operation-specific direct-navigation entries, and 299 production sources.
The verifier invokes the permanent scanner tests before scanning production
sources. The existing production claim APIs still cannot prove profession
isolation; that missing functionality is intentionally assigned to Phase 11
and is not represented as implemented here. The ledger remains BLOCKED only
because M2.1 requires developer report inspection; passing the verifier alone
is not the Phase 2 gate.

## Current correction-set evidence

The previous command table is historical to the earlier correction revision.
For the current correction set, the following fresh checks passed:

| Command | Result |
| --- | --- |
| `.\gradlew -p buildSrc test --no-daemon --no-configuration-cache --console=plain` | PASS — 23 tests, including 3 MixinAuditTask lifecycle tests |
| `.\gradlew verifyNavigationArchitecture --no-daemon --no-configuration-cache --console=plain` | PASS — 17 producers, 11 transient claim scopes, 299 production sources |
| `.\gradlew :core:test test verifyRegressionCoverage verifyPortSetup :1.21.1:compileJava :1.21.4:compileJava --no-daemon --no-configuration-cache --console=plain` | PASS — core/target tests, regression coverage, port setup, and both compilations |
| `python -m unittest tools.tests.test_validate_phase1_evidence` | PASS — 35 validator tests |

The generated report visibly includes the frozen
`farmer-pumpkin-work-cell-pipeline` and
`farmer-assignment-excludes-bank-employees` decisions, the Phase 15 legacy
`HarvestPumpkinGoal` scanner path, and the Phase 11 missing bank-employee
eligibility violation. It was semantically checked against the current source;
that check is an agent review, not an independent review.

M2.1 remains **BLOCKED**. The developer must perform the exact report
inspection listed in M2.1, confirm that every listed producer row and column
matches the source, and explicitly record the ordinary-world two-target manual
verification result. No generated report or agent review may mark this row
PASS.

## Final local correction evidence

The final local verification stack for this correction worktree passed:

| Command | Result |
| --- | --- |
| `gradle -p buildSrc test --no-daemon --no-configuration-cache --console=plain` | PASS — 23 tests |
| `gradle :core:test test verifyRegressionCoverage verifyPortSetup :1.21.1:compileJava :1.21.4:compileJava --no-daemon --no-configuration-cache --console=plain` | PASS — both targets, 266 discovered GameTests |
| `python -m py_compile tools/validate_phase1_evidence.py` | PASS |
| `python -m unittest discover -s tools/tests -p "test_*.py"` | PASS — 35 tests |
| `gradle verifyNavigationArchitecture --no-daemon --no-configuration-cache --console=plain` | PASS — 17 producers, 11 transient claim scopes, 299 production sources |

The generated report contains the frozen pumpkin work-cell pipeline, the
bank-employee exclusion decision, the Phase 15 `HarvestPumpkinGoal` legacy
path, and the documented Phase 11 missing eligibility check. This is fresh
agent evidence only; M2.1 remains **BLOCKED** until the developer opens the
report, inspects every required row and column, and records the explicit
two-target manual result.

The final Phase 1 CI run for commit `cffd0823b1302a782719f9665da69af612a47fee`
also uploaded the Phase 2 ownership report in artifact ID `12400252262`.
The CI architecture and scanner checks passed; this does not replace the
developer's required M2.1 inspection.

## Current begging consistency evidence

The current correction adds `registeredBehaviorId: "beg-for-food"` to the
begging producer. `verifyNavigationArchitecture` checks that this relationship
resolves to exactly one registered Brain behavior, that its `work package` /
`WORK` owner agrees with the producer's current Brain WORK activity, and that
the transitional MovementRequestManager is classified only as a movement
adapter. The positive and negative relationship fixtures are permanent tests
in `NavigationArchitectureScannerTest` and run under `buildSrc test` and
`verifyNavigationArchitecture`.

The Phase 1 developer evidence is separate: on October 22, 2026 the developer
reported “ordinary world test passed”, so the Phase 1 manual smoke item is
**PASS** for 1.21.1 and 1.21.4. Screenshot, world-name, and log locations were
not supplied and are intentionally not invented. M2.1 remains **BLOCKED**
until the developer inspects the corrected generated report and sends the exact
approval sentence.
