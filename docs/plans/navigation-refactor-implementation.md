# Navigation stabilization implementation and completion plan

## Status and purpose

This document defines the implemented villager-navigation contract and the
remaining work required to finish the pathing stabilization effort. It is not
a whole-mod audit, a future behavior-framework proposal, or evidence that the
current tree has passed final verification.

- Current implementation baseline: R8 (`21520ad5`).
- Historical passing evidence: the local R7/R8-era runs recorded below.
- Completion status: not complete until the Phase 5 gates pass for the exact
  release commit in CI.
- Supported targets: Minecraft 1.21.1 and 1.21.4. Later Stonecutter entries
  remain metadata-only for this effort.

## Scope

### Included

- movement-request ownership, priority, replacement, and cancellation;
- arrival, failure, timeout, retry, and terminal-outcome semantics;
- guarded cleanup and restoration of movement-owned brain memories;
- vanilla `MoveToTargetSink` handoff and route execution;
- doors and obstacles when they block a migrated villager journey;
- ladder ascent, descent, exits, contention, invalidation, and cleanup;
- begging only where emerald transfer depends on movement completion;
- vanilla farmer crop-work and wanted-item journeys where movement ownership
  can prevent otherwise eligible behavior from reaching its target;
- continuity of the existing lumberjack tree-work journey while it advances
  from one selected log to the next;
- normal-AI gameplay tests and pathing-specific performance work;
- the build and CI work strictly required to verify these behaviors on both
  supported targets.

### Excluded

- bank persistence, inventory refresh, capacity caching, and bank-wide
  performance work;
- farm placement, terrain modification, and village-generation scheduling;
- general saved-data hardening;
- a future generic villager behavior registration API;
- whole-mod performance optimization or unrelated gameplay features.

Excluded findings are tracked in
[`deferred-non-pathing-audit-findings.md`](deferred-non-pathing-audit-findings.md)
and must not be pulled into this effort merely because they are discovered
while pathing work is in progress.

## Implemented ownership boundary

Villager task code owns intent. `MovementRequestManager` owns the transient
owner/generation token and the handoff boundary. Minecraft's
`MoveToTargetSink` remains the route planner and path executor. A migrated task
submits a `MovementRequestSpec`, then only reasserts its `WALK_TARGET` and
optional `LOOK_TARGET` while its handle is current. It does not call
`PathNavigation.moveTo` or mutate `PATH` during ordinary task ticks.

Requests compare owner, destination identity, exact destination, speed,
arrival policy, traversal policy, priority, and optional look target. An
unchanged submission is idempotent. A speed-only or nearby-destination change
creates a new generation. Only a strictly higher-priority owner can preempt a
different active owner; equal- and lower-priority owners are rejected.

Cleanup is handle-guarded so delayed cleanup from an old task cannot stop a
newer request aimed at the same block. Movement-owned memories are restored or
removed according to the request lifecycle without erasing state owned by a
newer request.

Task intent, route state, and traversal state are separate. A route ending is
not automatically task completion. Terminal outcomes must distinguish at
least arrival, failure, cancellation, timeout, preemption, and abandonment.

Ladder traversal may temporarily stop the live route, but it leaves the task
request available for retry and never teleports a villager into the exit
block. Ladder claims are dimension-aware and must be released after natural
completion, cancellation, entity removal, level unload, and server stop.

## Migrated production callers

The following villager movement producers use the request bridge:

- pumpkin harvest and farmland repair;
- banker positioning and inventory-bank visits;
- emerald processor and golem construction;
- mayor door repair and moving-target candidate following;
- lumberjack job-site, tree, and production-furnace movement;
- hostile-player flee, zombie-plague avoidance, boat avoidance, and begging.

The wandering trader and Emerald Skrimisher/golem families retain their
entity-specific goal-driven executors where they do not use the villager brain
sink. Their direct navigation remains intentional. Shared geometry helpers do
not make those entities owners of villager brain movement state.

## Known pathing gaps

These player-observed behaviors require natural reproduction before any
production fix is selected:

### Vanilla farmer crop work

Ordinary farmers do not reliably path to mature crops to harvest and replant
them. The migrated pumpkin-harvest and farmland-repair goals do not prove that
Minecraft's vanilla crop behavior can acquire and retain the movement state it
needs to complete a normal farm-work journey.

### Wanted-item pursuit

Farmers do not reliably path to dropped seeds, and villagers can fail to move
toward other items they are otherwise willing and able to pick up. Eligibility
and navigation must be tested separately: failure is a pathing defect only
when `VillagerPickupCompat.wants(...)` is true, inventory space is available,
the item is reachable, and the ordinary wanted-item behavior still fails to
reach it.

### Lumberjack tree-work continuity

Lumberjacks can break one log, walk away, and later return without collecting
the rest of the selected tree. The goal advances `nextLogIndex` and completes
its movement request after reaching a work position, so the defect may be an
interaction between request completion, restored `WALK_TARGET` state, normal
goal scheduling, and selection of the next log. Existing focused tests create
and tick `LumberjackGoal` directly; they do not prove that a normally registered
AI goal remains at the tree and completes the full work loop.

## Pathing acceptance contract

The stabilization is complete only when normal gameplay demonstrates all of
the following without direct test intervention in the state being validated:

1. One movement owner controls a request at a time, with deterministic
   priority and replacement behavior.
2. Old cleanup cannot cancel a newer generation or remove its memories.
3. Arrival is reported only after the required destination policy is met.
4. Unreachable and stalled routes fail or time out, clean up, and retry only
   according to the caller's policy.
5. Doors and recoverable obstacles do not permanently strand migrated work
   journeys.
6. Villagers ascend and descend ladders, select valid exits, continue to the
   real task target, and release claims naturally.
7. Two villagers serialize use of a contested ladder without a test manually
   releasing a claim, canceling movement, or deleting either villager.
8. Begging transfers emeralds only after a completed movement leg; a naturally
   unreachable donor produces no transfer and leaves no stale request.
9. Entity removal, level unload, and server stop leave no live movement or
   ladder ownership associated with the ended session.
10. Stable routes do not perform an unbounded or full remaining-path scan on
    every villager AI tick.
11. An ordinary farmer with normal AI reaches a mature crop, harvests it, and
    replants it when valid farmland, seeds, inventory space, work time, and a
    reachable route are available.
12. A villager that reports it wants a reachable dropped item moves to it and
    picks it up; an unreachable item times out or is abandoned without leaving
    stale movement ownership.
13. A normally registered lumberjack continues its selected tree task across
    consecutive logs, collects the accepted drops, replants the tree, and does
    not walk away between logs unless a legitimate interruption occurs.

Component tests may install handcrafted paths to validate geometry helpers.
They must be labeled component coverage and cannot satisfy a normal-AI
acceptance item by themselves.

## Completion phases

Each phase is a separate review checkpoint. A phase must meet its gate before
the next begins. Production changes outside the scope above are prohibited.

### Phase 1 - Restore pathing verification

1. Reproduce and fix the current `verifyPortSetup` failure without disabling
   or weakening the task.
2. Align CI and local verification on the supported Java toolchain.
3. Compile both supported targets and run their pathing GameTests in CI.
4. Run unit tests, regression-manifest validation, applicable mixin audits,
   and `git diff --check`.
5. Preserve useful failure logs as CI artifacts without committing generated
   logs, worlds, caches, crash reports, or build output.

**Gate:** CI reaches and executes both supported GameTest targets. No gameplay
code changes belong in this phase.

### Phase 2 - Establish natural pathing tests

1. Map every acceptance-contract item to at least one observable assertion.
2. Replace the blocked-begging setup that repeatedly stops navigation and
   zeroes velocity with a genuinely unreachable but otherwise valid donor.
3. Replace ladder contention cleanup that calls `releaseAll`, cancels the
   owner, or discards it with two living villagers that complete naturally.
4. Retain handcrafted-path tests only as explicitly named component tests.
5. Add a normal farmer journey with a mature crop far enough away to require
   navigation. Assert harvesting and replanting as world effects; do not invoke
   the vanilla crop behavior directly or install its path.
6. Add wanted-item journeys for farmer seeds and representative items accepted
   by other villagers. First assert pickup eligibility, then verify natural
   movement and inventory transfer. Include full-inventory, rejected-item, and
   unreachable-item controls so policy failures are not mislabeled as pathing
   failures.
7. Add a normally registered lumberjack journey that observes the complete
   tree task without constructing or manually ticking `LumberjackGoal`. Assert
   that all selected logs are harvested, accepted logs enter inventory, the
   villager does not depart between consecutive reachable logs, and replanting
   occurs.
8. Add coverage for tall ladders, invalidated exits, path replacement, unload,
   and normal timeout/retry cleanup where coverage is absent.

**Gate:** each remaining failure is reproducible through ordinary production
behavior, and every passing test proves an observable outcome rather than a
test-hook state alone.

### Phase 3 - Apply minimal correctness fixes

Fix only failures reproduced in Phase 2. Known candidates are:

- connected ladders receiving different claims because segment discovery is
  truncated;
- claims or exits surviving after their natural request has ended;
- stale or obstructed exits failing to return control to normal navigation;
- incorrect terminal outcomes or retry accounting;
- movement-owned memories surviving cancellation or being removed from a
  newer request;
- door or obstacle recovery failing to resume the original task.
- vanilla farmer crop or wanted-item behavior losing access to `WALK_TARGET`
  because a completed, stale, or competing managed request still owns or
  restores movement state;
- lumberjack request completion or restored movement state pulling the
  villager away before it advances through the remaining selected logs.

Prefer the smallest change that preserves the ownership boundary. Do not add
new navigation features or redesign callers that already satisfy the
contract.

**Gate:** all Phase 2 tests pass on both supported targets, focused diffs have
been reviewed, and no unrelated production subsystem changed.

### Phase 4 - Measure and reduce pathing overhead

1. Measure request submissions, retries, terminal outcomes, route analyses,
   path nodes inspected, ladder-segment scans, and ladder blocks inspected.
2. Use identical multi-villager workloads before and after each optimization.
3. Confirm whether unchanged paths are rescanned on successive AI ticks.
4. If confirmed, cache ladder-route analysis by path identity, next-node
   progress, and relevant ladder invalidation state.
5. Invalidate only when the route advances beyond the cached segment, the
   path changes, or a relevant climbable block changes.

Wall-clock time is supporting evidence, not the sole acceptance criterion. A
faster run that abandons more requests is a regression.

**Gate:** equivalent before/after workloads show bounded repeated work while
successful outcomes and retry behavior remain correct on both targets.

### Phase 5 - Cross-version release validation

Run from a clean checkout of the exact proposed release commit:

```text
.\gradlew test verifyRegressionCoverage --no-daemon --no-configuration-cache --console=plain
.\gradlew verifyPortSetup --no-daemon --no-configuration-cache --console=plain
.\gradlew :1.21.1:compileJava --no-daemon --no-configuration-cache --console=plain
.\gradlew :1.21.4:compileJava --no-daemon --no-configuration-cache --console=plain
.\gradlew :1.21.1:runGameTestServer --no-daemon --no-configuration-cache --console=plain
.\gradlew :1.21.4:runGameTestServer --no-daemon --no-configuration-cache --console=plain
git diff --check
```

Also perform manual journeys for door recovery, ladder ascent/descent and
contention, movement cancellation/replacement, and reachable/unreachable
begging.

**Gate:** all required checks pass locally where available and in CI, no
generated artifacts are tracked, and the release report names the exact commit,
Java version, commands, results, pathing counter comparison, player-observable
fixes, and remaining limitations.

Do not begin another navigation revision after this gate unless a new,
reproducible pathing defect is reported.

## Diagnostics

Set `emeraldcapitalism.navigationDiagnostics=true` for opt-in transition,
cleanup, and route-generation logs. `PerformanceTimingCounters` records
request submissions, replans, preemptions, rejections, terminal outcomes, and
expensive searches without logging every tick.

Detailed timing is opt-in with
`-Demeraldcapitalism.performanceTiming=true`. Event counters are opt-in with
`-Demeraldcapitalism.performanceEvents=true`. Both remain disabled by default.
The disabled path must not read the clock or allocate timing lambdas.

Diagnostics do not replace gameplay assertions. Logs and counters explain why
a journey succeeded or failed; durable world effects establish correctness.

## Historical evidence and limitations

The recorded local verification on 2026-10-14/15 used Java 21.0.9 and the
project Gradle wrapper. The implementation note previously identified tested
commit `46939820` (R7) while also describing repairs and tests later present in
R8. Those results are retained as historical evidence only and are not final
verification of R8 or a future stabilization commit.

The recorded functional results were:

| Target | Required tests | Command wall time | Recorded result |
| --- | ---: | ---: | --- |
| 1.21.1 | 240 | 4.326 min | all required tests passed; 0 optional |
| 1.21.4 | 240 | 3.928 min | all required tests passed; 0 optional |

The coverage task reported 7 risk boundaries and 242 discovered GameTest
methods. Method discovery proves that named tests exist; it does not prove that
their setup is natural or their assertions satisfy the acceptance contract.

An instrumented 1.21.1 parent workload recorded 69 submissions, 28 retries,
256 ladder-segment scans, 1,536 ladder-block scans, 277 purge visits, and
28.586 ms of movement-manager time. A later, non-equivalent workload recorded
73 submissions, 62 retries, 33 segment scans, 198 ladder-block scans, 238 purge
visits, and 21.971 ms of movement-manager time. Because the suites differed,
these are diagnostic snapshots and not evidence of a speedup.

The current release must replace this historical section's ambiguity with an
exact-commit, equivalent-workload Phase 5 report. Until then, the pathing
stabilization remains open.
