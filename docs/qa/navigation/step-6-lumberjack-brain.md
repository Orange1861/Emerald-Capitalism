# Step 6 ledger: lumberjack on the Brain

Step 6 of the foundation-first order in
[`navigation-refactor-implementation.md`](../../plans/navigation-refactor-implementation.md)
delivers Phase 17: the lumberjack's whole job as one Brain-owned task, on the step 5 claim
contract and furnace pool. Its gate closes together with step 5. Agent review recorded here is a
self-review. **Everything below was written without a Gradle build; nothing is compiled or run
yet.** The first run is expected to show compile errors to fix.

## What changed

- `LumberjackWorkBehavior` (package `entity.ai`, so it can use the package-private tree scanner and
  reservations) replaces `LumberjackGoal`, which is deleted, along with its GoalSelector injection in
  `VillageRegistryEvents`. One instance per lumberjack Brain, added to the WORK package at priority 1.
- Composition (`VillagerGoalPackageIntegration`): for a lumberjack the package drops vanilla's
  job-site strolling gate and `SetWalkTargetFromBlockMemory` (the pull back to the job site, which
  would drag a working lumberjack home), keeps `WorkAtPoi`, and adds the behavior. The behavior starts
  only while `WALK_TARGET` is absent, publishes every journey through `BrainMovementIntent`, never
  creates a path, waits while another behavior owns the walk target, and yields if its own target is
  replaced. It returns to the sawmill itself when it has nothing else to do.
- Stages in one task: job-site return, tree search and selection (local probe, sapling hints, resumable
  scan), travel, consecutive log work, drops, leaf clearing, replanting, and furnace production.
  The single `failed` flag is replaced by named outcomes (`NO_REACHABLE_TREE`, `JOB_SITE_UNREACHABLE`,
  `PRODUCTION_BLOCKED`). A full inventory stops log breaking, so nothing spills.
- Furnaces come only from the village's `FurnacePool` (step 5). The 33 x 33 x 13 box scan is gone.
  A lumberjack asks every 100 ticks, only with a full batch of quota logs and fuel; a refusal keeps it
  cutting and queues it. After a grant it loads the batch (up to eight logs with one charcoal as fuel,
  or the one-log seed batch with four planks), leaves, and returns at a stage boundary to collect. The
  claim is released only when the furnace is verified empty or holds only items the pool did not place.
  Foreign items are never loaded over or taken. A saved `charcoal_furnace` is restored as a claim.
  The waiting quota stops at two batches; a village with no pool furnace accrues none and, once its
  pool has been read, owes none.
- Tests: `LumberjackGameTests` is rewritten as natural-AI tests (the old tests built and ticked the
  goal by hand); the `BankGameplayGameTests` lumberjack delivery test no longer injects the goal.
  `gradle/navigation-architecture.json` follows the rename and drops the deleted goal's caller entries.

## Not done in Step 6, and why

- **Tree claims still use `LumberjackTreeReservations`** (a per-level static, keyed by villager), not
  the village `ClaimLedger`, and **sapling ownership is still per villager** in `LumberjackSaplingCache`.
  The plan moves both to the village (saplings saved on the village record, any lumberjack of the village
  may harvest them). That is a persisted-schema change and was left out of this first pass.
- **Furnace claim order with a tree claim** (tree first, furnace second) is not exercised, because
  tree claims are not on the ledger yet.
- **Bank-trip timing** (a trip only at a stage boundary) is not enforced: `VillagerInventoryBankGoal` is
  still legacy until step 13. The lumberjack waits while that goal owns the walk target.
- **The scenario scenes** (`lumberjack_small_tree`, `lumberjack_tall_tree`, `lumberjack_shared_furnace`)
  and the standing-check additions for step 7 are not built.
- Several plan cases have no test yet: panic, sleep, save and reload mid-batch, unload, two
  lumberjacks sharing one furnace, a furnace removed while claimed, and cross-village isolation. The
  core pool tests cover the rules; the game-level cases are still to write.

## Requirement ledger (summary)

| ID | Requirement (Phase 17) | Status |
| --- | --- | --- |
| P17.1 | Job-site travel, tree travel, consecutive log work, drops, furnace travel, replanting as stages of one Brain-owned task. | PASS (automated, 1.21.1) |
| P17.2 | The selected tree survives arrival at one log. | PASS (automated, 1.21.1: felling, tall, ten-block, two-lumberjack tests) |
| P17.3 | Furnaces are a village-scoped shared pool, never derived from the lumberjack count. | PASS (automated, 1.21.1: load and collect through the pool) |
| P17.4 | Bounded backoff instead of repeated pathing to an unavailable furnace. | BLOCKED (ask every 100 ticks, trip retry cap; not run) |
| P17.5 | Named interruption, invalid-tree, inventory-full, and production-blocked outcomes. | BLOCKED (written, not run) |
| P17.6 | Natural lumberjack journey with no hand-ticked goal. | PASS (automated, 1.21.1) |
| P17.7 | Cases: fewer furnaces than lumberjacks, none, removed while claimed, contention, cross-village. | BLOCKED (core tests only; game cases not written) |
| P17.8 | Panic, sleep, invalid tree, unreachable upper logs, two lumberjacks, save/reload, unload. | BLOCKED (two lumberjacks and tall trees written; the rest not written) |
| P17.9 | Amendment: no player furnace ever loaded or emptied; quota cap; no-furnace village delivers every log; sapling ownership on the village; no felling inside another village. | PARTIAL (first three written; sapling ownership and the village rule not done) |
| M6.1 | Developer observes `lumberjack_small_tree`, `lumberjack_tall_tree`, the panic reset, and `lumberjack_shared_furnace`. | BLOCKED (scenes not built; waiting for the developer) |

## What the developer must run

```powershell
$env:GRADLE_OPTS='-Djava.net.preferIPv4Stack=true'; .\gradlew.bat compileJava verifyNavigationArchitecture verifyRegressionCoverage :core:test --no-daemon --no-configuration-cache --console=plain
```

Send the compile errors back. Then `.\gradlew.bat runGameTestServer --no-daemon --no-configuration-cache --console=plain`.

## In-game check once it compiles

1. `.\gradlew :1.21.1:runClient`. In a village with a lumberjack and trees, at work hours the lumberjack
   should walk to a tree, fell it log by log without wandering off after one log, collect the drops, and
   replant. Failure signs: it cuts one log and walks away; it stands at the sawmill and does nothing; it
   walks to the sawmill while a tree is half cut.
2. With a lumbermill furnace (a furnace inside the lumbermill building) and enough logs, it should load
   the furnace, go back to cutting, and return to collect charcoal. A furnace you filled yourself must
   never be loaded or emptied.
3. Panic: spawn a pillager near it. It should run; afterwards it goes back to its tree.

## Recorded results (2026-11-18, developer run, 1.21.1)

Compile passed. The first game-test run failed every tree-cutting lumberjack test; the debug lines showed vanilla's job-site walker still running (it is an anonymous `OneShot`, so removing it by class matched nothing and the package test passed vacuously) and a saved-furnace restore restarting the collect trip every other tick. Both were fixed (the walker is now removed by its priority-2 place in the package; the restore runs only when no claim exists). After that all lumberjack tests pass: felling and replanting, tall and ten-block trees, two lumberjacks, trees left alone, a shared furnace loaded and collected, a foreign furnace untouched, a no-furnace village owing no charcoal, and the package and Brain-only guards. The run showed 6 failures: the five baseline ones and `panicstopsthebankerwalkingtoworkandworkresumes`, a step 4 banker test that also failed once on 1.21.4 earlier. It is not in lumberjack code and looks flaky; it is not yet diagnosed.
