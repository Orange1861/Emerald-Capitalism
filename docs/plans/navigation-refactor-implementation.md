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

The final R7 verification on 2026-10-14/15 used tested commit SHA `46939820`
(R7; repairs are in the working tree), Java 21.0.9
(`Eclipse Adoptium OpenJDK 64-Bit Server VM 21.0.9+10-LTS`), and the project
Gradle wrapper. Generated server output is not part of the source evidence.

The exact functional commands were:

```text
.\gradlew test verifyRegressionCoverage --no-daemon --no-configuration-cache --console=plain
.\gradlew :1.21.1:compileJava --no-daemon --no-configuration-cache --console=plain
.\gradlew :1.21.1:runGameTestServer --no-daemon --no-configuration-cache --console=plain
.\gradlew :1.21.4:runGameTestServer --no-daemon --no-configuration-cache --console=plain
git diff --check
```

The JVM suite passed: core tests plus both version test source sets. The
coverage gate verified 7 risk boundaries and 242 discovered GameTests. Both
supported targets passed all 240 required GameTests; no optional GameTests
were registered or reported by either server run. The passing suites include
the real job-block journeys, begging arrival and blocked-donor journeys,
normal-AI ladder ascent/descent, intermediate landing, route continuation,
blocked-top and stale-exit recovery, two-villager contention, ladder
destruction, entity unload/reload, unmanaged route handoff, profession work,
the Emeraldsmith processor/construction journey, same-tick bank reads and
refresh coalescing, Mayor bank/door repair and repair-race refunding, golem
obstruction, and production-boundary server-stop lifecycle accounting.

The functional results were:

| Target | Required tests | GameTest runtime | Result |
| --- | ---: | ---: | --- |
| 1.21.1 | 240 | 4.326 min command wall time | all required tests passed; 0 optional |
| 1.21.4 | 240 | 3.928 min command wall time | all required tests passed; 0 optional |

The R6 parent checkout was also run as a history check. Its server log
reported 228 required GameTests in 5.691 s with 1 required failure (the
original begging journey), so it is not a passing functional baseline.

The functional gates above are separate from diagnostic runs. Shutdown logs
may emit movement and bank counters, but those counters are not used as a
substitute for the real journeys. In particular, ladder acceptance requires a
normal villager with normal goal registration to harvest the real target after
climbing; the component ladder tests that install handcrafted paths remain
component-only coverage.

The bank stress journey records each bank's refresh count and verifies that
repeated same-tick processor mutations coalesce to one aggregate refresh per
bank. The lifecycle tests use a loaded-entity/session token, bound terminal
history, duplicate cleanup, unload/reload, delayed callbacks, and the actual
server-stopping production boundary. Mayor diagnostics distinguish an already
repaired door from placement failure, and the race test verifies one claim
release and one material refund.

When detailed timing and event counting are explicitly enabled, the counters
are diagnostic snapshots only. They should be compared only across equivalent
instrumented workloads; the final functional commands intentionally do not
present those counters as acceptance evidence. The common multi-villager
ladder workload (`twoNormalVillagersContendForOneLadderAndOneWorkTarget`)
passed in both instrumented R7-parent and repaired R7 runs. The instrumented
R7-parent 1.21.1 full suite (237 required tests, GameTest runtime 5.793 s,
39 s command wall time) recorded 69 movement submissions, 28 retries, 256
ladder-segment scans, 1,536 scanned ladder blocks, 277 purge visits, and
28.586 ms of movement-manager time. The repaired R7 1.21.1 full suite (240
required tests, 4.241 min command wall time) recorded 73 submissions, 62
retries, 33 segment scans, 198 scanned blocks, 238 bounded purge visits, and
21.971 ms of movement-manager time. Because the repaired suite also contains
three new regression journeys and the Gradle task has no supported per-test
filter, these are reported as workload snapshots; they are not a claim of a
wall-clock speedup based on test counts.

No performance improvement claim is made from test counts or from an
instrumented diagnostic run; the repaired behavior is established by the
functional assertions and their durable world effects.

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
