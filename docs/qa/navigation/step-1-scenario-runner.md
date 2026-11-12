# Step 1 ledger: scenario runner and mixed-village safety scene

Step 1 of the foundation-first order in
[`navigation-refactor-implementation.md`](../../plans/navigation-refactor-implementation.md)
delivers the core of Phase 6 (a minimal scenario runner) and replaces Phase 7 with
one mixed-village scene, recorded before any behavior moves and re-run after every
later step (working rule 7). Agent review recorded here is a self-review.

Tests are in `ScenarioRunnerGameTests` and run under
`.\gradlew :1.21.1:runGameTestServer` and `.\gradlew :1.21.4:runGameTestServer`.

## What was built

- `scenario/ScenarioCommand` registers `/ecapscenario load|start|action|inspect|result|reset`
  and the older `state_reload` scene. Caller: `EmeraldCapitalism.onRegisterCommands`, which
  passes `FMLEnvironment.production`; a production game registers nothing.
- `scenario/ScenarioRunner` holds the one loaded scene and observes it every 20 ticks.
  Callers: `ScenarioCommand` and `ScenarioRunnerGameTests`. `VillagerSessionCleanup.releaseAll`
  clears it at server stop.
- `scenario/MixedVillageScene` builds a 20x20 glass pen registered as a mod village: a farmer,
  librarian, and cleric, each with a bed and a job site, plus a bell. Actors are held with
  NoAI until `start`. Callers: `ScenarioRunner.load`, used by both the command and the GameTest,
  so the manual and automated scenes are the same code.
- `VillageRegistryData.removeScenarioVillage` lets `reset` remove the scene's village. It
  refuses a village that owns a bank, library, lumbermill, or manager.

After `start` the scene changes the world only through named external events: time of day
(`work`, `meet`, `rest`), a held pillager appearing and leaving (`panic`, `calm`), and a player
opening a trade (`trade`). It does not tick behaviors, write Brain memories, set paths, claim
POIs, or move villagers. `inspect` and `result` only read.

## Checkpoints

| Checkpoint | Triggered by | Pass condition | Limit |
| --- | --- | --- | --- |
| work | `action work` (day time 2500) | Every actor seen in WORK within 2.5 blocks of its own job site | 1200 ticks |
| trade | Any player trade with an actor | After the trade closes, that actor is no longer trading and its activity matches its schedule | 400 ticks after close |
| panic | `action panic` | Every actor seen in PANIC | 200 ticks |
| calm | `action calm` | Every actor back in its scheduled activity | 600 ticks |
| meet | `action meet` (day time 9200) | Every actor seen in MEET within 7 blocks of the bell | 1200 ticks |
| rest | `action rest` (day time 13000) | Every actor seen asleep | 1200 ticks |

## Requirement ledger

| ID | Requirement (plan wording) | Implementation | Automated evidence | Negative case | Status |
| --- | --- | --- | --- | --- | --- |
| S1.1 | Scenario runner: `scenario load`, `start`, `action`, `inspect`, `result`, `reset`. | `/ecapscenario` subcommands backed by `ScenarioRunner`. | `scenarioCommandsAreRegisteredOnlyOutsideProduction` checks every subcommand exists; `mixedVillageKeepsVanillaWorkMeetRestPanicAndTrade` drives load, start, action, result, and reset through `ScenarioRunner`. | Load while a scene is loaded, unknown ids, and actions before start are refused with a message. | PASS |
| S1.2 | QA commands are unavailable in normal production play. | `ScenarioCommand.register(dispatcher, FMLEnvironment.production)`. | `scenarioCommandsAreRegisteredOnlyOutsideProduction`: a production dispatcher gets nothing; a development dispatcher and the live development server get the commands. | The production case is the negative. | PASS |
| S1.3 | Fixtures spawn ordinarily registered villagers, assign profession and village context normally, and let the server tick AI. | `MixedVillageScene.build` and `release`. | The mixed-village test asserts actors are held before start, have no WALK_TARGET, PATH, JOB_SITE, HOME, or MEETING_POINT before the Brain runs, and are released by start. | The test fails if any of those memories exists before start. | PASS |
| S1.4 | Reuse the same fixture builders for automated tests and manual scenes. | Both go through `ScenarioRunner.load`. | Same code path; no separate test builder exists. | Not applicable. | PASS |
| S1.5 | One mixed-village scene covering work, meet, rest, sleep, panic, and trade, recorded before any behavior moves. | `mixed_village`. | `mixedVillageKeepsVanillaWorkMeetRestPanicAndTrade` on 1.21.1 and 1.21.4; results below. | Each checkpoint fails after its limit and the failure message prints every actor's inspection line. | PASS |
| S1.6 | The scene is part of the standing check after every later step. | The test is in the normal GameTest suite and listed under `villager-gameplay` in `gradle/regression-coverage.json`. | `verifyRegressionCoverage`. | Not applicable. | PASS |
| M1.1 | Developer loads the scene, starts it, follows the checkpoints, and records `result`. | Run sheet below. | None; only the developer can pass this. | Not applicable. | BLOCKED (developer) |

Not in Step 1, by the 2026-11-10 amendment ("step 1 builds only a minimal scenario runner and
the rest grows with each profession"): the Phase 6 reachable, unreachable, interrupted, and
two-villager contention variants, the Phase 8 interruption matrix, and moving `state_reload`
onto the runner (planned with the farmer, step 9). The baseline file's discovered and executed
counts and identity hash were updated for the two new tests (277 discovered, 275 executed);
its expected-failure list is unchanged.

## Recorded baseline

Before Step 1 (HEAD 6d8cc718): `verifyNavigationArchitecture --no-configuration-cache` passed.
GameTests ran 273 tests with the five known failures on 1.21.1 (`contestedgovernor…`,
`malformedbankstatefailsloudly`, and the three natural ladder tests) and six on 1.21.4 (the same
five plus the known flaky `normalfarmerharvestsandreplantsmaturewheat`).

After Step 1 (uncommitted tree on 6d8cc718 plus 2fcb5c37): compile, `verifyNavigationArchitecture`,
and `verifyRegressionCoverage` passed (308 sources scanned, 277 GameTests discovered). GameTests
ran 275 tests on each target; the failures are exactly the baseline set above, and both new
tests passed on 1.21.1 and 1.21.4. The mixed-village runner's own result lines:

```text
1.21.1: [ECAP][Scenario] RESULT mixed_village: released 440 ticks ago, day time 13100 | work PASS after 78 ticks | trade PASS after 0 ticks | panic PASS after 38 ticks | calm PASS after 18 ticks | meet PASS after 78 ticks | rest PASS after 98 ticks
1.21.4: [ECAP][Scenario] RESULT mixed_village: released 580 ticks ago, day time 13120 | work PASS after 63 ticks | trade PASS after 0 ticks | panic PASS after 43 ticks | calm PASS after 23 ticks | meet PASS after 143 ticks | rest PASS after 103 ticks
```

The first run failed `work` on both targets because the actors' Brains were still built for an
unemployed villager, so vanilla's job-site validation erased every job site they claimed. The
scene now rebuilds the Brain after setting the profession, as vanilla does on a profession
change.

## Developer run sheet for M1.1

1. Launch: `.\gradlew :1.21.1:runClient` (then repeat with `:1.21.4:runClient`).
2. Create a new creative superflat world with cheats on, difficulty Easy or higher. Stand in an
   open spot away from any village. The scene is built centred on you, 20x20, with its floor
   one block below your feet.
3. Run `/ecapscenario load mixed_village`. Check: a glass pen with a bell in the north-east, a
   composter, lectern, and brewing stand in the north-west, three red beds along the south, and
   three villagers standing still in the middle. Fly up or switch to spectator to watch;
   spectator may be used to observe only.
4. Run `/ecapscenario start`, then `/ecapscenario action work`. Within a minute each villager
   should walk to its own job block. `/ecapscenario result` should show `work PASS`.
5. Right-click the farmer (or run `/ecapscenario action trade`), wait a few seconds, then close
   the trade screen. `result` should show `trade PASS` within 20 seconds.
6. Run `/ecapscenario action panic`. A frozen pillager appears in the middle and the villagers
   should run from it; `panic PASS`. Run `/ecapscenario action calm`. The pillager vanishes and
   the villagers return to work; `calm PASS`.
7. Run `/ecapscenario action meet`. The villagers should gather near the bell; `meet PASS`.
8. Run `/ecapscenario action rest`. The villagers should go to bed and sleep; `rest PASS`.
9. Run `/ecapscenario result` and record the output. Use `/ecapscenario inspect` (or
   `inspect @e[type=villager,limit=1,sort=nearest]`) on any villager that looks wrong.
   Failure signs: a villager that stands still for the whole minute, walks to the wrong
   block, ignores the pillager, keeps trading after the screen closes, or never sleeps.
10. Optional lifecycle check: save and quit with the scene loaded, reopen, and confirm the
    villagers still move normally. The runner forgets the scene on quit, so remove it by hand
    afterwards (the pen and villagers stay in that throwaway world).
11. Clean up with `/ecapscenario reset`, which removes the villagers, the pillager, the
    scene's village, and everything above the floor. The stone floor stays.
