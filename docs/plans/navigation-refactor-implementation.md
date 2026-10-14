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
rejections, and existing expensive searches without logging every tick.
Planning helpers retain bounded candidate attempts and do not force-load
chunks.

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
version compile tasks passed. Each GameTest server ran 211 tests: 210 passed
and one unrelated required test failed, so the Gradle GameTest task exits 1.
The repaired navigation and extension journeys passed on both versions.

| Target | Runtime | Manager time | Registry time | Completed / abandoned | Retries | Replans | Preemptions | Rejections | Request plan attempts | Failure |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 1.21.1 | 43.89 s | 140 calls / 5.395 ms | 215 calls / 2.232 ms | 8 / 5 | 10 | 7 | 2 | 2 | 24 | `resourcescanincludesnearestemeraldprocessorinventory` |
| 1.21.4 | 39.25 s | 140 calls / 5.632 ms | 226 calls / 2.861 ms | 8 / 5 | 10 | 7 | 2 | 2 | 24 | `resourcescanincludesnearestemeraldprocessorinventory` |

The counters are emitted in the server shutdown log and cover the complete
disposable GameTest workload. “Request plan attempts” counts accepted logical
movement-plan transitions; vanilla sink pathfinding remains the route planner.
These are workload snapshots, not a controlled before/after benchmark: the
repository has no stored baseline run or fixed hardware benchmark harness.
The remaining failure is pre-existing resource-scan behavior and does not
touch the migrated villager movement bridge.

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
