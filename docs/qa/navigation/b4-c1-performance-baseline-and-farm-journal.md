# B4 and C1: performance baseline tooling and farm placement safety

Two parallel work packages from the plan, taken up on 2026-11-13 after Step 4. Both were reviewed
against the recent road and building-placement performance commits (`ddec935d`, `7068f876`,
`06ac47b1`, `bb7c154f`, and the road commits through `5aeea55b`) and neither is invalidated.
Agent review recorded here is a self-review.

## Review: are B4 and C1 invalidated by the rework?

- **C1 is not invalidated.** The rework added `StagedPlacement` (bank, library, lumbermill placed
  over several ticks) and `finishActivePlacementForShutdown`, which finishes the in-flight placement
  on a clean server stop. That closes the clean-stop case only. Still true after the rework:
  `finalizeTask` marked every village's farms as placed whether or not a farm failed, so a failed
  farm was never retried; the farm task's phase and cursors were held in memory only, so a crash
  or an unload mid-placement left part-built terrain with no record; and a restart re-ran the site
  search against terrain the interrupted attempt had already changed. The new staged placements
  carry the same crash exposure for the bank, library, and lumbermill; this package fixes farms only.
- **B4 is not invalidated.** B4 measures steady-state villager and scan cost in a busy village, to
  tell whether later steps helped and whether the cost is in villager AI at all. The rework changed
  village generation cost (planning worker, several steps per tick, staged placement), not
  steady-state villager AI. What changes is when to take the capture: on the current HEAD, after
  the rework, not before it.

## C1: what changed

- **A farm journal in `VillageFarmSavedData`.** Before the first farm is placed, the whole planned
  set is saved with its exact sites. Each placement counts an attempt before it writes a block and is
  marked done only when it succeeds. Codec bounds: 64 farms per village, footprint 1 to 256, attempts
  0 to 1000; an oversized list recovers only a bounded prefix.
- **`JournaledFarmTask`** wraps every farm placement in `FarmVillageBuildingProvider`, both the
  incremental and the synchronous path, so the tests exercise the production path.
- **Retry from the journal.** When a village has a journal, the provider replays the unfinished
  sites at their original positions instead of searching again.
- **No false completion.** `VillageGenerationPipeline.finalizeTask` marks a village's farms placed
  only when every journaled farm is done or has used its 3 attempts. An unfinished village stays
  unmarked, and the next detection pass retries just those farms. A given-up farm is logged.
- Phase accessor `FarmPlacementTask.phaseName()` for tests.

Not done, and why:

- A hard crash between a chunk being saved and the saved data being written can still lose the
  journal entry. Saved data and chunks flush together on the normal save, so the window is small;
  closing it would need a forced save per farm, which costs more than the risk.
- The bank, library, and lumbermill staged placements are not journaled.
- The water-containment pass runs once at the end of a pipeline run. A farm placed in an earlier
  run that was interrupted before that pass does not get it on the retry.
- `StructureTemplate.placeInWorld` is still one call per farm; the measurement below is the answer
  to "measure the worst single-tick cost", not a change.

| Requirement (deferred finding 1) | Evidence | Status |
| --- | --- | --- |
| Decide between reverting the incremental placement and making it resumable. | Made resumable: replaying the same site from scratch yields the same farm. | PASS |
| Never mark farms complete when a required placement failed. | `failedFarmPlacementIsRetriedThenGivenUp`; `VillageFarmJournalTest` (attempt limit, pending until done). | PASS (automated) |
| Make every mutating phase idempotent or provide rollback. | `interruptedFarmPlacementReplaysToTheSameFarmAtEveryStep`: stops a real placement after every step (0 to 17), replays the same site, and compares the block counts of the farm and its margin with an uninterrupted run. Every phase of the run was interrupted. | PASS (automated) |
| Interruption tests at every mutating phase, including save and reload and forced failure. | The test above; `interruptedFarmStaysPendingInTheJournalUntilItsReplayCompletes` (journal saved, loaded, replayed); `failedFarmPlacementIsRetriedThenGivenUp` (forced failure). | PASS (automated) |
| Measure the worst single-tick cost of template placement. | Logged by the interruption test: `PLACE_TEMPLATE` worst step about 6 ms for `plains_small_farm_1` on this PC, 1.21.1 and 1.21.4 (other phases under 1.3 ms). Larger farms were not measured. | PASS for the small farm |

Test comparison ignores crop blocks, whose type the processors choose per column.

## B4: what changed

The mod already has opt-in timing counters (percentiles per operation, logged at server stop in
`[ECAP][Performance]`). They covered village generation, scans, movement, and banks, but not whole
server ticks or villagers. Two operations were added:

- `SERVER_TICK`: one whole server tick.
- `VILLAGER_ENTITY_TICK`: one villager's tick, including its Brain and goals.

`VILLAGER_ENTITY_TICK` calls divided by `SERVER_TICK` calls is the average number of villagers
ticking per server tick, and its total divided by `SERVER_TICK`'s total is the share of server time
that villager ticks take. Both are inert unless detailed timing is on. The hooks are in
`event/PerformanceTimingEvents` and only time server-side villagers.

**The capture itself is not taken.** It needs a busy village in a real world, which only you have.
Taking it now, on this HEAD, gives the reference for the comparison after steps 9 and 15.

### How to take the capture (B4 run sheet)

1. In the dev run config set `advanced.debug.performanceTiming = true` in
   `config/emeraldcapitalism-common.toml`, or add the JVM argument
   `-Demeraldcapitalism.performanceTiming=true`.
2. Launch `.\gradlew :1.21.1:runClient` (or a dedicated server) and open your busiest village world.
3. Stand in the village. `/gamerule doDaylightCycle false` and `/time set 3000` so every capture sees
   work time, and wait 5 minutes (6000 ticks) without touching villagers.
4. Quit or `/stop`. Open `logs/latest.log` and find the line beginning `[ECAP][Performance]`.
5. Paste the `server_tick`, `villager_entity_tick`, `movement_manager_time`, `lumberjack_search`,
   `village_registry_manager_tick`, `village_detection_tick`, `bank_chest_cache_rebuild`, and
   `bank_inventory_total_refresh` entries into the table below, with the commit and the number of
   villagers in the world.
6. Repeat after step 9 and after step 15 on the same world and settings.

| Operation | Calls | Average ms | p95 ms | Max ms |
| --- | ---: | ---: | ---: | ---: |
| `server_tick` | | | | |
| `villager_entity_tick` | | | | |
| `movement_manager_time` | | | | |
| `lumberjack_search` | | | | |
| `village_registry_manager_tick` | | | | |
| `bank_chest_cache_rebuild` | | | | |

Commit: (HEAD when captured). Villagers loaded: (count). World and village: (name).

| ID | Requirement | Status |
| --- | --- | --- |
| B4.1 | Counters for whole server ticks and villager ticks. | PASS (compiled and verified; the events are exercised by every GameTest tick) |
| B4.2 | One capture of a busy village at the current HEAD. | BLOCKED (waiting for the developer) |
| B4.3 | Repeat after steps 9 and 15. | Not due |
