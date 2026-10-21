# Phase 2 ownership review

This is Luna's independent semantic review for Phase 2. It is intentionally
separate from `gradle/navigation-architecture.json`, the verifier, and the
tracked requirement ledger at
[`phase-2-ownership-ledger.md`](phase-2-ownership-ledger.md). It is not a
developer sign-off.

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
crop, and tree claim APIs likewise do not establish the complete Phase 2
profession-isolated contract. No test-only model was added to manufacture
that proof, so A2.2 and G2.9 are `BLOCKED` until the later claim design exists.

## Frozen decision review

| Boundary | Frozen Phase 2 decision | Current behavior / implementation status |
| --- | --- | --- |
| Mayor door repair | `WORK`; village + MAYOR + mayor UUID + door + generation | Current position-only exclusion is recorded as a known gap; no migration was performed. |
| Mayor governor follow | `IDLE`; village + MAYOR + mayor UUID + candidate + session generation | Current GoalSelector path remains inventoried for its scheduled migration. |
| Avoidance | `CORE`; villager + hazard + safety generation | Safety remains a frozen core boundary, not a new scheduler. |
| Begging | `IDLE`; beggar UUID + donor UUID + session generation; cross-village donors forbidden | `BegForFoodBehavior` currently lacks the same-village filter. This is a frozen Phase 21 implementation requirement, not current behavior and not an unresolved decision. |
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

The developer has not yet confirmed opening and inspecting that report. The
exact manual checklist and required confirmation are therefore recorded as
`BLOCKED` in M2.1 rather than being represented as completed inspection.
