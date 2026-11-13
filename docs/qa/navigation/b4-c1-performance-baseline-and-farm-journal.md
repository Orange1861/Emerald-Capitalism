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

- The crash window between a chunk save and the saved-data write is closed for the journal
  entry: `VillageFarmSavedData.flushNow` writes the farm data file (temporary file, then an atomic
  move) after the journal entry exists and before the first block of the farm changes, once per farm.
  A completed farm is deliberately not flushed: a durable "done" ahead of the chunk save would lose
  the farm instead. The remaining case is the other direction: a crash after a farm finished but
  before the next normal save replays that farm at its site, which rebuilds the same farm and
  would discard player edits made to it in that interval. Two writers to the data file at once (the
  normal async save and this flush) are not coordinated; each writes a complete valid file.
- The bank, library, and lumbermill staged placements are not journaled.
- The water-containment pass runs once at the end of a pipeline run. A farm placed in an earlier
  run that was interrupted before that pass does not get it on the retry.
- `StructureTemplate.placeInWorld` is still one call per farm; the measurement below is the answer
  to "measure the worst single-tick cost", not a change.

| Requirement (deferred finding 1) | Evidence | Status |
| --- | --- | --- |
| Decide between reverting the incremental placement and making it resumable. | Made resumable: replaying the same site from scratch yields the same farm. | PASS |
| Never mark farms complete when a required placement failed. | `failedFarmPlacementIsRetriedThenGivenUp`; `VillageFarmJournalTest` (attempt limit, pending until done). | PASS (automated) |
| Journal entry durable before the first block changes. | `flushNow` runs between journaling and the first step; `interruptedFarmStaysPendingInTheJournalUntilItsReplayCompletes` checks the journal is complete and the terrain untouched at that moment; `VillageFarmJournalTest` checks the file is what the data storage reads (compressed `data` tag plus `DataVersion`), leaves no temp file, and a second flush replaces it. | PASS (automated); a real crash was not simulated |
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

**The capture was taken by the developer on 2026-11-13** (results below).
Taking it on this HEAD, on this HEAD, gives the reference for the comparison after steps 9 and 15.

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
| `server_tick` | 7,319 | 21.973 | 38.325 | 310.731 |
| `villager_entity_tick` | 402,427 | 0.109 | 0.163 | 73.908 |
| `movement_manager_time` | 10,451 | 0.010 | 0.034 | 5.837 |
| `movement_vanilla_path_plan_attempt` | 17,644 | 0.858 | 1.666 | 22.352 |
| `movement_helper_path_plan_attempt` | 17,024 | 0.587 | 1.657 | 14.861 |
| `lumberjack_search` | 3,078 | 0.064 | 0.135 | 13.030 |
| `village_registry_manager_tick` | 21,957 | 0.116 | 0.026 | 282.173 |
| `village_full_scan` | 142 | 11.727 | 64.166 | 282.092 |
| `village_detection_tick` | 7,319 | 0.017 | 0.010 | 44.881 |
| `bank_chest_cache_rebuild` | 23 | 0.556 | 1.012 | 2.468 |
| `bank_inventory_total_refresh` | 52 | 0.033 | 0.058 | 0.163 |

Captured 2026-11-13 at commit `b6b3fef3`, a 1.21.1 integrated server (client run), about 6 minutes of server time
(7,319 server ticks) after `/gamerule doDaylightCycle false` and `/time set 3000`. Raw line:
`b4-baseline-2026-11-13-performance-line.txt`.

What the numbers say:

- **Villagers:** 402,427 villager ticks over 7,319 server ticks is about **55 villagers ticking per server tick**.
- **Share of server time:** villager entity ticks took 44 s of 161 s, **27.3%** of server tick time. Average 0.109 ms per villager tick, p99 1.64 ms, worst 73.9 ms.
- **Server tick:** average **22.0 ms**, p95 38.3 ms, p99 58.2 ms, worst **311 ms**. The average is under 50 ms but the tail is long.
- **Path planning:** vanilla `createPath` attempts took 15 s and the mod helper's preflight attempts 10 s: together about **15.6%** of server tick time (these run inside villager ticks, so they overlap the villager share, not add to it). The helper preflight (`movement_helper_path_plan_attempt`, 17,024 calls) is the kind of work the refactor removes, so this is the number to watch after steps 9 and 15.
- **Spike:** `village_registry_manager_tick` (max 282 ms) and `village_full_scan` (max 282 ms) have the same worst case, so the worst single stall in this run is one full village scan inside the registry manager tick. That is the likely source of the 310 ms worst server tick.
- **Not a cost here:** banks (`bank_*`, under 13 ms total), lumberjack search (197 ms total), and the movement manager (107 ms total) are small.
- **Caveats:** this is an integrated server, so rendering shares the machine; one world, one run; the world and its villager count were not recorded beyond the derived average.

| ID | Requirement | Status |
| --- | --- | --- |
| B4.1 | Counters for whole server ticks and villager ticks. | PASS (compiled and verified; the events are exercised by every GameTest tick) |
| B4.2 | One capture of a busy village at the current HEAD. | PASS (developer capture 2026-11-13, commit b6b3fef3, recorded above) |
| B4.3 | Repeat after steps 9 and 15. | Not due |
