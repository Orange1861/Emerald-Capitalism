# Villager AI and navigation refactor plan

## Status and purpose

This document defines the target architecture and phased implementation plan
for stabilizing Emerald Capitalism villager behavior and navigation. The
current movement-request implementation is a transitional baseline, not the
finished design.

The central decision is that Minecraft's villager `Brain` remains the only
villager behavior scheduler. Emerald Capitalism supplies Brain behaviors and
task state; vanilla `MoveToTargetSink` continues to plan and execute ordinary
routes. Special traversal support such as ladders and fence gates is a route
capability, not a second task scheduler or a replacement villager AI.

- Current historical implementation baseline: R8 (`21520ad5`).
- Supported implementation targets: Minecraft 1.21.1 and 1.21.4.
- Porting policy: implement and prove changes on 1.21.1 first, then move the
  same feature to 1.21.4 through a small version adapter.
- Completion status: open until every phase gate below passes for the exact
  release commit.

This is not permission to rewrite all vanilla villager AI. Replacing the
entire Brain would duplicate a large, version-sensitive Minecraft subsystem
and make compatibility and future ports worse. The plan instead removes the
mod's competing schedulers and integrates its features into the vanilla
activity and memory model.

## Problems this plan must fix

The existing navigation work identifies a connected set of defects rather
than one pathfinding bug:

1. **Competing schedulers and ownership models.** Vanilla Brain behaviors,
   injected goals, `VillagerBehaviorRegistry`, and `MovementRequestManager`
   can independently decide what a villager should do or which movement state
   should survive.
2. **Fragile memory ownership.** Snapshotting, restoring, or deleting
   `WALK_TARGET`, `LOOK_TARGET`, and `PATH` can revive obsolete intent or erase
   newer vanilla intent after cancellation, arrival, or preemption.
3. **Task and route lifecycles are mixed together.** Reaching one work
   position can incorrectly complete a multi-step job, as seen in the
   lumberjack walking away between logs.
4. **Scope is not consistently enforced.** Village work, profession work,
   claims, and transient movement state must remain village-scoped and
   profession-scoped instead of leaking across villagers, villages, worlds,
   or sessions.
5. **The tests can prove artificial setups instead of gameplay.** Some tests
   manually tick goals, install handcrafted paths, stop navigation, release
   claims, or discard entities. Those are useful component tests but cannot
   demonstrate that ordinary AI scheduling works.
6. **Vanilla farmer and wanted-item behavior can regress.** Mod movement state
   can prevent eligible vanilla crop work or item pickup even when the mod's
   own focused goals pass.
7. **Lumberjack continuity and production capacity are incomplete.** Route
   completion and task completion are not reliably separated across
   consecutive logs, collection, production, and replanting. Scheduling must
   also treat furnaces as shared, finite resources instead of assuming one
   furnace exists for every lumberjack.
8. **Mayor movement has a moving-target problem.** Candidate following should
   use live entity tracking and normal Brain updates rather than repeated
   custom path ownership.
9. **Doors, fence gates, and recoverable obstacles need route-level support.**
   They must not be implemented separately inside every profession behavior.
10. **Ladders need bounded, interruptible traversal.** Claims, entry, exit,
    contention, invalidation, unload, and cancellation must work without
    teleportation or permanent ownership.
11. **Begging can transfer too early.** The emerald exchange must depend on a
    real completed movement leg, and unreachable donors must leave no stale
    state.
12. **Lifecycle cleanup is incomplete unless proved naturally.** Entity
    removal, profession change, dimension change, level unload, and server
    stop must release transient task, route, and ladder state.
13. **Repeated scans and replans may waste server time.** Optimizations must
    compare equivalent successful workloads; lower work caused by abandoned
    tasks is not an improvement.
14. **Cross-version verification is unreliable.** Local and CI checks must
    compile and execute the same tests on both supported targets and report
    the exact commit and toolchain.

## Target architecture

### One scheduler

For villagers, the vanilla `Brain` and its activities are the only scheduler.
Emerald Capitalism behaviors are inserted into appropriate activity packages
through version-specific composition hooks. The mod does not independently
tick profession tasks through `GoalSelector` or a parallel registry.

Non-villager entities that naturally use goal-based AI may retain their own
goals. Sharing a geometry helper does not give them ownership of villager
Brain memories.

### Three separate layers

1. **Task layer:** decides the gameplay job and owns its domain state, such as
   the selected tree, door, crop, customer, or construction site. It also owns
   village- and profession-scoped leases.
2. **Movement-intent layer:** while a Brain behavior is active, it publishes
   vanilla-compatible `WALK_TARGET` and optional `LOOK_TARGET` memories. It
   observes arrival, absence, timeout, and interruption but does not create or
   drive a path itself.
3. **Route-capability layer:** vanilla `MoveToTargetSink` and navigation own
   route planning and execution. Shared helpers add gate, door, and ladder
   traversal without knowing whether the caller is a farmer, mayor, or
   lumberjack.

A route ending is not automatically task completion. A multi-stage task may
publish several movement intents before it completes.

### State rules

- Persistent saved data stores genuine gameplay state only.
- Task leases, movement attempts, generation tokens, ladder claims, retry
  counters, and cached route analysis are transient and rebuilt after load.
- Task identity and claims are village-scoped and profession-scoped.
- The running Brain behavior owns only the memories it publishes. It erases
  those memories on stop only when they still contain its own value; it never
  restores a stale snapshot over newer Brain state.
- Safety activities such as panic, raid response, sleep, and other vanilla
  higher-priority activities may interrupt mod work naturally.
- No feature writes `PATH` or calls `PathNavigation.moveTo` during ordinary
  villager task ticks.

### Version boundary

Common code owns domain tasks, lifecycle rules, claims, and observable
outcomes. Small 1.21.1 and 1.21.4 adapters own version-specific Brain package
composition, memory access, activity placement, sensors, and navigation hooks.
Minecraft internals must not be scattered throughout each profession.

### Transitional code to retire

The following are migration mechanisms, not target architecture:

- `MovementRequestManager` as an independent villager movement arbiter;
- `VillagerMovementRequestBinding`;
- `MoveToTargetSinkMixin` behavior that snapshots or restores unrelated Brain
  memories;
- villager `GoalSelector` injections for Emerald Capitalism profession work;
- independent ticking by `VillagerBehaviorRegistry`.

No new behavior should be added to those mechanisms. Each migrated feature
must run through exactly one path: legacy or Brain-native, never both.

## Scope

### Included

- the architecture and testing foundations required to remove competing
  villager schedulers;
- farmer crop work, pumpkin work, farmland repair, bread production, and
  wanted-item journeys;
- lumberjack job-site, tree, drop collection, furnace, and replanting work;
- mayor candidate following and door repair;
- banker positioning and inventory-bank visits;
- emerald processing and golem construction;
- hostile-player flee, zombie-plague avoidance, boat avoidance, and begging;
- door, fence-gate, ordinary obstacle, and ladder route capabilities;
- lifecycle cleanup, performance measurement, and supported-version ports;
- vanilla regression protection for ordinary villager activities affected by
  these changes.

### Excluded

- replacing the complete vanilla villager Brain;
- bank persistence, inventory refresh, capacity caching, and unrelated
  bank-wide performance work;
- farm placement, terrain modification, and village-generation scheduling;
- general saved-data hardening unrelated to task or navigation lifecycle;
- whole-mod performance optimization or unrelated gameplay features.

Excluded findings remain in
[`deferred-non-pathing-audit-findings.md`](deferred-non-pathing-audit-findings.md)
unless a phase below explicitly brings them into scope.

## Acceptance contract

The refactor is complete only when ordinary production AI demonstrates all of
the following without direct test intervention in the state being validated:

1. A villager has one Brain scheduler; mod profession work is not also ticked
   by a goal or parallel registry.
2. Task state, movement intent, and route traversal have separate lifecycles.
3. A stopped behavior cannot erase or restore movement state belonging to a
   newer vanilla or mod behavior.
4. Arrival is reported only when the task's arrival policy is satisfied.
5. Unreachable or stalled movement times out or is abandoned according to the
   task policy and leaves no claim or movement state behind.
6. Panic, raids, sleeping, trading, profession change, and other legitimate
   interruptions stop or suspend work predictably and allow valid work to
   resume later.
7. Doors, fence gates, and recoverable obstacles do not permanently strand a
   supported work journey.
8. Villagers ascend and descend ladders, choose valid exits, continue to the
   actual task target, and release claims naturally.
9. Two living villagers serialize a contested ladder without a test releasing
   a claim, cancelling movement, or removing either villager.
10. An ordinary farmer naturally reaches a mature crop, harvests it, and
    replants it when all vanilla conditions are satisfied.
11. A villager that wants a reachable item naturally moves to it and picks it
    up; rejected, full-inventory, and unreachable controls do not create false
    pathing failures or stale state.
12. A normally registered lumberjack completes the selected tree across
    consecutive logs, accepts valid drops, shares a finite furnace pool when
    production is required, and replants without walking away between stages
    unless legitimately interrupted.
13. Mayor following tracks the live entity and door repair completes without
    repeated custom path ownership.
14. Begging transfers only after real arrival; an unreachable donor causes no
    transfer and leaves no stale state.
15. Boat, plague, and hostile-player fear yield to vanilla safety priorities,
    do not fight ordinary navigation, and stop when the threat is no longer
    applicable.
16. Entity removal, dimension change, level unload, and server stop leave no
    transient task, movement, or ladder ownership from the ended session.
17. Stable workloads do not perform unbounded scans or unnecessary replans on
    every villager AI tick.
18. The same behavioral contract passes on 1.21.1 and 1.21.4 through narrow
    version adapters.

Handcrafted paths, direct behavior ticks, forced navigation stops, and manual
claim release may be used in component tests. They must be labeled as such and
cannot satisfy a normal-AI acceptance item.

## Phase plan

Every phase is a review checkpoint. Luna must add or update the automated
tests listed directly under that phase before the phase may close. The
developer then performs the stated manual verification. A focused passing test
cannot substitute for the phase's gameplay gate.

## Group I — Major architectural fixes for the mod

### Phase 1 — Restore a trustworthy build and port baseline

**Work**

- Reproduce and fix `verifyPortSetup` without disabling or weakening it.
- Align local and CI Java/Gradle configuration.
- Compile both supported targets and ensure CI can launch both GameTest
  servers.
- Keep generated logs, worlds, caches, crash reports, and build output out of
  source control.

**Luna automated verification**

- Run `test`, `verifyRegressionCoverage`, `verifyPortSetup`, both target
  compile tasks, and a minimal GameTest smoke suite on both targets.
- Add a CI assertion that reports the tested commit, Java version, target, and
  executed GameTest count.

**Developer manual verification**

- Launch one development client for each target, create or load a disposable
  world, and confirm a normal villager can spawn, acquire a profession, and
  walk without a crash or mixin error.

**Gate:** both targets reach and execute tests in CI. No gameplay behavior is
changed in this phase.

### Phase 2 — Freeze scheduler, scope, and ownership invariants

**Work**

- Record the target architecture above as an enforced decision: Brain is the
  only villager scheduler; tasks, movement intent, and routes are distinct.
- Inventory every current villager goal, registered behavior, request owner,
  memory mutation, direct navigation call, and persistent claim.
- Assign each item a target Brain activity, village/profession scope, and
  durable or transient classification.
- Prohibit new callers of the transitional movement stack.

**Luna automated verification**

- Add architecture checks that fail when villager profession code directly
  writes `PATH`, calls `PathNavigation.moveTo`, or registers a new mod
  `GoalSelector` task outside an explicit allowlist.
- Add scope tests proving identical targets in two villages or professions do
  not share a lease.

**Developer manual verification**

- Review the inventory for farmer, lumberjack, mayor, banker, smith,
  avoidance, and begging; confirm each has exactly one future scheduler and a
  named activity.

**Gate:** every current producer has a documented owner and migration
destination, and no ambiguous global task state remains in the plan.

### Phase 3 — Separate persistent gameplay state from transient execution

**Work**

- Define task records that persist only player-meaningful progress.
- Make leases, movement attempts, retries, ladder claims, and path-analysis
  caches session-local.
- Centralize cleanup for death/removal, profession change, dimension change,
  level unload, and server stop.
- Ensure cleanup is keyed by village, profession, villager UUID, task, and
  world/dimension where applicable.

**Luna automated verification**

- Add save/reload tests proving durable task progress survives where intended
  while transient ownership does not.
- Add lifecycle tests for each cleanup boundary and negative tests showing one
  village cannot release another village's work.

**Developer manual verification**

- Start a multi-stage job, save and reload mid-job, and confirm the villager
  resumes safely or reacquires work without frozen movement, duplicated work,
  or an immortal claim.

**Gate:** all transient state has a single cleanup owner and no execution-only
record is serialized as authoritative gameplay state.

### Phase 4 — Create the common-code/version-adapter boundary

**Work**

- Move profession decisions, task stages, claims, and observable outcomes into
  common code.
- Restrict Brain package construction, activity/memory API differences,
  sensors, and navigation internals to small 1.21.1 and 1.21.4 adapters.
- Establish a port rule: feature logic is implemented and tested on 1.21.1,
  then only adapter differences are introduced for 1.21.4.

**Luna automated verification**

- Add compile-time dependency checks that common task code does not import
  target-specific implementation classes.
- Run the same contract test names and assertions on both targets.

**Developer manual verification**

- Compare the two version implementations for one representative task and
  confirm that only Minecraft API wiring differs, not gameplay policy.

**Gate:** future Minecraft ports have a narrow list of adapter touchpoints
instead of copies of every profession behavior.

## Group II — Make the tests prove real gameplay

### Phase 5 — Audit and classify all AI/navigation tests

**Work**

- Map every acceptance item to unit, component, natural-AI integration, and
  developer-manual coverage.
- Label tests that directly tick a goal/behavior, inject a path, stop
  navigation, release a claim, or remove an actor.
- Prevent component coverage from being reported as natural-AI acceptance.

**Luna automated verification**

- Extend regression-manifest validation to require a test type and acceptance
  item for every navigation test.
- Fail coverage when an acceptance item lacks at least one natural-AI test.

**Developer manual verification**

- Inspect the coverage report and confirm it clearly distinguishes helper
  correctness from real scheduled behavior.

**Gate:** the suite can no longer turn an artificial component setup into a
claim that player-visible behavior works.

### Phase 6 — Build deterministic natural-AI fixtures

**Work**

- Create reusable fixtures that spawn ordinarily registered villagers, assign
  profession and village context normally, and let the server tick AI.
- Standardize time of day, weather, gamerules, inventories, points of
  interest, reachability, and observation timeouts.
- Observe world and inventory outcomes instead of private test hooks.

**Luna automated verification**

- Add fixture self-tests proving the villager uses its production Brain,
  receives no handcrafted path, and is not manually ticked.
- Add reachable, unreachable, interrupted, and two-villager contention
  fixture variants.

**Developer manual verification**

- Watch one generated fixture world in a client and confirm its setup matches
  the scenario the test claims to exercise.

**Gate:** future behavior phases can express natural journeys without copying
fragile world setup or controlling the result from the test.

### Phase 7 — Establish vanilla behavior baselines

**Work**

- Capture behavior before the refactor for sleep, panic, raid response,
  trading, breeding, job-site use, local crop work, wanted-item pickup, doors,
  and ordinary idle movement.
- Separate eligibility failures from scheduling or navigation failures.
- Treat these baselines as regression protections, not as a promise to change
  vanilla policy.

**Luna automated verification**

- Add natural-AI tests for each baseline with both eligible and ineligible
  controls.
- Assert observable outcomes and absence of stale mod state after completion
  or rejection.

**Developer manual verification**

- In a mixed village, observe a full day/night cycle including work, social
  time, sleep, panic, and trading; record any pre-existing deviations before
  migration starts.

**Gate:** later phases can identify whether a change fixed a mod feature while
breaking vanilla villagers.

### Phase 8 — Establish interruption, benchmark, and manual acceptance suites

**Work**

- Build a reusable interruption matrix covering panic, raid, sleep, trade,
  profession loss, death/removal, dimension change, unload, and server stop.
- Define a short developer checklist for path visuals, animation, door/gate
  use, ladder entry/exit, and post-interruption recovery.
- Save opt-in diagnostics and failure-world artifacts in CI.
- Add an opt-in benchmark runner that Luna can invoke before and after a
  potentially performance-sensitive AI change. It must use fixed seeds,
  equivalent village layouts and populations, a warm-up period, a fixed
  measurement window, and the same required gameplay outcomes.
- Make the benchmark report server tick time, task outcomes, target searches,
  behavior starts/stops, movement-intent updates, route plans, path-node work,
  retries, ladder scans, and allocations where reliable measurement is
  available. Store machine and JVM metadata with the result.
- Keep noisy wall-clock thresholds out of ordinary correctness CI. CI should
  smoke-test that the benchmark runs and produces a valid report; Luna should
  run repeated before/after samples when a change touches an AI hot path or
  when the developer requests a performance comparison.

**Luna automated verification**

- Run each lifecycle boundary against a representative work task and assert
  memory, lease, task, and ladder cleanup.
- Add failure messages that identify task stage, active activity, relevant
  memories, route state, and outstanding claims.
- Add a small benchmark-harness smoke test and a comparison task that rejects
  mismatched seeds, populations, durations, or successful outcome counts.
- Document the exact command Luna should run to produce a baseline and compare
  a candidate change, with raw reports retained as untracked build artifacts.

**Developer manual verification**

- Interrupt a representative worker by panic, trading, sleep, and chunk
  unload; confirm it neither freezes nor resumes obsolete work.
- For a performance-sensitive change, review one Luna-generated before/after
  report and confirm both runs completed the same visible work before
  accepting a claimed improvement.

**Gate:** every later phase has reusable automated and human checks for the
most common regression pattern—interruption followed by stale state—and Luna
has a reproducible, outcome-normalized benchmark path available when
performance evidence is needed.

## Group III — Redesign villager pathing around the Brain

### Phase 9 — Install a no-op Brain composition seam

**Work**

- Create the version-adapted hook that composes Emerald Capitalism behaviors
  into vanilla villager activity packages.
- Initially add only an observable no-op behavior so scheduler parity can be
  tested before migrating gameplay.
- Preserve vanilla activity ordering and memory requirements.

**Luna automated verification**

- Prove the behavior starts and stops through the production Brain.
- Run the Phase 7 baselines with the seam enabled and disabled and require
  identical results.

**Developer manual verification**

- Observe an unmodified farmer and one other profession for a day/night cycle
  with the seam enabled; confirm no change in schedule or movement.

**Gate:** the integration point exists on both targets without changing
gameplay.

### Phase 10 — Replace the parallel behavior scheduler with Brain-owned tasks

**Work**

- Convert `VillagerBehaviorRegistry` from an independently ticked executor
  into immutable task definitions or remove its execution role.
- Ensure a task is selected, started, ticked, and stopped only while its Brain
  behavior is running.
- Add a per-villager guard that prevents the same feature from running through
  both legacy and Brain-native paths.

**Luna automated verification**

- Add scheduler tests proving one start/stop sequence per task and no legacy
  goal or registry ticks for a migrated feature.
- Test two villagers and two professions to prevent accidental global
  singleton execution.

**Developer manual verification**

- Enable scheduler diagnostics in a mixed village and confirm each visible
  action corresponds to one active Brain behavior, without rapid start/stop
  oscillation.

**Gate:** the Brain is demonstrably the only scheduler for one pilot task and
the pattern is ready for other behaviors.

### Phase 11 — Introduce an explicit task lifecycle and claim contract

**Work**

- Model task stages such as acquire, claim, move, interact, continue, complete,
  suspend, abandon, and fail.
- Distinguish task completion from arrival at an intermediate work position.
- Define interruption reasons and which ones release, suspend, or reacquire
  village/profession-scoped claims.
- Keep retry policy in the task rather than in a global movement owner.

**Luna automated verification**

- Add state-transition tests for success, unreachable target, invalidated
  target, preemption, timeout, unload, and profession change.
- Add generation/claim tests proving delayed cleanup from an old task cannot
  release a new task's lease.

**Developer manual verification**

- Watch a multi-stage pilot job through arrival, interaction, continuation,
  interruption, and resumption; confirm diagnostics match what occurs in the
  world.

**Gate:** all later profession migrations use one lifecycle vocabulary and
cannot mistake route completion for job completion.

### Phase 12 — Publish vanilla movement intent instead of owning paths

**Work**

- Add a small Brain behavior helper that publishes `WALK_TARGET` and optional
  `LOOK_TARGET` only while the task behavior is active.
- Use vanilla trackers for live entity destinations and fixed trackers for
  block destinations.
- Observe arrival and absence/failure without path preflight, direct
  `moveTo`, `PATH` writes, or snapshot/restore of unrelated memories.
- On stop, erase only the exact memory value still owned by that behavior.

**Luna automated verification**

- Test static and moving targets, speed changes, target replacement, natural
  arrival, unreachable timeout, and cancellation by a higher-priority
  activity.
- Assert vanilla `MoveToTargetSink` owns route creation and that a stopped old
  behavior cannot erase a newer walk target.

**Developer manual verification**

- Observe a villager approach a block and a moving entity, then trigger panic
  mid-route; confirm immediate safety movement and clean later recovery.

**Gate:** the pilot task completes real journeys without
`MovementRequestManager` or direct navigation ownership.

### Phase 13 — Place mod behaviors into correct activity priorities

**Work**

- Assign safety, core route support, work, civic, social, and idle features to
  explicit Brain activities and priorities.
- Preserve vanilla panic, raid, rest, meet, work, and core behaviors.
- Define which mod work may resume after interruption and which must reacquire
  a target.

**Luna automated verification**

- Add priority tests for work versus panic, raid, sleep, trade, meeting, and
  profession change.
- Assert that interruption produces one clean stop and no simultaneous
  conflicting movement intent.

**Developer manual verification**

- Trigger each high-priority interruption while a pilot worker is travelling
  and interacting; verify responsive behavior rather than delayed or
  alternating movement.

**Gate:** vanilla safety and schedule behavior wins deterministically without
custom preemption arbitration.

### Phase 14 — Isolate doors, fence gates, obstacles, and ladders as route capabilities

**Work**

- Implement door and fence-gate support at the navigation/path-evaluation or
  core-observer layer instead of inside profession tasks.
- Keep ladder discovery, claims, ascent/descent, exit validation, and route
  resumption independent of task identity.
- Bound route analysis and forbid teleporting a villager into an exit block.
- Preserve ordinary vanilla door behavior when no mod task is active.

**Luna automated verification**

- Add natural journeys through doors, closed fence gates, recoverable
  obstacles, short/tall ladders, ascent/descent, invalidated exits, and two
  living villagers contending for one ladder.
- Assert the original task continues after traversal and all claims disappear
  after completion, cancellation, unload, and server stop.

**Developer manual verification**

- Watch workers traverse each structure in both directions, including ladder
  contention and a gate closing during travel; check for clipping,
  teleportation, jitter, or permanent blockage.

**Gate:** route capabilities work for the pilot task without profession-aware
code and do not alter unrelated vanilla villagers.

## Group IV — Apply the architecture to each behavior

### Phase 15 — Farmer crop, pumpkin, farmland, and bread work

**Work**

- Migrate farmer mod work to Brain-native work tasks.
- Preserve vanilla local crop harvesting/replanting and do not let mod tasks
  occupy movement memories outside their active work behavior.
- Keep pumpkin harvesting, farmland repair, and bread production as distinct
  task stages with village/profession-scoped claims.
- If long-range crop work is desired, implement it as an explicit mod feature,
  not as a hidden mutation of vanilla crop behavior.

**Luna automated verification**

- Add natural near/far mature-crop journeys, harvest/replant outcomes,
  insufficient-seed controls, invalid farmland, full inventory, competing
  farmers, panic, sleep, and unload cases.
- Add natural pumpkin, farmland-repair, and bread-production journeys and
  prove their claims do not block vanilla crop work after stop.

**Developer manual verification**

- Observe at least two farmers for a full work period on a mixed farm; verify
  normal crops, pumpkins, repair, and bread behavior without oscillation or
  one farmer monopolizing all work.

**Gate:** ordinary farmer gameplay works before and after mod work, with no
stale walk target or claim.

### Phase 16 — Wanted-item pursuit

**Work**

- Keep pickup eligibility separate from navigation.
- Allow eligible vanilla item pickup to publish and retain its normal movement
  intent without interference from completed mod work.
- Avoid creating a duplicate item-seeking scheduler unless vanilla lacks an
  intentional supported feature.

**Luna automated verification**

- First assert `VillagerPickupCompat.wants(...)`, inventory capacity, and item
  reachability; then assert natural movement and inventory transfer.
- Cover farmer seeds, representative accepted items for other villagers,
  rejected items, full inventory, item removal mid-route, unreachable items,
  and post-task pickup after mod work stops.

**Developer manual verification**

- Drop seeds and profession-relevant items at near and path-requiring
  distances after a villager finishes work; verify eligible pickup and clean
  abandonment when blocked.

**Gate:** policy failures are not mislabeled as pathing failures, and eligible
pickup is no longer suppressed by mod movement state.

### Phase 17 — Lumberjack full-tree continuity

**Work**

- Migrate job-site travel, selected-tree travel, consecutive log work, drop
  collection, furnace/production travel, and replanting as stages of one
  Brain-owned task.
- Preserve the selected tree while advancing logs; arriving at one log must
  not complete or release the whole task.
- Model furnaces as a village-scoped pool of shared, claimable production
  resources. Never derive furnace availability or assignment from the number
  of lumberjacks, and never assume each lumberjack has a dedicated furnace.
- When every valid furnace is occupied, missing, blocked, or invalid, wait with
  bounded backoff or perform other eligible work instead of repeatedly
  pathing to the same unavailable furnace.
- Define legitimate interruption, invalid-tree, inventory-full, and
  production-blocked outcomes.

**Luna automated verification**

- Add a normally registered lumberjack journey without constructing or
  manually ticking `LumberjackGoal`.
- Assert all selected logs are processed, accepted drops enter inventory,
  consecutive reachable logs do not cause departure, production runs when
  required, replanting occurs, and claims clean up after each terminal path.
- Cover fewer furnaces than lumberjacks, more furnaces than lumberjacks, no
  furnace, a furnace removed while claimed, two workers contending for one
  furnace, and cross-village furnace isolation. Assert exclusive claims,
  eventual progress where capacity exists, and bounded retry behavior where
  it does not.
- Include panic, sleep, tree invalidation, unreachable upper logs, two
  lumberjacks, save/reload, and unload cases.

**Developer manual verification**

- Watch one complete small and one tall tree cycle, then interrupt a third
  cycle; confirm continuity, drop handling, production, replanting, and safe
  resumption or reacquisition.
- Run at least three lumberjacks with one furnace, then remove or obstruct that
  furnace during production; confirm workers serialize access and remain
  responsive without duplicating furnaces, jobs, fuel, or outputs.

**Gate:** the player-observed one-log-then-walk-away regression is reproduced
by a test first and eliminated in ordinary gameplay.

### Phase 18 — Mayor candidate following and door repair

**Work**

- Migrate candidate following to a Brain behavior using a live entity tracker.
- Make door repair a staged civic task with a fixed work position and
  village-scoped claim.
- Define loss of candidate, candidate dimension change, invalid door, and
  higher-priority interruption behavior.

**Luna automated verification**

- Add moving, stopping, disappearing, unreachable, and dimension-changing
  candidate journeys without repeated custom path requests.
- Add natural door-repair journeys with contention, invalidation, panic,
  unload, and post-repair cleanup.

**Developer manual verification**

- Walk a candidate through turns, doors, and a moderate route while the mayor
  follows, then cancel eligibility; separately observe a complete door repair.

**Gate:** mayor movement follows current entity position smoothly and civic
work never leaves stale movement or claims.

### Phase 19 — Banker and inventory-bank work

**Work**

- Migrate banker positioning and inventory-bank visits as Brain work tasks.
- Keep bank persistence and bank-wide optimization outside this refactor while
  preserving existing gameplay semantics.
- Scope visits and reservations to the correct village, profession, bank, and
  villager.

**Luna automated verification**

- Add natural reachable/unreachable bank visits, contention, full/invalid
  destination, panic, sleep, unload, and cross-village isolation tests.
- Assert the task performs its intended inventory effect exactly once after
  real arrival.

**Developer manual verification**

- Observe multiple bankers using the same and different bank locations; check
  for crowding loops, cross-village work, duplicate inventory effects, and
  failure to resume normal schedule.

**Gate:** bank-related movement uses the common lifecycle without expanding
into unrelated persistence or performance work.

### Phase 20 — Emerald processing and golem construction

**Work**

- Migrate processor and construction journeys into staged Brain work tasks.
- Separate material eligibility, site claims, movement, interaction, and final
  world effects.
- Ensure construction cannot complete from failed or cancelled movement.

**Luna automated verification**

- Add natural success, missing-material, invalid-site, blocked-site,
  competing-worker, panic, unload, and retry journeys.
- Assert materials and world effects change once and only after the required
  arrival and interaction stages.

**Developer manual verification**

- Observe a complete processing cycle and construction cycle, then invalidate
  each target during travel and confirm no phantom consumption or build.

**Gate:** both behaviors use the shared task/movement contract and preserve
transactional gameplay outcomes.

### Phase 21 — Avoidance, fear, and begging

**Work**

- Express hostile-player flee, zombie-plague avoidance, and boat avoidance as
  Brain-compatible safety behaviors with a common threat abstraction where
  their policies truly match.
- Keep different threat predicates and stop conditions explicit; a boat must
  not accidentally inherit plague or hostile-player rules.
- Place begging in the appropriate social/idle activity and make transfer
  conditional on real arrival and current donor validity.

**Luna automated verification**

- Add natural near/far threat, moving threat, removed threat, safe-distance,
  competing safety activity, boat, plague, and hostile-player cases.
- Replace artificial blocked-begging tests with a genuinely unreachable but
  otherwise valid donor; assert no transfer and no stale state.
- Add successful begging, donor movement, donor invalidation, interruption,
  and duplicate-transfer controls.

**Developer manual verification**

- Trigger each fear source separately while villagers are idle, working, and
  socializing; verify prompt escape and clean return. Observe reachable and
  blocked begging and confirm emeralds transfer only after arrival.

**Gate:** fear behaviors preempt safely without fighting Brain movement, and
begging cannot pay at a distance or leak state.

## Group V — Remaining navigation work and release proof

### Phase 22 — Retire the legacy movement stack and harden lifecycle cleanup

**Work**

- After every villager caller has migrated, remove independent profession goal
  injections, parallel registry ticking, `MovementRequestManager`,
  `VillagerMovementRequestBinding`, and memory snapshot/restore behavior from
  `MoveToTargetSinkMixin`.
- Retain goal-based navigation only for explicitly documented non-villager
  entities such as the wandering trader or custom golems where appropriate.
- Remove compatibility bridges, feature flags, obsolete counters, and dead
  tests only after their replacements pass.

**Luna automated verification**

- Add source/architecture checks proving no villager feature references the
  retired stack or directly owns a vanilla path.
- Run the full interruption matrix across representative tasks and assert zero
  transient owners after entity removal, dimension change, unload, and server
  stop.
- Run the complete vanilla baseline and all natural-AI behavior suites.

**Developer manual verification**

- Play a mixed village through work, meeting, panic, trading, sleep, chunk
  unload/reload, and server restart with diagnostics enabled; confirm no
  legacy execution appears and no villager remains frozen.

**Gate:** there is one villager scheduler and one ordinary route executor in
production, with all transitional ownership code removed.

### Phase 23 — Measure and reduce navigation overhead

**Work**

- Measure task selections, starts/stops, movement-intent publications, route
  plans, failed arrivals, retries, path nodes inspected, ladder scans, claim
  operations, and expensive target searches.
- Compare identical multi-villager workloads and normalize results by
  successful gameplay outcomes.
- Cache only proven repeated work, with precise invalidation for route
  progress, target change, world change, or relevant climbable/gate change.
- Keep event/timing instrumentation disabled by default with no clock reads or
  timing-lambda allocation on the disabled path.

**Luna automated verification**

- Add deterministic workload tests with outcome counts and bounded-operation
  budgets rather than wall-clock thresholds alone.
- Run before/after workloads for mixed professions, ladder contention, moving
  targets, and unreachable targets and require equivalent outcomes.

**Developer manual verification**

- Run a dense mixed village long enough to observe a work period and inspect
  server tick behavior, visible jitter, stalled villagers, and diagnostic
  counters before accepting an optimization.

**Gate:** repeated work is bounded and reduced without reducing task success,
changing retries, or hiding regressions through earlier abandonment.

### Phase 24 — Cross-version release validation

**Work**

- Validate a clean checkout of the exact proposed release commit.
- Produce a release report naming commit, Java version, commands, test counts,
  results, workload counters, player-visible fixes, compatibility limitations,
  and known remaining defects.
- Document that mods replacing villager Brain packages, activity priorities,
  relevant memories, `MoveToTargetSink`, path evaluators, door/gate behavior,
  or navigation internals may be incompatible and require integration work.

**Luna automated verification**

Run on the exact release commit:

```text
.\gradlew test verifyRegressionCoverage --no-daemon --no-configuration-cache --console=plain
.\gradlew verifyPortSetup --no-daemon --no-configuration-cache --console=plain
.\gradlew :1.21.1:compileJava --no-daemon --no-configuration-cache --console=plain
.\gradlew :1.21.4:compileJava --no-daemon --no-configuration-cache --console=plain
.\gradlew :1.21.1:runGameTestServer --no-daemon --no-configuration-cache --console=plain
.\gradlew :1.21.4:runGameTestServer --no-daemon --no-configuration-cache --console=plain
git diff --check
```

- Run mixin audits, architecture checks, regression-manifest validation, and
  the equivalent performance workloads on both targets.

**Developer manual verification**

- On both targets, perform the complete farmer, wanted-item, lumberjack,
  mayor, banker, processor/construction, avoidance, begging, door/gate, ladder,
  interruption, unload/reload, and restart journeys from the phase checklist.
- Repeat with the project's supported compatibility-mod set and record any mod
  that replaces the same Brain or navigation seams.

**Gate:** all automated checks and the developer checklist pass on both
targets, no generated artifact is tracked, and the exact-commit release report
contains no ambiguous historical evidence.

Do not begin another navigation revision after this gate unless a new,
reproducible defect is reported and assigned to the correct task, movement, or
route-capability layer.

## Diagnostics

During migration, keep `emeraldcapitalism.navigationDiagnostics=true` as an
opt-in source of task transitions, active Brain activities, movement-intent
publication, route outcomes, claims, and cleanup. Legacy request-manager
counters may remain only until Phase 22.

Detailed timing remains opt-in with
`-Demeraldcapitalism.performanceTiming=true`. Event counters remain opt-in with
`-Demeraldcapitalism.performanceEvents=true`. Both are disabled by default,
and the disabled path must not read the clock or allocate timing lambdas.

Diagnostics explain a journey; world, inventory, memory, and lifecycle
outcomes prove it.

## Historical evidence and limitations

Recorded local verification on 2026-10-14/15 used Java 21.0.9 and the project
Gradle wrapper. The prior implementation note identified tested commit
`46939820` (R7) while also describing later R8 repairs. Those results are
historical context only and do not verify this refactor.

| Target | Required tests | Command wall time | Recorded result |
| --- | ---: | ---: | --- |
| 1.21.1 | 240 | 4.326 min | all required tests passed; 0 optional |
| 1.21.4 | 240 | 3.928 min | all required tests passed; 0 optional |

The old coverage task found 7 risk boundaries and 242 GameTest methods. Method
discovery proves tests exist; it does not prove their setup is natural or that
their assertions satisfy this plan.

An instrumented 1.21.1 workload recorded 69 submissions, 28 retries, 256
ladder-segment scans, 1,536 ladder-block scans, 277 purge visits, and 28.586 ms
of movement-manager time. A later, non-equivalent workload recorded 73
submissions, 62 retries, 33 segment scans, 198 ladder-block scans, 238 purge
visits, and 21.971 ms. Because the suites differed, neither snapshot proves a
performance improvement.

Phase 24 must replace these ambiguous records with exact-commit,
equivalent-workload evidence.
