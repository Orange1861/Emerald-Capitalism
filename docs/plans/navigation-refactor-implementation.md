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

The final R5 verification on 2026-10-14 used Java 21.0.9 (Eclipse Adoptium)
with the project Gradle wrapper. The checked-out R5 commit is
`e05ae933f153d552afd47c8939b42736fcba4137`; the controlled R4 comparison
commit is `954713348362c7fc18703fdd04d91615551bf949`.

The exact functional commands were:

```text
.\gradlew test verifyRegressionCoverage --no-daemon --no-configuration-cache --console=plain
.\gradlew :1.21.1:compileJava --rerun-tasks --no-daemon --no-configuration-cache --console=plain
.\gradlew :1.21.1:runGameTestServer --no-daemon --no-configuration-cache --console=plain
.\gradlew :1.21.4:runGameTestServer --no-daemon --no-configuration-cache --console=plain
git diff --check
```

The JVM suite passed: core tests plus both version test source sets. The
coverage gate verified 7 risk boundaries and 227 discovered GameTests. Both
supported targets completed all 226 required GameTests with no required
failures. The passing suites include the real job-block journeys, ladder exit
and two-villager ladder cases, unmanaged-route handoff, profession work, the
Emeraldsmith processor/construction journey, Mayor bank/door repair, golem
obstruction, and server-stop lifecycle accounting.

The uninstrumented functional snapshots were:

| Target | Required tests | GameTest runtime | Result |
| --- | ---: | ---: | --- |
| 1.21.1 | 226 | 2.483 min | all required tests passed |
| 1.21.4 | 226 | 2.047 min | all required tests passed |

The counters are emitted in the server shutdown log. With detailed timing
disabled and event counting enabled, the movement lifecycle snapshots were:

| Target | Submissions | Replans | Preemptions | Rejections | Arrived | Retries | Abandoned | Failures | Unloaded / shutdown |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1.21.1 | 16 | 0 | 2 | 0 | 9 | 6 | 1 | 2 | 1 / 1 |
| 1.21.4 | 16 | 0 | 2 | 1 | 9 | 8 | 1 | 2 | 1 / 1 |

The corrected diagnostic run used
`-Demeraldcapitalism.performanceTiming=true` and
`-Demeraldcapitalism.performanceEvents=true` through `JAVA_TOOL_OPTIONS`.
On 1.21.4 it recorded 592 manager-tick calls, 16.253 ms total, 0.027 ms
average, 0.007 ms p50, 0.064 ms p95, 0.141 ms p99, and 3.650 ms maximum.
The same run recorded 404 behavior-registry calls, 8.114 ms total, and
0.020 ms average. It exposed two timing-sensitive test assertions (the
begging journey deadline and an exact boat-preemption count); the ordinary
functional runs above remained fully green. These diagnostic numbers are
therefore performance evidence, not an additional green functional gate.

The R4 diagnostic snapshot recorded 214 manager calls, 4.823 ms total,
0.023 ms average, and 0.219 ms maximum. R5 adds seven long-running real
journey regressions, eight additional required tests, lifecycle accounting,
and broader path instrumentation, so the full-suite runtimes and manager-call
totals are not apples-to-apples. The measured R5 manager average is comparable
but not lower in this sample. Safety is proven by the two full green suites;
a strict whole-suite speedup over R4 is not claimed until an identical,
instrumentation-neutral workload is available. No allocation profiler was
run, so allocation totals remain unmeasured.

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
