# Phase 3 persistent and transient state ledger

This ledger covers every Phase 3 Work, Luna automated-verification, Developer
manual-verification, and Gate requirement in
[`navigation-refactor-implementation.md`](../../plans/navigation-refactor-implementation.md).
It was created before Phase 3 implementation and is updated only with repository
evidence. Agent review recorded here is a self-review, not an independent review.

Tests named below are in `VillagerSessionCleanupGameTests` unless stated otherwise
and run under `.\gradlew :1.21.1:runGameTestServer` and
`.\gradlew :1.21.4:runGameTestServer`.

## Requirement ledger

| ID | Original requirement | Implementation or decision | Automated evidence | Negative or adversarial case | Developer observation | Status |
| --- | --- | --- | --- | --- | --- | --- |
| W3.1 | Define task records that persist only player-meaningful progress. | The existing durable owners below are the task records; no parallel task-record model is added, because the production task contract belongs to Phase 11. `beg_time` and `beg_donor_uuid` are removed from the `VillagerStatsAttachment` schema. | `savedStatsKeepBegCooldownButNotTheLiveBeggingSession`; `savedVillageKeepsWorkInventoryButNoLeaseOrClaim`. | A save that still contains the old begging fields decodes with no live session. | None. | PASS |
| W3.2 | Make leases, movement attempts, retries, ladder claims, and path-analysis caches session-local. | The transient audit below records each as in-memory only. Door claims gained an owner so they can be released per villager. | `savedVillageKeepsWorkInventoryButNoLeaseOrClaim`; `BankGameplayGameTests` asserts the construction slot is not encoded. | A decoded village has no lease, repair claim, or door claim. | None. | PASS |
| W3.3 | Centralize cleanup for death/removal, profession change, dimension change, level unload, and server stop. | `VillagerSessionCleanup` receives `EntityLeaveLevelEvent`, `LivingDeathEvent`, `LevelEvent.Unload`, and the profession seam in `VillagerNameRefreshMixin`; `EmeraldCapitalism.clearServerSessionState` delegates server stop to it. | `removalReleases…`, `deathReleases…`, `professionChangeEnds…`, `dimensionChangeReleases…`, `levelUnloadReleases…`, and `ZzzServerStopLifecycleGameTests`. | A bystander's state survives each boundary. Disabling the owner made the five boundary tests fail (a one-off diagnostic, not a permanent fixture; the tests themselves are the regression coverage). | None. | PASS |
| W3.4 | Ensure cleanup is keyed by village, profession, villager UUID, task, and world/dimension where applicable. | Village work and door claims release per `VillageRecord` and villager UUID; tree reservations per level and UUID; ladder claims per dimension and villager. Profession change releases work but keeps the route-level ladder claim and social sessions. Village and profession keys on tree, sapling, furnace, and construction claims stay with Phases 17, 18, and 20 as already frozen. | `oneVillageCannotReleaseAnotherVillagesWork`; `levelUnloadReleasesOnlyThatLevelsSessionState`; `professionChangeEndsOldWorkButNotTheLadderTraversal`. | A foreign village's release returns false and changes nothing; one level's unload leaves the other level's state; an unchanged profession releases nothing. | None. | PASS |
| A3.1 | Add save/reload tests proving durable task progress survives where intended while transient ownership does not. | Round trips through the real codecs. | `savedStatsKeep…`; `savedVillageKeeps…`; `stateReloadScenarioReachesALeaseAndInspectionDoesNotChangeIt`; the existing bank persistence test. | Durable repair and missing-door work survive; lease and claims do not. | None. | PASS |
| A3.2 | Add lifecycle tests for each cleanup boundary and negative tests showing one village cannot release another village's work. | Each boundary uses its real entry point; level unload calls the production handler with a real event for the Nether only, so the shared overworld fixture is not torn down. | The tests under W3.3 and W3.4. | `oneVillageCannotReleaseAnotherVillagesWork`. | None. | PASS |
| M3.1 | Temporary `state_reload` scenario, mid-job marker, read-only lease inspection, Save and Quit, reload; the same villager safely resumes or reacquires once, with no duplicated world effect, frozen movement, or pre-reload transient claim. | `/ecapscenario state_reload start` and `inspect`. The run sheet is below. | `stateReloadScenario…` proves the scenario reaches a lease and that inspection is read-only; it does not replace the manual reload. | Not applicable. | **Required from the developer.** | BLOCKED |
| G3.1 | All transient state has a single cleanup owner and no execution-only record is serialized as authoritative gameplay state. | `sessionCleanup` in `gradle/navigation-architecture.json` lists every release call; `validateSessionCleanupCalls` rejects any use outside the owner or defining type. | `verifyNavigationArchitecture`; `NavigationArchitectureScannerTest`. | Fixtures reject a call outside the owner, through whitespace, and through a method reference, and accept the owner and defining type. | None. | PASS for the automated gate; Phase 3 remains incomplete until M3.1. |

The baseline's test counts and identity hash were updated deliberately for the new
tests (275 discovered, 273 executed). Its expected-failure list is unchanged.

## Audit of persisted task state

| Owner | Durable fields | Decision |
| --- | --- | --- |
| `VillageRecord` / `VillageWorkCell` | Farmland registry, repair and mature-crop work, door registry, missing doors, members, governance, opinions | Player-meaningful progress; kept. |
| `VillagerStatsAttachment` | Hunger timers, `last_beg_time`, naming, family, emerald balance | Kept. The cooldown is a gameplay invariant. |
| `VillagerStatsAttachment` | `beg_time`, `beg_donor_uuid` | **Execution-only; removed from the schema.** |
| `LumberjackProductionAttachment` | Charcoal quota, pending furnace position (`charcoal_furnace`) | Kept deliberately. The position records where this lumberjack's logs are already smelting, which is real world progress that must survive a reload. It reserves nothing and excludes no other villager; the owner-bearing furnace reservation is frozen for Phase 17. |
| `BankBlockEntity` | Identity, employees, chest and processor positions, construction site, takeover lock, control settings | Kept. The construction slot owner was already absent. |
| `BankAccountData` | Balances and numbering | Kept; holds no task claims. |

## Audit of transient state

| State | Location | Cleanup |
| --- | --- | --- |
| Work-cell leases, roster, repair and crop claims | `VillageRecord` fields | Owner, through `VillageRegistryManager.releaseVillagerWork` and `shutdown` |
| Door claims and their owners | `VillageRecord` fields | Owner, through `releaseDoorClaims` and `shutdown` |
| Movement requests, attempts, and retry cooldowns | `MovementRequestManager.STATES` | Owner |
| Behavior bindings | `VillagerBehaviorRegistry` | Owner |
| Ladder claims and active exits | `VillagerLadderClaims` | Owner; block invalidation and stale purge stay with `VillagerLadderClaimEvents` |
| Tree reservations and sapling ownership | `LumberjackTreeReservations`, `LumberjackSaplingCache` | Owner. An unloaded lumberjack keeps its saplings. |
| Breeding sessions | `VillagerBreedingSessions` | Owner |
| Begging session | `VillagerStatsAttachment` in-memory fields | Owner on removal and death. A profession change keeps it. |
| Bank construction slot | `BankBlockEntity` field | Owner |
| Parent-assignment retries | `VillagerSpawnEvents` | Owner on leave and server stop (production trace; no dedicated test) |
| Fence-gate use cache | `InteractWithFenceGateBehavior` | Owner, through `VillageRegistryEvents` |
| Path and retry fields inside goals and behaviors | Goal instances | End with the entity |
| Tree scan budget | `LumberjackTreeScanner` | Weakly keyed by level; not ownership |
| Wandering trader ladder caches | `WanderingTraderEvents` | Non-villager exception with its own single owner |

## Developer run sheet for M3.1

Use a disposable Superflat Creative world with cheats enabled, on 1.21.1:

1. `.\gradlew :1.21.1:runClient --no-daemon --no-configuration-cache --console=plain`
2. Create the world, stand on open ground away from any village, and run
   `/gamerule doDaylightCycle false`, `/time set day`, then
   `/ecapscenario state_reload start`.
3. Wait for the gold chat message `[state_reload] MID_JOB` (also logged as
   `[ECAP][StateReload] MID_JOB`). It appears once the farmer holds a work lease,
   normally within a few seconds. A red chat message means the job already finished
   and the attempt must restart in a new world. The farmer then walks a ring of
   16 repairs 30 blocks across, which takes about a minute.
4. Straight away, stand inside the scene and run `/ecapscenario state_reload inspect`.
   Note the `leases=[...]` value and `durableRepairs`. The check only counts while
   `durableRepairs` is above 0 and `leases` shows the farmer.
5. While that is still true, use Save and Quit to Title, close the game, run the same
   command as step 1, and reopen the world.
6. Run the inspect command straight away, and again after about 30 seconds.

**Pass:** right after reload `leases=[]` or the same single farmer; after 30 seconds
exactly one lease, held by the same farmer UUID, while repairs remain; `durableRepairs`
never rises and keeps falling to 0; the farmer keeps moving instead of freezing; no
crash or mixin error in the log.

**First attempt, November 3–10, 2026:** the developer reported "all values stay at 0
before and after reload". The client log shows the farmer took the lease and finished
all six repairs in about five seconds of game time, so the first inspect and the
reload came after the job ended. No stale lease, repeated repair, frozen farmer, or
error appeared, but the run did not exercise a mid-job reload, so M3.1 stays
`BLOCKED`. The scenario was widened in response.

**Second attempt, November 10, 2026:** the developer saved and quit mid-job and
reported the before and 30-second inspect lines. The logs show durable repairs
falling from 6 to 5 across the reload and on to 1, and the same farmer taking one new
lease a second after rejoining rather than keeping a pre-reload lease. At the
30-second inspect, one repair remained with no lease: the ring was 40 blocks across,
and the farmer finished at the edge opposite that repair, beyond the 32-block farmer
dispatch range. That range rule predates Phase 3 and caused the same pauses before
the reload. The 30-second criterion was therefore not met, so M3.1 stays `BLOCKED`,
and the ring was narrowed to 30 blocks. The dispatch-range behavior is recorded for
Phase 15.

The same log query works from outside the game:
`Select-String -Path versions\1.21.1\run\logs\latest.log -Pattern "StateReload"`.
