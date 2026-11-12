# Step 2 ledger: movement intent through the Brain, proven on boat avoidance

Step 2 of the foundation-first order in
[`navigation-refactor-implementation.md`](../../plans/navigation-refactor-implementation.md)
delivers Phase 12 (publish vanilla movement intent instead of owning paths) with
`AvoidBoatBehavior` as the pilot, and the 1.21.4 confirmation of the Phase 9 seam. Agent review
recorded here is a self-review.

## What changed

- `behavior/BrainMovementIntent` is the helper. It publishes `WALK_TARGET` and an entity
  `LOOK_TARGET`, reports the sink's outcome by reading memories (`MOVING`, `ARRIVED`,
  `ROUTE_ENDED`, `UNREACHABLE`, `REPLACED`, `TIMED_OUT`), and on `release` erases only the exact
  objects it published. It never writes `PATH` or calls navigation. Caller: `AvoidBoatBehavior`.
- `AvoidBoatBehavior` no longer uses `MovementRequestManager`, `VillagerNavigationTargets`, or
  any path preflight. It picks escape blocks by collision checks, publishes through the helper,
  re-picks every 10 ticks, and stops when another behavior replaces its walk target.
- Transitional bridge in `MovementRequestManager` (deleted at step 15): a safety intent in
  `WALK_TARGET` is not reclaimed at the sink, not overwritten by a maintained work request, and
  not snapshotted for restore. The manager drops only its own live route so the sink plans the
  escape from a clean `PATH`, and republishes the work target when the intent is released. This
  adds one `stop` and one `getPath` to the manager's inventoried navigation counts.
- Manifest: boat avoidance leaves the transitional caller list and reference inventory; the helper
  is inventoried as a memory mutation; the `avoid-boat` behavior is recorded as phase 12.
- Seam on 1.21.4: `VillagerGoalPackages` composes the same way and the same `AvoidBoatBehavior`
  passes the new tests on 1.21.4. `MoveToTargetSink` and `WalkTarget` sources are identical on
  both targets.

Not in Step 2: plague and hostile-player avoidance and begging (step 8), activity placement
(step 4), and the trader's `WanderingTraderAvoidBoatGoal` (a non-villager goal, unchanged).

## Requirement ledger

| ID | Requirement (Phase 12) | Implementation | Automated evidence | Negative case | Status |
| --- | --- | --- | --- | --- | --- |
| P12.1 | A helper publishes `WALK_TARGET` and optional `LOOK_TARGET` only while the behavior is active. | `BrainMovementIntent.walkTo/lookAt`; `AvoidBoatBehavior.stop` calls `release`. | `boatAvoidanceNaturallyWalksAwayAndLeavesNoIntentBehind` (natural AI, both targets): intent published during the escape, none left after the boat is gone. | After the boat leaves, no published walk or look target remains. | PASS (automated) |
| P12.2 | Vanilla trackers: live entity for entity destinations, fixed for blocks. | Entity `LOOK_TARGET` on the boat; block `WalkTarget` for the escape. | Existing `boatAvoidanceChoosesAWalkTargetAwayFromTheBoat` asserts the look target is the boat. | Not applicable. | PASS |
| P12.3 | Observe arrival and failure without path preflight, direct `moveTo`, `PATH` writes, or snapshot/restore. | `observe` reads memories only. Scanner: `AvoidBoatBehavior` has no navigation, manager, or memory mutation. | `verifyNavigationArchitecture` (removed manager entries; any manager reference in the file now fails it). | The scanner rejects an unlisted `MovementRequestManager` reference. | PASS |
| P12.4 | On stop, erase only the exact value still owned. | `release` compares by identity. | `stoppedBoatAvoidanceCannotEraseANewerWalkTarget` (component). | A newer foreign `WALK_TARGET` survives the stop. | PASS (automated) |
| P12.5 | Test static and moving targets, target replacement, natural arrival. | Re-pick every 10 ticks. | `boatAvoidanceReplacesItsTargetWhenTheBoatMoves` (component); natural test reaches 4.6 blocks from the boat. | A boat that moves to the other side flips the target. | PASS (automated) |
| P12.6 | Unreachable timeout and cancellation by a higher-priority activity. | `TIMED_OUT` after 80 ticks; `REPLACED` ends the behavior; the pumpkin journey checks a legacy work request yields and resumes. | `boatEscapePreemptsAndThenResumesARealPumpkinWorkJourney` (rewritten; no longer expects a manager preemption). | Work completes after the boat leaves with no boat intent left. | PASS (automated) |
| P12.7 | Vanilla `MoveToTargetSink` owns route creation. | The behavior never touches navigation. | The natural test asserts `MovementRequestManager.activeHandle` is null while escaping. | Fails if the manager takes the request. | PASS |
| P12.8 | Pilot completes real journeys without `MovementRequestManager`. | As above. | Natural test; mixed-village scene still passes (below). | Not applicable. | PASS (automated) |
| P9 | Seam composes the same way on 1.21.4. | Shared `VillagerGoalPackageIntegration`. | New tests and `VillagerGoalBehaviorGameTests` pass on 1.21.4. | Not applicable. | PASS |
| M2.1 | Developer runs the boat scenes and the mixed village, and records the result. | Run sheet below. | None; only the developer can pass this. | Not applicable. | BLOCKED (waiting for the developer) |

## Recorded results (this tree, uncommitted base 566ca3c6 plus Step 2)

Compile, `verifyNavigationArchitecture`, `verifyRegressionCoverage` pass (280 GameTests
discovered; baseline counts and hash updated for three new tests). GameTests ran 278 on each
target. Failures are exactly the baseline set: five on 1.21.1, six on 1.21.4 (the same five plus
the known flaky `normalfarmerharvestsandreplantsmaturewheat`). Mixed-village runner lines:

```text
1.21.1: mixed_village: work PASS after 65 ticks | trade PASS after 0 | panic PASS after 45 | calm PASS after 25 | meet PASS after 105 | rest PASS after 105
1.21.4: mixed_village: work PASS after 78 ticks | trade PASS after 0 | panic PASS after 38 | calm PASS after 18 | meet PASS after 118 | rest PASS after 98
```

Gradle note: on this PC the build needs `$env:GRADLE_OPTS = '-Djava.net.preferIPv4Stack=true'` and
`--no-daemon`; without them it fails with "Unable to establish loopback connection".

## Developer run sheet for M2.1

1. Launch `.\gradlew :1.21.1:runClient` (then `:1.21.4:runClient`). Create a creative superflat
   world with cheats on, difficulty Easy or higher, and stand away from any village.
2. `/ecapscenario load mixed_village`, then `/ecapscenario start`, then
   `/ecapscenario action work`. Wait until the farmer is at its composter (about 10 seconds).
3. Boat scene, work interrupted. Fly or stand beside the farmer and run
   `/summon minecraft:boat ~2 ~ ~` on 1.21.1, or `/summon minecraft:oak_boat ~2 ~ ~` on 1.21.4.
   Expected within 5 seconds: the farmer turns to look at the boat and walks 3 to 5 blocks away
   from it, stops, and goes back to work after you remove it (`/kill @e[type=boat]`, or
   `oak_boat` on 1.21.4). Failure signs: the farmer ignores the boat, walks toward it, circles in
   place, freezes, or does not return to the composter within a minute after the boat is gone.
4. While the boat is near, run `/ecapscenario inspect @e[type=villager,limit=1,sort=nearest]`.
   `walkTarget` should be a block on the far side of the villager from the boat.
5. Panic over boat. With a boat near the farmer, run `/ecapscenario action panic`. The villagers
   should flee the pillager. Boat avoidance must not drag the farmer back toward the boat or hold
   it frozen. Run `/ecapscenario action calm` and remove the boat; the farmer returns to work.
6. Walled-in boat. Place a boat in a one-block gap you build with stone so the villager cannot
   get 4.5 blocks away. The villager may shuffle but must not jitter between targets every tick
   and must resume normal behavior when the boat is removed.
7. Run `/ecapscenario action meet`, then `rest`, and confirm `/ecapscenario result` still shows
   all six checkpoints `PASS`.
8. Reset with `/ecapscenario reset` and record the `result` output here.

Repeat steps 2 to 5 on 1.21.4 after the 1.21.1 pass.
