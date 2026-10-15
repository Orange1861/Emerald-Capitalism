# Deferred Non-Pathing Audit Findings

## Status

- Audit baseline: R8 (`21520ad5`)
- Recorded: 2026-10-15
- Scope decision: the current stabilization effort is limited to villager pathing.
- This document is a backlog, not evidence that these findings have been fixed.

## Purpose

The R3-R8 revision cycle expanded beyond its original goal of stabilizing villager pathing. The findings below remain relevant, but they should not be included in the pathing-completion revision unless they directly prevent pathing verification. Each area should receive its own reproduction, scope, implementation plan, and acceptance criteria before work begins.

## 1. Incremental farm placement is not restart-safe

**Priority:** P1 correctness and world-integrity risk  
**Area:** Village generation and farms  
**Introduced before R8; still present in R8**

`VillageFarmPlacer.FarmPlacementTask` modifies terrain across multiple ticks while its phase, cursors, and captured state exist only in memory. `VillageGenerationPipeline.clearPendingWork()` discards active work during lifecycle shutdown. A stop, unload, or crash can therefore leave partially leveled or cleared terrain without a completed farm or a durable resume point.

Related concerns:

- A failed placement task is omitted from `placedBuildings`, but pipeline finalization can still call `markFarmsPlaced` for the village.
- A failed farm may consequently never be retried.
- Restarted planning can observe terrain already modified by the interrupted attempt.
- `StructureTemplate.placeInWorld(...)` remains a monolithic operation despite the surrounding incremental scheduler.

Evidence:

- [`VillageFarmPlacer.java`](../../src/main/java/com/orangevillager61/emeraldcapitalism/world/villagefarms/VillageFarmPlacer.java)
- [`VillageGenerationPipeline.java`](../../src/main/java/com/orangevillager61/emeraldcapitalism/world/village/pipeline/VillageGenerationPipeline.java)

Recommended future work:

1. Decide whether to revert the incremental farm optimization or make it durably resumable.
2. Never mark farms complete when a required placement failed.
3. Make every mutating phase idempotent or provide rollback.
4. Add interruption tests at every mutating phase, including save/reload and forced placement failure.
5. Measure the worst single-tick cost of template placement.

## 2. Bank invalidation scales with every loaded bank

**Priority:** P2 performance risk  
**Area:** Banks and storage  
**Not part of pathing stabilization**

Chest and processor mutations call `markCachesDirtyNear`, which iterates the full set of loaded banks before applying distance checks. The cost therefore grows with all loaded banks in the dimension, even when only one bank is near the changed block.

Recommended future work:

1. Add deterministic counters for banks examined per mutation.
2. Measure 1, 16, 64, and 128 loaded-bank scenarios.
3. If scaling is material, index banks by chunk or section and inspect only nearby buckets.

Evidence:

- [`BankBlockEntity.java`](../../src/main/java/com/orangevillager61/emeraldcapitalism/block/entity/BankBlockEntity.java)

## 3. Bank getters can perform synchronous topology or aggregate refreshes

**Priority:** P2 performance and latency risk  
**Area:** Banks and storage  
**Partially caused or amplified by R8 same-tick consistency changes**

Consistency-sensitive getters call `ensureInventoryTotalsCurrent`. When topology is dirty, an ordinary read can synchronously scan the bank search volume. Capacity methods may also refresh aggregate totals before scanning chest slots for capacity, producing redundant work and making nominal reads unexpectedly expensive.

Recommended future work:

1. Separate topology, chest-total, processor-total, and capacity dirty states.
2. Ensure capacity-only reads do not rebuild unrelated aggregate totals.
3. Add repeated `mutate -> read -> mutate -> read` tests in the same server tick.
4. Record full scans, aggregate refreshes, chest slots inspected, and capacity-cache invalidations.

## 4. Bank malformed-data coverage is incomplete

**Priority:** P2 regression-test gap  
**Area:** Bank persistence  
**R8 improved the implementation but did not exhaustively prove it**

R8 decodes persisted fields independently so one malformed field should not erase valid neighboring state. Current coverage does not independently corrupt every field or field group, and some gameplay assertions allow more than one outcome.

Recommended future work:

1. Corrupt each independently decoded field in isolation.
2. Verify the malformed field receives its documented default.
3. Verify every valid neighboring field survives exactly.
4. Cover normalization rules separately from decode-failure behavior.

## 5. The regression manifest proves existence, not behavioral coverage

**Priority:** P3 tooling limitation  
**Area:** Test infrastructure  
**Not a release blocker by itself**

`verifyRegressionCoverage` discovers GameTest method names and verifies that manifest references point to existing methods. It cannot determine whether a test naturally exercises the stated risk, reaches the relevant production path, or contains meaningful assertions.

Recommended future work:

1. Continue using the manifest as an inventory only.
2. Require human review mapping each risk to a reproduction and observable assertion.
3. Do not build a complex semantic coverage system unless repeated false coverage claims justify the maintenance cost.

Evidence:

- [`stonecutter.gradle`](../../stonecutter.gradle)
- [`regression-coverage.json`](../../gradle/regression-coverage.json)

## 6. Whole-mod performance claims need comparable baselines

**Priority:** P2 process and measurement gap  
**Area:** Performance verification  
**Outside the pathing-only performance pass**

Existing R7/R8 timing notes compare different test counts and substantially different command conditions. They are useful diagnostics but cannot establish a general speedup or regression.

Recommended future work:

1. Use identical commits, worlds, test selections, JVM settings, and warm-up rules for comparisons.
2. Prefer deterministic operation counters for CI and use wall-clock measurements as supporting evidence.
3. Maintain separate scenarios for pathing, bank activity, village generation, and idle-server cost.
4. Record raw results rather than only conclusions.

Evidence:

- [`navigation-refactor-implementation.md`](navigation-refactor-implementation.md)

## 7. Generic villager behavior registration API

**Priority:** Deferred architecture proposal  
**Area:** Behavior extensibility  
**Not required to stabilize current pathing**

The earlier navigation implementation note proposed `VillagerBehaviorDefinition`
as a generic registration unit with eligibility, control and memory ownership,
priority, interruption policy, lifecycle callbacks, refresh behavior, and
per-villager bindings. A test-only extension demonstrates part of this shape,
but the generic framework is not required to complete the existing movement
request contract.

Building this framework during pathing stabilization would expand the task from
repairing current journeys into designing a future public behavior system. It
could also force another ownership or lifecycle redesign before the present
contract has been proven stable.

Recommended future work:

1. Start only when a concrete new behavior requires the extension point.
2. Write the consumer's requirements and compatibility contract first.
3. Preserve `MovementRequestManager` as the route-request boundary rather than
   merging behavior registration with navigation execution.
4. Prove that registration refresh, profession changes, conflicts, removal,
   entity leave, and shutdown cannot duplicate bindings or leak memories.
5. Keep reservations and economic effects in the behavior, not in the movement
   manager.

The current test-only definition may remain as experimental coverage, but it is
not a completion criterion for the pathing stabilization release.

## Deferred-work rules

When one of these items is scheduled:

1. Give it a separate branch or bounded work package.
2. Reproduce the problem before changing production code.
3. Define player or server impact and measurable acceptance criteria.
4. Do not combine it with unrelated pathing changes.
5. Require both supported versions and CI to pass before closing it.
6. Update this document with the fixing commit or pull request instead of silently deleting the finding.

