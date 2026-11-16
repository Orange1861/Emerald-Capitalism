# Step 5 ledger: task stages and the claim contract

Step 5 of the foundation-first order in
[`navigation-refactor-implementation.md`](../../plans/navigation-refactor-implementation.md)
delivers Phase 11 plus its 2026-11-10 amendment: one task lifecycle vocabulary, the claim
contract, the village furnace pool with its claim stages, and the farmer bank-employment
exclusion. Per the plan, the furnace pool and claim stages have no production caller until
step 6, so **this gate stays open and closes together with step 6.** Agent review recorded
here is a self-review.

Status when written (updated after the footprint decision): the core code is compiled and its 67 core tests pass (plain `javac` and
the JUnit launcher). **The mod code and the new GameTests were written without a Gradle build**
(Gradle fails to start in the agent's environment with "Unable to establish loopback
connection"), so nothing in the mod has been compiled or run yet. See "What the developer must
run".

## What changed

Core (Minecraft-free, `core/`):

- `navigation/TaskStage`, `TaskInterruption`, `TaskLifecycle`: the stage vocabulary (acquire,
  claim, move, interact, continue, complete, suspend, abandon, fail), what each interruption
  does to a task, and what it does to a claim (release, keep, or hand to the village). Route
  arrival moves a task to its interaction and can never complete it.
- `navigation/ClaimLedger`: claims keyed by village, profession, kind, and target, each with an
  owner and a generation. A release must present the exact claim, so late cleanup from an old
  task releases nothing. `tryClaimAll` takes several claims in kind order (tree first, furnace
  second), all or nothing; `releaseAll` releases in reverse.
- `world/forestry/FurnaceClaim`: the loading, smelting, and collecting stages, what the claim
  placed, and a check that compares the three furnace slots with that record: a log removed
  before smelting refunds one conversion, charcoal removed counts as done, the holder never
  takes more than it placed, and foreign items are never taken.
- `world/forestry/FurnacePool`: one claim per furnace, a longest-wait-first queue, orphan
  hand-off to the village (holder gone, or charcoal left about 6,000 ticks), restoring a saved
  `charcoal_furnace` as a claim, and a 600-tick grace after creation so reloaded holders restore
  before anyone else is offered a furnace.
- `CharcoalProductionPolicy.accrueQuotaWhileWaiting`: a waiting lumberjack's quota stops growing
  at two batches (16).

Mod:

- Farmer bank-employment exclusion. `VillageRecord.isEligibleFarmer` now rejects a villager
  that is on the employee list of the village's loaded bank, using a new uncached
  `BankEmployeeLookup.isEmployeeOfVillageBank`. Roster refresh, nearby-farmer selection, roster
  pruning, and lease assignment all go through that check. `BankBlockEntity` releases a farmer's
  lease and exact claims at the moment the villager becomes an employee
  (`registerSpawnedEmployee`, `registerEmployeeFromJob`). Removal from employment needs no call:
  the next roster refresh finds the villager eligible again.
- The pool's index (developer decision, option A, 2026-11-16). The registry now saves the
  footprint of every generated lumbermill (`lumbermill_footprints`, recorded by the lumbermill
  building provider and by the pool-generated lumbermill path). `VillageRecord` owns a transient
  `ClaimLedger` and `FurnacePool`; `acceptFurnaceSighting` accepts only a furnace block inside the
  village bounds and a footprint; `reconcileFurnacePool` (one village per 20 ticks from the registry
  manager, each village at most every 100 ticks) reads the loaded lumbermill chunks' block
  entities; `LumbermillFurnaceEvents` adds placed furnaces and drops broken ones;
  `releaseLumberjackClaims` (called from `releaseVillagerWork`, so death, unload and profession
  change) hands a lumberjack's furnace to the village and releases its other claims. Villages whose
  lumbermill was generated before this step have no footprint and so no pool furnaces (pre-release
  policy).
- `FarmerBankEmploymentGameTests` (4 GameTests), `LumbermillFurnacePoolGameTests` (5) and the
  regression baseline (`gradle/navigation-regression-baseline.json`): 324 discovered, 322 executed, new hash. The
  baseline was already three tests behind (312) before this step; the figure is recomputed from
  the sources with the same rule `discoverGameTests` uses, and the same script reproduces the
  old hash at the commit that last changed the file.

Production callers: every new core class is first used by the step 6 lumberjack behavior
(`TaskLifecycle` and `TaskInterruption` for its stages and stops, `ClaimLedger` for tree and
furnace claims, `FurnacePool` and `FurnaceClaim` for furnace production). Until then they have
no gameplay caller and prove nothing about gameplay; their tests prove only their own rules.

## Not done in Step 5, and why

- **Farmer leases do not carry a generation yet, and there is no pumpkin claim on
  `VillageRecord`.** `HarvestPumpkinGoal` holds no claims and there is no pumpkin work item to
  claim; the lease and claim code is rewritten in step 9, and the goals that would pass a
  generation are deleted there. The contract (kind `ATTACHED_PUMPKIN`, village, `farmer`, target,
  owner, generation) is in `ClaimLedger` and tested, so step 9 adopts it instead of reinventing
  it. Adding it to `VillageRecord` now would be an abstraction with no caller.
- **No retry policy in the task.** The plan asks for retry policy to live in the task rather than
  a global movement owner. With no task running yet there is nothing to retry; step 6 adds it.
- **`task_lifecycle_pilot` scenario is not built.** A pilot task made only for the scenario would
  be the test-only substitute the plan rules out. The lumberjack is the first real task; its
  scenes come in step 6 and the standing check in step 7.
- **Farmer dispatch range** (from the job site, not the farmer's position) belongs to Phase 15
  and is unchanged.

## Requirement ledger

| ID | Requirement (Phase 11 and amendment) | Implementation | Automated evidence | Negative case | Status |
| --- | --- | --- | --- | --- | --- |
| P11.1 | Model task stages. | `TaskStage`, `TaskLifecycle`. | `TaskLifecycleTest`: `.\gradlew :core:test`. | Stages cannot be skipped; a task never started advances nowhere; terminal stages accept nothing. | PASS (core); no gameplay caller until step 6 |
| P11.2 | Task completion is not arrival at an intermediate position. | `TaskLifecycle.routeArrived` moves to INTERACT; `complete` only from INTERACT or CONTINUE. | `routeArrival...NeverCompletes...`, `completeIsOnlyLegalAfterInteraction`. | Completing while still moving is refused. | PASS (core); no gameplay caller until step 6 |
| P11.3 | Interruption reasons; which release, suspend, or reacquire. | `TaskInterruption.effect`, `dispositionFor`; `TaskLifecycle.resume` (new generation). | `everyInterruptionHasTheDocumentedEffect`, `durableClaimsAreNeverReleasedByAnInterruption`. | A durable claim is never RELEASE for any interruption. | PASS (core); no gameplay caller until step 6 |
| P11.4 | Retry policy in the task, not a global owner. | None. | None. | Not applicable. | BLOCKED (no task exists to hold it; step 6) |
| P11.5 | Exact attached-pumpkin claims with village, FARMER, cell, pumpkin, owner, generation. | `ClaimLedger.Kind.ATTACHED_PUMPKIN` only. | `anAttachedPumpkinIsClaimed...` (core). | Another owner cannot claim it. | BLOCKED (no pumpkin work item or claim on `VillageRecord`; step 9) |
| P11.6 | Bank employment is an authoritative farmer exclusion at roster refresh, nearby selection, and lease assignment. | `VillageRecord.isEligibleFarmer`, `BankEmployeeLookup.isEmployeeOfVillageBank`. | `FarmerBankEmploymentGameTests`: `aBankEmployeeFarmerIsNotOnTheRosterAndTheNormalFarmerGetsTheWork`, `aBankEmployeeFarmerAloneInTheVillageIsNeverLeasedWork`. Run with `.\gradlew runGameTestServer`. | The employee stands nearer the work than the normal farmer and still gets nothing; alone, dispatch assigns 0. | PASS (automated, 1.21.1) |
| P11.7 | Acquiring bank employment releases the farmer lease and exact claims; removal permits later eligibility. | `BankBlockEntity.releaseFarmerWork` on both registration paths. | `acquiringBankEmploymentReleasesTheFarmersLeaseAndClaims`, `leavingBankEmploymentLetsAnOrdinaryFarmerBeLeasedWorkAgain`. | The claim and lease are checked gone after hiring. | PASS (automated, 1.21.1) |
| P11.8 | Late cleanup from an old task cannot release a new task's lease. | `ClaimLedger.release(Claim)` and `FurnacePool.release` match the full claim. | `aLateReleaseFromAnOldTask...`, `lateCleanupWithAStaleClaim...`. | Old generation releases nothing, new claim survives. | PASS for ledger and furnace claims (core); farmer leases BLOCKED (step 9) |
| P11.9 | Production-backed profession isolation: identical targets in two professions do not share or release each other's leases. | Profession is part of `ClaimLedger.Key`. | `identicalTargetsInTwoProfessionsDoNotMeet` (core). | Releasing the farmer's claim leaves the lumberjack's. | BLOCKED (core proof only; no production caller until steps 6 and 9) |
| A.1 | A task holding several claims takes them in a fixed order, tree then furnace, and releases in reverse. | `ClaimLedger.tryClaimAll`, `releaseAll`. | `severalClaimsAreTakenTreeFirst...`, `aFailedMultiClaim...` (two). | Failure rolls back what it took and keeps what the owner already held. | PASS (core); no gameplay caller until step 6 |
| A.2 | Furnace pool: one claim per furnace; asked for only with a full batch; queue longest wait first; refused lumberjack keeps cutting; a broken furnace drops its claim and the owner rejoins. | `FurnacePool`. | `FurnacePoolTest` (eachFurnaceHasAtMostOneClaim, theLongestWaiting..., aBrokenFurnace..., aStaleQueueEntry...). | A later asker cannot jump the queue; a stale queue entry cannot block it. | PASS (core); no gameplay caller until step 6 |
| A.3 | Quota stops growing at two batches while waiting. | `CharcoalProductionPolicy.accrueQuotaWhileWaiting`. | Three tests in `FurnacePoolTest`. | Quota above the cap is kept, not cut. | PASS (core); no gameplay caller until step 6 |
| A.4 | After a reload the saved `charcoal_furnace` becomes a claim again before the pool offers it to anyone else. | `FurnacePool.restoreClaim` and the 600-tick grace. | `aSavedFurnacePositionBecomesAClaimAgain...` and three refusal cases. | A furnace held by another, gone, or taken by the village is not restored. | PASS (core); the lumberjack must call it on load (step 6) |
| A.5 | Claim stages and the slot check: items removed or added by someone else; only charcoal, only the recorded count; foreign items never taken; release only when verified empty or only foreign items remain. | `FurnaceClaim`, `FurnacePool.check`. | Six tests in `FurnacePoolTest`. | The holder never takes foreign charcoal; a furnace with someone else's items is not offered. | PASS (core); no gameplay caller until step 6 |
| A.6 | Orphaned batches: holder gone or away about 5 minutes after charcoal is ready; the next in line collects. | `FurnacePool.handToVillage`, `prune`. | Three tests in `FurnacePoolTest`. | A returning holder's old claim is stale and changes nothing. | PASS (core); no gameplay caller until step 6 |
| A.7 | The pool's index: lumbermill placer, block events, 100-tick reconciliation; furnaces inside village bounds that are part of a lumbermill. | Saved footprints; `VillageRecord.acceptFurnaceSighting`, `reconcileFurnacePool`, `releaseLumberjackClaims`; `LumbermillFurnaceEvents`. | `LumbermillFurnacePoolGameTests` (5): membership, reconciliation, hand-off, broken furnace, footprint save and reload. | A furnace outside the footprint, outside the village, or a blast furnace is refused. | PASS (automated, 1.21.1); the lumberjack's tree scan does not report sightings until step 6 |
| M5.1 | Developer observes the farmer exclusion and, with step 6, the claim and furnace behavior in game. | Run sheet below. | None; only the developer can pass this. | Not applicable. | BLOCKED (waiting for the developer) |

## What the developer must run

Compile, architecture check, and core tests (run once with `-Djava.net.preferIPv4Stack=true` if
Gradle reports a loopback error):

```powershell
$env:GRADLE_OPTS='-Djava.net.preferIPv4Stack=true'
.\gradlew.bat compileJava verifyNavigationArchitecture verifyRegressionCoverage :core:test --no-daemon --no-configuration-cache --console=plain
```

GameTests (the four new tests are in `FarmerBankEmploymentGameTests`; the failures should still
be exactly the five known baseline ones):

```powershell
.\gradlew.bat runGameTestServer --no-daemon --no-configuration-cache --console=plain
```

Then wipe the generated test world if the run wrote `.\world` (see AGENTS.md).

## Developer run sheet for M5.1 (farmer exclusion)

1. `.\gradlew :1.21.1:runClient`, then repeat with `:1.21.4:runClient`.
2. In a village with at least one ordinary farmer and some farmland, trample a few farmland
   blocks so repairs are queued. The ordinary farmer should repair them as before.
3. Stand inside the bank's area and run
   `/summon minecraft:villager ~ ~ ~ {VillagerData:{profession:"minecraft:farmer",level:1,type:"minecraft:plains"},Xp:1}`.
   A villager spawned in the bank's area is registered as a bank employee.
4. Trample more farmland. The new farmer must not take repair or harvest work; only the ordinary
   farmer should. Failure sign: the summoned villager walks to the farms and repairs them.
5. Reset: `/kill @e[type=minecraft:villager,distance=..5]` on the summoned villager only.

The claim and furnace behavior has nothing to observe until step 6 moves the lumberjack onto it.

Record anything that looks wrong, with the version.

## Recorded results (2026-11-16, developer run)

On 1.21.1 compile, `verifyNavigationArchitecture`, `verifyRegressionCoverage` (324 GameTests) and `:core:test` pass. `runGameTestServer` ran 322 and failed exactly the five baseline tests; all nine new GameTests passed. The first run of the four farmer tests failed on a fixture fault (a villager spawned within 8 blocks of a bank is auto-registered as its employee); the bank was moved and they pass. 1.21.4 was not conclusive in the supplied log and still needs its own run.
