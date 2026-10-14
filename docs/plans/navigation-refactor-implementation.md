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
tick. Detailed `nanoTime` measurements are opt-in with
`-Demeraldcapitalism.performanceTiming=true`, and event counters are opt-in
with `-Demeraldcapitalism.performanceEvents=true`; both are disabled by
default. The movement-manager disabled path does not read the clock or create
timing lambdas. Planning helpers retain bounded candidate attempts and do not
force-load chunks.

The final verification report must include equivalent multi-mob workload
snapshots for both supported targets. A faster run is not considered an
improvement if it only abandons more work; successful task outcomes and retry
counts are part of the comparison.

The final-tree runs on 2026-10-14 used Java 21.0.9 (Eclipse Adoptium) with
the project Gradle wrapper and these exact commands:

```text
.\gradlew test --no-daemon --no-configuration-cache --console=plain
.\gradlew :1.21.1:compileJava :1.21.4:compileJava --no-daemon --no-configuration-cache --console=plain
.\gradlew :1.21.1:runGameTestServer --no-daemon --no-configuration-cache --console=plain
.\gradlew :1.21.4:runGameTestServer --no-daemon --no-configuration-cache --console=plain
git diff --check
```

The diagnostic snapshots additionally set
`-Demeraldcapitalism.performanceTiming=true` and
`-Demeraldcapitalism.performanceEvents=true` through `JAVA_TOOL_OPTIONS`.

The JVM suite passed (core tests plus both version test source sets). Both
version compile tasks passed. The final-tree GameTest runs had 218 tests per
target; all new registry, movement-ownership, stale-memory, preemption,
look-ownership, and Mayor-follow tests passed on both targets. The named
Skrimisher periodic-deposit regression also passed. The measurement runs
reported the following final-tree snapshots:

| Target | GameTest runtime | Manager time (calls / total / avg / max) | Registry time (calls / total / avg / max) | Arrived / abandoned | Retries | Replans | Preemptions | Rejections | Sink path-plan attempts (success / failure) | Remaining failures |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 1.21.1 | 1.308 min | 253 / 3.166 / 0.013 / 0.106 ms | 459 / 4.466 / 0.010 / 0.418 ms | 2 / 12 | 12 | 5 | 3 | 2 | 12 (10 / 2) | `resourcescanincludesnearestemeraldprocessorinventory`; unrelated `viruszombiealwaysdropstransferredequipment` |
| 1.21.4 | 1.505 min | 237 / 4.169 / 0.018 / 0.218 ms | 441 / 6.066 / 0.014 / 0.524 ms | 2 / 11 | 9 | 5 | 3 | 2 | 10 (8 / 2) | `resourcescanincludesnearestemeraldprocessorinventory`; unrelated `bankgolemrealselectortargetscontestedgovernorcandidatethroughobstruction` |

The counters are emitted in the server shutdown log. Logical submissions,
replans, and retries are separate from actual `MoveToTargetSink` path-plan
attempts, which are recorded by the sink mixin. The measurement-run failures
listed above are unrelated to the migrated villager movement bridge except for
the navigation regressions, which passed. The resource-scan failure is also
present on the parent baseline.

An uninstrumented rerun on the exact final tree also completed all 218 tests on
both targets; its remaining required failures were the resource-scan test plus
the unrelated Skrimisher periodic-deposit test on 1.21.1, and the unrelated
bank-golem selector test on 1.21.4. The movement-manager regression tests
remained green.

For the controlled comparison, commit `25d1b9bd` was run on the same Windows
host and Java 21.0.9 toolchain with the same two GameTest commands:

| Target | Parent tests / GameTest runtime | Manager time (calls / total) | Registry time (calls / total) | Arrived / abandoned | Retries | Replans | Preemptions | Rejections | Sink path-plan attempts | Remaining failures |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 1.21.1 | 211 / 5.133 s | 136 / 5.874 ms | 187 / 2.092 ms | 8 / 5 | 9 | 7 | 2 | 2 | 24 | `resourcescanincludesnearestemeraldprocessorinventory` |
| 1.21.4 | 211 / 5.123 s | 157 / 5.177 ms | 223 / 2.601 ms | 9 / 5 | 12 | 7 | 2 | 2 | 25 | `resourcescanincludesnearestemeraldprocessorinventory` |

The repaired tree adds seven long-running regression tests, so the full-suite
wall times above are not an apples-to-apples speedup claim. They are the same
commands and host/toolchain, with the changed test population called out
explicitly. The parent does not emit the repaired tree's path-plan success /
failure and unload / shutdown fields. No allocation profiler was run, so
allocation totals remain unmeasured.

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
