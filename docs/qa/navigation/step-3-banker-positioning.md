# Step 3 ledger: one scheduler, proven on banker positioning

Step 3 of the foundation-first order in
[`navigation-refactor-implementation.md`](../../plans/navigation-refactor-implementation.md)
delivers Phase 10 (the Brain is the only scheduler, proven on one pilot job), the
banker-positioning half of Phase 19, and the start of the Phase 4 port rule. Agent review
recorded here is a self-review.

## What changed

- `behavior/BankerWorkSideBehavior` replaces `BankerWorkGoal`. It is one Brain behavior in the
  banker's WORK package at priority 1. It starts only while `WALK_TARGET` is absent, publishes the
  route to the bank's work side through `BrainMovementIntent` (Step 2), and lets vanilla
  `MoveToTargetSink` walk it. At the work side it holds, and places a prepared replacement
  chest every 20 ticks through `BankBlockEntity.replaceMissingEmeraldChest`, as the goal did. No
  path preflight, no `MovementRequestManager`, no watchdog.
- `BankerWorkGoal` and its `injectBankerWorkGoal` registration in `VillageRegistryEvents` are
  deleted. The class has no remaining reference.
- Work-slot rule (found by testing, see "Negative case"): vanilla's WORK package contains a
  `RunOne` of `StrollAroundPoi`/`StrollToPoi` that writes `WALK_TARGET` regardless of priority, so
  a mod behavior at any priority cannot hold a position against it. For a banker,
  `VillagerGoalPackageIntegration.addWorkBehaviors(profession, original)` removes that second
  `RunOne` and adds plain `WorkAtPoi` (work sound, `LAST_WORKED_AT_POI`, restock) in its place.
  Other professions are unchanged. The mixin now passes the profession. Step 4 generalizes this
  into the written rule for every mod job.
- Candidate stall fix (developer report, 2026-11-13): a villager taking the bank job walked up,
  stopped about a block short, and was not assigned until pushed. `BankAwarePotentialJobSiteBehavior`
  targeted the work-side block with vanilla's close-enough distance of 1, so `MoveToTargetSink` stopped
  on the block before it, and `BankAwareAssignProfessionFromJobSite` requires the villager at the work
  position. The bank target now uses close-enough 0. This predates Step 3; it is fixed here because
  the new banker behavior shares the work-side geometry.
- Any other writer (panic, boat, wanted item, trading, a newer target) is respected: the behavior
  waits for `WALK_TARGET` to clear, yields if its own target is replaced, and walks back after.
  A journey that fails or exceeds 400 ticks retries after 100 ticks.
- `VillagerBehaviorRegistry.refresh/tick` no longer have any production caller
  (`VillagerSpawnEvents`, `VillageRegistryEvents`). The registry class and its tests remain; the
  extension test now drives `tick` itself and is labeled a component test. Deleting the dead
  framework and those tests is left for step 15.
- Manifest: the banker producer and a new `banker-work-side` registered Brain behavior describe
  the new executor; the goal's caller and reference rows and its allowlist entry are gone;
  `VillageRegistryEvents` goal-registration count is 10. Baseline: 288 discovered, 286 executed,
  new hash.

Schedule note (developer, 2026-11-13): natural banks are built so their employees are trapped and
cannot join the village at the bell, so meeting-time behavior is not testable there; bankers at
artificial banks are probably not confined. The old goal held the work side all day, including
meeting hours. The Brain behavior lives only in WORK, so an unconfined banker now follows the vanilla
schedule (bell at meeting time, back to the work side after). Holding the work side through MEET and
IDLE too would be a Phase 13 activity-placement decision; this step does not make it.

Not done in Step 3, and why:

- No scenario-runner banker scene or `inspect` output for behavior start/stop counts. The plan
  gives that to step 7 (standing check) and the Phase 10 manual text asks for it; the run sheet
  below uses vanilla `/data` instead.
- No Phase 4 compile-time dependency check yet. The banker behavior is in the shared tree and
  only the package mixin is version-sensitive, but no automated check enforces that.
- The tracked guard against a feature being on both the legacy and the Brain path covers the
  banker only (`bankerPositioningRunsOnlyOnTheBrain`). It becomes generic when a second job moves.
- No test for a banker whose work side is unreachable. The retry path exists but is untested.
- The candidate test (`unemployedVillagerReachesTheBankWorkSideAndBecomesABankerUnaided`) fails on
  1.21.4 and is recorded as an expected failure there. The candidate's `POTENTIAL_JOB_SITE`
  fixture memory is gone one tick after setup on 1.21.4 for a reason I did not find
  (`YieldJobSite` is identical on both versions and no other villager exists). The stall fix itself is
  therefore verified on 1.21.1 only; the 1.21.4 behavior is unverified until you check it in game.

## Requirement ledger

| ID | Requirement | Implementation | Automated evidence | Negative case | Status |
| --- | --- | --- | --- | --- | --- |
| P10.1 | Remove the registry's execution role. | `refresh`/`tick` callers deleted from `VillagerSpawnEvents` and `VillageRegistryEvents`. | `verifyNavigationArchitecture`; the registry GameTests still pass as component tests. | The extension test fails unless the fixture ticks the registry itself. | PASS (class retained until step 15) |
| P10.2 | A task starts, ticks, and stops only while its Brain behavior runs. | `BankerWorkSideBehavior` in the WORK package. | `bankerNaturallyReachesAndHoldsTheWorkSide`, `bankerWorkSideWaitsForAndYieldsToAnotherWalkTarget` (component). | Waiting on and yielding to a foreign walk target. | PASS (automated) |
| P10.3 | Per-villager guard against legacy plus Brain. | `BankerWorkGoal` deleted. | `bankerPositioningRunsOnlyOnTheBrain` (component): no banker goal on the GoalSelector after production join. | Package test: other professions never receive the banker behavior. | PASS for the banker; not generic yet |
| P10.4 | Two villagers, two professions, no global singleton. | One behavior instance per Brain. | `twoBankersHoldTheirOwnBanksWorkSides` (natural, two villages); mixed-village scene (farmer, librarian, cleric) passes. | Each bank refuses the other's banker. | PASS (automated) |
| P19.1 | Banker positioning as a Brain work task, scoped to village and bank. | Village-scoped lookup through `BankEmployeeLookup`. | The two natural journey tests. | Two villages do not cross. | PASS (automated); unreachable case untested |
| P19.3 | A villager taking the bank job reaches the work position and is assigned without being pushed. | Bank target close-enough 0 in `BankAwarePotentialJobSiteBehavior`. | `unemployedVillagerReachesTheBankWorkSideAndBecomesABankerUnaided` (natural walk and assignment): passes on 1.21.1. | Before the fix the candidate stopped one block short of the work-side block and the test failed on 1.21.1. | PASS on 1.21.1; FAIL on 1.21.4 (expected-failure entry, cause not found) |
| P19.2 | Natural reach and hold across the work day; inventory effect after real arrival, once. | Hold plus repair on arrival. | Hold test requires 400 ticks at the work side after arriving; `bankerPlacesPreparedChestAfterReachingTheWorkSide` checks one chest, prepared count 0, banker at the work side. | See below. | PASS (automated) |
| P4.1 | Port rule and dependency check from step 3. | Feature logic in the shared tree, mixin as the only version-sensitive piece. | None for the dependency check. | None. | BLOCKED (check not built) |
| M3.1 | Developer observes banker positioning in game. | Run sheet below. | None; only the developer can pass this. | Not applicable. | PASS (developer, 2026-11-13; findings: player-placed banks offer no banker job, see the finding above; a walled-in banker could not flee a pillager, see the step 4 addendum) |

### Negative case

Leaving vanilla's job-site `RunOne` in the banker package (rule disabled) makes both natural hold
tests fail on 1.21.1: the banker walks off the work side about 140 ticks after arriving. With the
rule they pass. The package-contents test also fails.

## Recorded results (HEAD 7d6e11a2 plus the candidate fix, uncommitted)

Compile, `verifyNavigationArchitecture`, and `verifyRegressionCoverage` pass (296 GameTests
discovered). GameTests ran 294 on each target. On 1.21.1 the failures are exactly the five baseline
ones. On 1.21.4 they are the baseline six (the five plus the known flaky
`normalfarmerharvestsandreplantsmaturewheat`) and the new candidate test, which is an expected-failure
entry. The five other new banker tests pass on both targets. Mixed-village runner lines:

```text
1.21.1: work PASS after 78 ticks | trade PASS after 0 | panic PASS after 38 | calm PASS after 18 | meet PASS after 138 | rest PASS after 118
1.21.4: work PASS after 85 ticks | trade PASS after 0 | panic PASS after 25 | calm PASS after 25 | meet PASS after 125 | rest PASS after 85
```

The candidate test failed on 1.21.1 before the close-enough fix (the villager stood one block from the
work-side block) and passed after it. One run of the unrelated `gateremainsopenwhileanothermobapproaches`
failed on 1.21.1 and passed on the next two; I did not touch gate code and treat it as flaky.

Gradle on this PC needs `$env:GRADLE_OPTS = '-Djava.net.preferIPv4Stack=true'` and `--no-daemon`.

## Finding (2026-11-13): player-placed banks offer no banker job

The developer reported that villagers do not become bankers at a bank block placed in an artificial
bank with a village ledger. Cause, from the code and confirmed by
`playerPlacedBankIsControlledAndOffersNoBankerJobUntilReleased` on 1.21.1 and 1.21.4: `BankBlock.setPlacedBy`
makes the placing player the bank's controller, `BankBlockEntity.setController` then sets the bank
block's `banker_available` state to false, and `ECAPPoiTypes.BANKER` only matches `banker_available=true`.
A player-controlled bank is therefore not a job-site POI and no villager is sent to it. Releasing control
from the bank screen (the controller only) sets it back to independent and restores the job. This predates
Steps 3 and 4; the test documents the current design and must change if that design changes.

## Developer run sheet for M3.1

1. `.\gradlew :1.21.1:runClient`, then repeat with `:1.21.4:runClient`. Use a world with a
   generated village that has a bank and a banker (or the world you use for path testing).
2. Find the bank. Its deposit face is the front the players use; the banker belongs on the side
   directly opposite (the back). Stand where you can see both.
3. `/time set 2500` (work time). Within about 30 seconds the banker should walk to the block
   behind the bank and stay there. Watch it for a full minute. Failure signs: the banker wanders
   around the bank, stands at the front, pushes into the bank, or jitters.
4. Run `/data get entity @e[type=minecraft:villager,limit=1,sort=nearest] Brain.memories` while the
   banker stands there. There should be no `walk_target` and no `path`.
5. Only for a bank that does not confine its banker (artificial banks): `/time set 9200`. The banker
   follows the normal schedule and goes to the bell, and `/time set 2500` sends it back to the back of
   the bank. Skip this step for natural banks, whose employees cannot leave.
6. With the banker at the back of the bank, summon a boat next to it
   (`/summon minecraft:boat ~2 ~ ~`, `oak_boat` on 1.21.4). It should step away and return after
   `/kill @e[type=boat]` (`oak_boat` on 1.21.4).
7. Candidate check (the reported stall): place a bank where villagers can reach it, bring an
   unemployed adult villager near it (`/summon minecraft:villager ~ ~ ~ {VillagerData:{profession:"minecraft:none",level:1,type:"minecraft:plains"}}`),
   and wait without touching it. It should walk onto the block behind the bank and become a banker
   within about a minute, without another villager pushing it. Failure sign: it stops one block short.
8. Right-click the banker or deposit at the front. Normal bank use must still work.
9. `/time set 13000`: the banker goes to bed like other villagers.
10. If the village has two banks, check each banker stays at its own bank.

Repeat on 1.21.4 and record anything that looks wrong.
