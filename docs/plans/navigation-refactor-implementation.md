# Incremental navigation refactor implementation

This note records the implemented contract for the revised navigation plan. It
is intentionally separate from the strategy documents: it describes the code
that is present in the supported source set, not a future design.

## Ownership boundary

Villager task code owns intent. `MovementRequestManager` owns the transient
owner/generation token and the handoff boundary. Minecraft's
`MoveToTargetSink` remains the route planner and path executor. A migrated task
submits a `MovementRequestSpec`, then only reasserts its `WALK_TARGET` and
optional `LOOK_TARGET` while its handle is current. It does not call
`PathNavigation.moveTo` or mutate `PATH` during ordinary task ticks.

Requests compare owner, destination identity, exact destination, speed,
arrival policy, traversal policy, priority, and optional look target. An
unchanged submission is idempotent. A speed-only or nearby-destination change
creates a new generation; only a strictly higher-priority owner can preempt a
different active owner. Equal- and lower-priority owners are rejected. Cleanup
is handle-guarded, so delayed cleanup from an old task cannot stop a newer
request aimed at the same block.

Task intent, route state, and traversal state are separate. A route ending is
not task completion. Ladder traversal may temporarily stop the live route, but
it leaves the task request memory available for retry and never teleports the
villager into the exit block. Ladder claims are dimension-aware and are
released on entity leave, level unload, and server stop.

## Migrated production callers

The following villager producers use the bridge:

- pumpkin harvest, farmland repair, banker positioning, inventory-bank visits;
- emerald processor and golem construction;
- mayor door repair and moving-target candidate following;
- lumberjack job-site, tree, and production-furnace movement;
- hostile-player flee, zombie-plague avoidance, boat avoidance, and begging.

The wandering trader and Emerald Skrimisher/golem families retain their
entity-specific goal-driven executor where they do not use the villager brain
sink. Their shared path/ladder geometry remains in the narrow navigation
helpers. Skrimisher bank delivery no longer writes unused villager brain
movement metadata; its direct goal navigation remains intentional.

## Future villager behavior API

`VillagerBehaviorDefinition` is the registration unit. A definition declares a
stable id, eligibility predicate, required controls, memories it owns,
priority, interruption policy, and a `VillagerBehaviorLifecycle`. Definitions
are shared and deterministic; `VillagerBehaviorRegistry` stores only
per-villager bindings. `refresh` is idempotent and is called on join and at a
bounded tick cadence so profession changes, enablement changes, and brain
refreshes do not duplicate bindings. Conflicting controls or owned memories
are resolved by priority; lower-priority definitions are suspended or canceled
according to their interruption policy. Bound definitions also receive a small
per-tick callback for request-memory maintenance; route planning still belongs
to the vanilla sink. Removal, replacement, entity leave, and server shutdown
invoke lifecycle cancellation.

The worked extension is the test-only definition in
`VillagerBehaviorExtensionGameTests`; it declares MOVE and WALK_TARGET
ownership, submits a `MovementRequestSpec`, survives refresh and profession
changes, and cleans up its request through the lifecycle. A future task should
use that definition shape and keep reservations and economic effects in the
task rather than in the movement manager.

## Diagnostics and performance evidence

Set the JVM property `emeraldcapitalism.navigationDiagnostics=true` for
opt-in transition, cleanup, and route-generation logs. The counters in
`PerformanceTimingCounters` record request submissions, replans, preemptions,
rejections, terminal outcomes, and expensive searches without logging every
tick. Detailed `nanoTime` measurements can be disabled with
`-Demeraldcapitalism.performanceTimingDisabled=true`; event counters remain
available when timing is enabled. Planning helpers retain bounded candidate
attempts and do not force-load chunks.

The final verification report must include equivalent multi-mob workload
snapshots for both supported targets. A faster run is not considered an
improvement if it only abandons more work; successful task outcomes and retry
counts are part of the comparison.

The final-tree runs on 2026-10-13 used Java 21.0.9 (Eclipse Adoptium) with
the project Gradle wrapper and these exact commands:

```text
.\gradlew test --no-daemon --no-configuration-cache --console=plain
.\gradlew :1.21.1:compileJava :1.21.4:compileJava --no-daemon --no-configuration-cache --console=plain
.\gradlew :1.21.1:runGameTestServer --no-daemon --no-configuration-cache --console=plain
.\gradlew :1.21.4:runGameTestServer --no-daemon --no-configuration-cache --console=plain
git diff --check
```

The JVM suite passed (core tests plus both version test source sets). Both
version compile tasks passed. The final-tree GameTest runs had 216 tests per
target; all new registry, movement-ownership, stale-memory, preemption,
look-ownership, and Mayor-follow tests passed on both targets. The named
Skrimisher periodic-deposit regression also passed after tightening its test
village AABB so bounded wandering cannot enter the surrounding stone wall.

| Target | GameTest runtime | Manager time (calls / total / avg / max) | Registry time (calls / total / avg / max) | Arrived / abandoned | Retries | Replans | Preemptions | Rejections | Sink path-plan attempts (success / failure) | Remaining failures |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 1.21.1 | 60.6 s | 232 / 10.854 / 0.047 / 4.769 ms | 205 / 2.365 / 0.012 / 0.358 ms | 4 / 15 | 19 | 8 | 3 | 2 | 30 (19 / 11) | `resourcescanincludesnearestemeraldprocessorinventory`; one non-required `emeraldskrimisherusesgolemassistedjumpattack` flake in this run |
| 1.21.4 | 49.61 s | 229 / 9.544 / 0.042 / 4.222 ms | 217 / 2.096 / 0.010 / 0.347 ms | 4 / 15 | 15 | 8 | 3 | 2 | 26 (14 / 12) | `resourcescanincludesnearestemeraldprocessorinventory` |

The counters are emitted in the server shutdown log. Logical submissions,
replans, and retries are separate from actual `MoveToTargetSink` path-plan
attempts, which are recorded by the sink mixin. No allocation profiler was run,
so allocation totals are not claimed. The remaining resource-scan failure is
also present on the parent baseline and does not touch the migrated villager
movement bridge. The one-shot 1.21.1 jump-attack failure is unrelated to this
change set; the parent 1.21.1 run passed it and the failure was not among the
required navigation regressions.

For the controlled comparison, commit `94e102e3` was run on the same Windows
host and Java 21.0.9 toolchain for both targets. It ran 210 tests, with only
`resourcescanincludesnearestemeraldprocessorinventory` failing on each target.
Its server completion times were 4.884 s (1.21.1) and 5.151 s (1.21.4), but
the parent does not contain the six new long-running regression tests and does
not emit the new movement counters. Those runs therefore establish the
baseline failure set, not a valid performance-improvement claim; allocation
counts remain unmeasured.

### Likely cost relative to the pre-refactor code

The source-level migration removes eight direct villager `moveTo` calls from
the audited goal set and reduces direct `WALK_TARGET` writers there from 15 to
the single manager-owned writer. One helper-mediated writer remains in
`BankAwarePotentialJobSiteBehavior`, but it is limited to pre-profession
potential-job-site assignment rather than an active migrated work journey.
This should reduce competing route starts, stale-path cleanup, and repeated
executor fights during active work.

The steady-state path now keeps the immutable request spec in each movement
binding and uses an allocation-light unchanged-intent maintenance path.
Matching block-position walk/look memories are left in place instead of
allocating replacement trackers every tick; meaningful target changes still
stop only manager-owned executor state and let the vanilla sink plan again.
Manual entity-look producers explicitly mark their look memory as owned so
handoff cleanup does not leave stale focus behind. The registry is skipped
entirely when no extension definitions are registered, its shared priority
ordering is cached, and ordinary per-villager refresh/tick paths are not
guarded by a JVM-wide monitor. The shutdown snapshot now exposes manager and
registry time alongside completions, retries, replans, preemptions, rejections,
and abandoned tasks for future controlled comparisons.

## Test layers

Pure ownership rules live in `core` ledger tests. Minecraft GameTests retain
component coverage for ladder geometry and bounded candidate selection, and
the navigation regression suite is the place for normal-AI journeys that
assert actual world effects. Component tests may install a handcrafted path;
they are not evidence that normal brain/goal registration completed a task.

The supported Gradle targets are 1.21.1 and 1.21.4. Later Stonecutter entries
are metadata-only and are not activated by this refactor.
