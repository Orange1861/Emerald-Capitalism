# Phase 2 agent semantic review

This is an agent semantic self-review for Phase 2, written by the implementing
agent. It is intentionally separate from `gradle/navigation-architecture.json`,
the validator, and the tracked requirement ledger at
[`phase-2-ownership-ledger.md`](phase-2-ownership-ledger.md). It is not an
independent review and is not developer verification or sign-off.

## Review basis

- Branch: `codex/ECAP-Path-Rework`
- Source revision before these corrections: `00cd9c16`
- Phase 1 commits and the plan-only completion-protocol change remain intact.
- No Phase 3 gameplay migration was started.
- `ScopedClaimLedger` was searched in the complete production tree and removed
  because it had no gameplay consumer; it is not evidence of current claim
  isolation.

## Wanted-item producer review

The repository's mirrored vanilla source at
[`VillagerGoalPackages.java`](../../../net/minecraft/world/entity/ai/behavior/VillagerGoalPackages.java)
shows `MoveToTargetSink` in the villager CORE package and `GoToWantedItem` in
that same package. The external vanilla executor is modeled explicitly because
its implementation is not a repository source file:

| Version | External vanilla executor and ownership | Actual mod integration seam |
| --- | --- | --- |
| 1.21.1 | `NearestItemSensor` finds wanted items; `GoToWantedItem` publishes `LOOK_TARGET`/`WALK_TARGET`; `MoveToTargetSink` executes the route; `Villager.pickUpItem` removes the item. | `VillagerPickupCompat` bridges `Villager.wantsToPickUp(ItemStack)`; `VillagerInventoryPolicy`, `VillagerPickupPolicyMixin`, and `VillagerPickupAccountingMixin` enforce/observe mod policy. |
| 1.21.4 | The same Brain chain and route executor, with the version's `ServerLevel`-aware sensor/pickup signatures and `GoToWantedItem` pickup eligibility. | `VillagerPickupCompat` bridges `Villager.wantsToPickUp(ServerLevel, ItemStack)`; the same inventory-policy and pickup mixin seams remain the mod boundary. |

The seams cover vanilla-compatible wanted-item eligibility, farmer seeds and
bone meal, accepted profession items, rejected items, capacity, reachability,
actual item removal, and pickup accounting. Stale transitional movement state
can retain or restore `WALK_TARGET`/route state and suppress vanilla pursuit;
that is why the manifest records the movement compatibility observer and
forbids a duplicate item-seeking scheduler. The exact target ownership is the
vanilla CORE wanted-item route, with migration in Phase 16.

`EmeraldSkrimisherPickupGoal` remains solely in the non-villager exception
inventory. The permanent producer test rejects substituting it for the
ordinary-villager producer.

## Current claim evidence and limitation

The real current farmer claim API is `VillageRecord`/`VillageWorkCell`:
`WorkLease` is held by a `VillageWorkCell` instance and is therefore isolated
between two `VillageRecord` instances. The existing
`VillageWorkCellTest.identicalTargetsInSeparateVillagesDoNotShareFarmerLeases`
tests that behavior directly.

That API has no profession key. The current lumberjack furnace path in
`LumberjackGoal` finds a nearest usable furnace and does not reserve a shared
furnace pool by village, profession, owner, and generation. The current door,
crop, and tree claim APIs likewise do not establish the future profession-keyed
contract. No test-only model was added to manufacture that proof. Phase 2
therefore freezes the required scope and records the missing production claim
contract as a Phase 11 implementation gap; Phase 11 must add the real task/
claim API and production-backed tests proving that identical targets in two
professions do not share or release each other's leases.

## Frozen decision review

| Boundary | Frozen Phase 2 decision | Current behavior / implementation status |
| --- | --- | --- |
| Mayor door repair | `WORK`; village + MAYOR + mayor UUID + door + generation | Current position-only exclusion is recorded as a known gap; no migration was performed. |
| Mayor governor follow | `IDLE`, immediately below `CORE` safety and above ordinary `WORK`/`IDLE`; village + MAYOR + mayor UUID + candidate + session generation | Current GoalSelector path remains inventoried for its scheduled migration. |
| Avoidance | `CORE`; villager + hazard + safety generation | Safety remains a frozen core boundary, not a new scheduler. |
| Begging | `WORK`; beggar UUID + donor UUID + session generation; cross-village donors forbidden | `VillagerGoalPackageIntegration.addIdleBehaviors` and `addMeetBehaviors` add no begging behavior; `addWorkBehaviors` adds `BegForFoodBehavior`. The behavior currently lacks the same-village filter. This is an authorized retained later-phase package-placement change; same-village donor eligibility and removal of the movement adapter remain Phase 21 implementation requirements, not unresolved decisions. |
| Lumberjack furnace | `WORK`; one shared village-scoped LUMBERJACK pool keyed by furnace, owner UUID, and generation | Current code has no owner-bearing shared-pool reservation; this remains a Phase 17 gap. |
| `MoveToTargetSinkMixin` | Compatibility observer only; no task movement ownership or restoration | Existing interception remains transitional and scheduled for removal; it cannot be treated as a task scheduler. |

## Enforcement review

`NavigationArchitectureScanner` is a buildSrc helper used by
`verifyNavigationArchitecture`. It independently counts
`MovementRequestManager` and `VillagerMovementRequestBinding` references.
Direct navigation is inventoried by separate `moveTo`, `createPath`, `stop`,
and `getPath` counts, so replacing an observation with a mutation cannot keep a
single aggregate count passing. The scanner covers chained calls and aliases
declared as `PathNavigation`, `GroundPathNavigation`, or `var`, including
field and method-parameter aliases. GoalSelector registration is receiver
independent. PATH checks cover direct and aliased `setMemory`/`eraseMemory`.

The helper is a lexical scanner, not a complete Java parser or data-flow
analyzer. It deliberately covers the declaration forms present in this
production tree—chained calls, typed locals/fields/parameters, `var` aliases
initialized from `getNavigation()`, and typed PATH aliases. It does not claim
to resolve an alias returned by an arbitrary helper method, cast, or other
future syntax; introducing such a form requires extending this helper with a
parser-backed rule and a permanent fixture before it can be allowlisted.

Owner IDs are validated against a canonical set built from real producer,
behavior, request-owner, navigation, registrar, claim, state, and exception
inventory records; source ownership and reference rows do not define that set.
Reference categories are checked against compatible owner categories, and an
allowlisted source without an inventory explanation fails.

The permanent in-memory scanner tests cover rejection of every requested
prohibited form and acceptance of documented transitional callers,
non-villager exceptions, and read-only observations. The architecture verifier
invokes them before scanning the 299 production sources.

## Evidence commands

The following commands were run successfully for this correction set:

```text
.\gradlew -p buildSrc test --no-daemon --no-configuration-cache --console=plain
.\gradlew verifyNavigationArchitecture --no-daemon --no-configuration-cache --console=plain
.\gradlew :core:test --no-daemon --no-configuration-cache --console=plain
.\gradlew test --no-daemon --no-configuration-cache --console=plain
.\gradlew verifyRegressionCoverage --no-daemon --no-configuration-cache --console=plain
.\gradlew verifyPortSetup --no-daemon --no-configuration-cache --console=plain
.\gradlew :1.21.1:compileJava :1.21.4:compileJava --no-daemon --no-configuration-cache --console=plain
git diff --check
git status --short
```

The buildSrc report records 20 passing scanner tests. The generated report is
at `build/reports/phase2/navigation-ownership.md`; it contains 17 producer
rows, 11 transient claim scopes, 19 operation-specific direct-navigation
entries, and the exact transitional and GoalSelector inventories.

The developer has supplied the separate Phase 1 ordinary-world result, but has
not yet confirmed opening and inspecting the Phase 2 ownership report. The
exact report checklist and required approval are therefore still recorded as
`BLOCKED` in M2.1; the Phase 1 manual item is not being conflated with Phase 2
report inspection.

## Fresh correction-set review

The first exact-commit CI attempt for this correction was
[Build run 39725293877](https://github.com/Orange1861/Emerald-Capitalism/actions/runs/39725293877)
on commit `df8efc416d38d40111ac0fe43feccdd582299a94`; its uploaded artifact is
ID `12402398133` with SHA-256
`06ad4b55fe2cb2892504393f0508f668d96623a51eda12cecf7fbf40cfd7e295`.
The architecture verifier passed and the corrected begging relationship was
present in the artifact, but the separate Phase 1 evidence gate found one
unassigned 1.21.4 runtime failure. This does not change the Phase 2 ownership
conclusions or the regression baseline; a new exact-commit workflow is being
obtained before recording the automated Phase 1 gate as PASS.

The architecture verifier was rerun against the current correction-set
worktree revision `48588dfef9806aba0289ef03c2c507e23e0e7d0a` and regenerated
`build/reports/phase2/navigation-ownership.md`. It reports 17 producers, 11
transient claim scopes, and 299 production sources. The generated report
visibly contains `farmer-pumpkin-work-cell-pipeline`,
`farmer-assignment-excludes-bank-employees`, the old per-farmer
`HarvestPumpkinGoal` scanner as a Phase 15 legacy path, and the missing bank
employee eligibility check as a Phase 11 known violation.

For the begging producer, the corrected manifest now links
`begging-social-task` to the registered `beg-for-food` Brain behavior and
records the current scheduler as Brain's WORK package plus only the
transitional `MovementRequestManager` movement adapter. The producer sources
explicitly include `BegForFoodBehavior.java`,
`VillagerGoalPackageIntegration.java`, and `VillagerGoalPackagesMixin.java`.
The permanent scanner accepts the matching Brain WORK/adapter relationship and
rejects the stale IDLE/MEET-vs-WORK relationship with an explicit mismatch.

The corresponding source review confirms that `VillageRecord.isEligibleFarmer`
currently checks liveness, age, profession, village membership, and bounds but
does not consult authoritative bank employment. Bank-employee exclusion is
therefore a documented Phase 11 production gap, not an implemented Phase 1/2
feature. The frozen pumpkin pipeline remains a Phase 15 gameplay decision and
no Phase 15 implementation was added.

This is a fresh agent semantic self-review of source and generated evidence,
not an independent review and not developer sign-off. M2.1 remains
`BLOCKED`: the developer must open the generated report, inspect every
producer row and the exact columns listed in the Phase 2 ledger, confirm the
frozen pumpkin/bank decisions and Phase 11/15 ownership, and record the
two-target manual result explicitly.

## Final correction-set fixture review

The remaining Phase 1 selector fixture is outside the ownership manifest and
does not change any Phase 2 producer decision. Its source review confirms
that the real-selector test now establishes server-player registration, bank
association, and the contested-candidate grace transition synchronously before
one real damage event. It then waits for the ordinary Mob selector and cleans
up the owned synthetic player; it does not invoke `targetSelector.tick` or
change the 200-tick test timeout. The fresh 1.21.1 and 1.21.4 full-suite logs
show the dedicated batch completing without the suspect identity in the
required-failure list.

The Phase 2 report was regenerated after the local architecture verifier passed
with 17 producers, 11 transient claim scopes, and 299 production sources. The
report remains an agent-generated artifact, not independent review or
developer approval; M2.1 remains `BLOCKED` pending the exact inspection and
confirmation recorded in the ledger.

The final pushed commit `cffd0823b1302a782719f9665da69af612a47fee` is covered
by [workflow run 39719238024](https://github.com/Orange1861/Emerald-Capitalism/actions/runs/39719238024);
its uploaded artifact ID is `12400252262`. The CI architecture verification
passed and the generated report is included in that artifact. This remains an
agent semantic review, not independent review or developer approval.

## Current exact-commit CI evidence

The current workflow [39726266307](https://github.com/Orange1861/Emerald-Capitalism/actions/runs/39726266307)
completed successfully for commit `683d7dc9c9e510bf8c57e1cf736d6e6300f402d3`.
Artifact `12403281903` has SHA-256
`de154db213007f411535d47ad63d42759c4b5581d48099c5be514b769dff7ae1`.
The workflow's architecture verifier passed, the Phase 1 evidence validator
reported `infrastructureGate=PASS`, `baselineReview=PASS`, and no evidence
errors, and the generated report in the artifact contains the corrected
begging producer relationship. This is automated evidence only; M2.1 remains
BLOCKED until the developer inspects the report and sends the exact approval.
