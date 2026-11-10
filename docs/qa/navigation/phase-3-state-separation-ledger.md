# Navigation refactor Phase 3 requirement-to-evidence ledger

> **Superseded.** This is the pre-implementation review from `97250b7b`. The
> authoritative Phase 3 record, with implementation evidence and the developer's
> approval, is [`phase-3-state-ledger.md`](phase-3-state-ledger.md). This file is kept
> as history; its `FAIL (not started)` rows describe the starting position only.

Phase 3 plan text: `docs/plans/navigation-refactor-implementation.md`, "Phase 3 —
Separate persistent gameplay state from transient execution". Status values
are `PASS`, `FAIL`, or `BLOCKED`. Nothing in Phase 3 is implemented yet; this
ledger records the starting position from a read-only source review of
`97250b7b`. It is an agent pre-review, not evidence that any row passes.

## Starting position (source review, not verified by a run)

- **Not serialized today (good):** the farmer work-cell `WorkLease`, its retry
  time and claimed positions live only on `VillageWorkCell` and are not in the
  `VillageRecord` codec. Ladder claims, tree reservations, sapling cache and the
  movement-request state are in-memory.
- **Serialized and meant to be durable:** `VillageRecord` fields such as
  `farmland_registry`, `mature_crop_work_queue`, missing doors and members;
  `LumberjackProductionAttachment` (`charcoal_quota`, `charcoal_furnace`).
- **Needs a decision:** `charcoal_furnace` (a `GlobalPos`) is stored with the
  lumberjack but behaves like a furnace reservation. The Phase 2 manifest says
  the quota is durable and the reservation transient, assigned to Phase 17.
  Phase 3 must either keep this explicitly durable with a stated reason or
  split it; it should not be silently left as is.
- **Cleanup is scattered, not centralized:** release code exists in at least
  `VillageRecord.releaseLeaseAndClaims`, `MovementRequestManager.release`,
  `VillagerBehaviorRegistry.release`, `VillagerLadderClaims.release`,
  `LumberjackTreeReservations.release`, and level-unload clears in
  `VillageRegistryEvents`. Which boundary (death/removal, profession change,
  dimension change, level unload, server stop) reaches which owner is not
  documented, and some boundaries (for example dimension change and server
  stop) have no visible hook for several owners.

## Requirements

| ID | Original requirement | Planned evidence | Status |
| --- | --- | --- | --- |
| P3.1 | Define task records that persist only player-meaningful progress. | A written list of every durable record with the player-visible reason, checked against each codec and attachment. | FAIL (not started) |
| P3.2 | Make leases, movement attempts, retries, ladder claims, and path-analysis caches session-local. | Tracked test per item showing it is absent from saved data; source trace of each owner. `charcoal_furnace` decision above. | FAIL (not started) |
| P3.3 | Centralize cleanup for death/removal, profession change, dimension change, level unload, and server stop. | One cleanup owner per transient item, reached from each of the five boundaries; a table of boundary versus owner. | FAIL (not started) |
| P3.4 | Cleanup keyed by village, profession, villager UUID, task, and world/dimension where applicable. | Key design recorded; production-backed tests that identical targets in two villages or two dimensions are not released by each other. Profession-keyed claim API itself is Phase 11. | FAIL (not started) |
| P3.5 | Save/reload tests: durable progress survives, transient ownership does not. | Tracked GameTest or unit test through the real codec/attachment round trip. | FAIL (not started) |
| P3.6 | Lifecycle tests for each cleanup boundary, plus negative tests that one village cannot release another village's work. | Tracked tests per boundary and a cross-village negative case. | FAIL (not started) |
| P3.7 | Developer manual: temporary `state_reload` scenario command with a console marker; developer saves, quits, relaunches and reopens; read-only lease inspection before and after; same villager resumes or reacquires once with no duplicated effect or frozen movement. | Run sheet and command prepared by the agent. Only Adam can mark this PASS. | BLOCKED (needs developer action) |
| P3.8 | Gate: all transient state has a single cleanup owner and no execution-only record is serialized as authoritative state. | Architecture check extended with a negative case (serializing a lease or claim fails) and a positive case (the durable queue is accepted); fresh semantic review. | FAIL (not started) |

Out of scope for Phase 3 (assigned elsewhere): the profession-keyed claim API
(Phase 11), pumpkin pipeline (Phase 15), furnace/tree reservation redesign
(Phase 17), begging redesign (Phase 21).
