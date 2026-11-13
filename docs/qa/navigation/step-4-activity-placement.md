# Step 4 ledger: activity placement

Step 4 of the foundation-first order in
[`navigation-refactor-implementation.md`](../../plans/navigation-refactor-implementation.md)
delivers Phase 13: explicit Brain activities and priorities for mod behavior, the rule for how a mod
job holds the WORK slot against vanilla movers, and frozen placements for begging and governor
following. Its tests and scene run on the banker. Agent review recorded here is a self-review.

## What changed

- The frozen rules are written into the plan under "Activity placement rules (frozen by step 4)":
  a mod behavior ends itself when its activity ends; a mod job holds WORK by replacing vanilla's
  movers; jobs resume by reacquiring; hours; begging and governor following in IDLE, MEET, and
  WORK; the inventory-bank trip as a lumberjack competitor; safety wins structurally.
- Defect found and fixed: **the Brain does not stop a running behavior when the active activity
  changes.** `BankerWorkSideBehavior` (Step 3) would have kept running and republishing a walk
  target during panic, raid, and rest. It now checks `isActive(Activity.WORK)` in `canStillUse`.
- Developer decision (2026-11-13): the banker works through the bell hours, normal otherwise.
  `BankerSchedule` is vanilla's villager schedule with WORK in place of MEET (9000 to 11000).
  It is applied by a 20-tick reconcile in `VillagerSpawnEvents.onVillagerTick` and at profession
  assignment in `BankAwareAssignProfessionFromJobSite`, because vanilla resets the schedule when a
  Brain is rebuilt. No new mixin. This also covers trapped natural-bank bankers, who may have no
  meeting point and so would be IDLE, not MEET, at bell time.
- Manifest and verifier: the begging and governor-following frozen decisions change from WORK and
  IDLE to "IDLE, MEET and WORK"; `banker-work-activity` and `mod-behavior-activity-bound` are new
  frozen decisions; `stonecutter.gradle` requires all four. Baseline: 304 discovered, 302 executed,
  new hash.

Not done in Step 4, and why:

- **Begging and governor following are frozen but not moved.** Step 8 adds begging to IDLE and MEET
  (it is in WORK only today); step 12 moves governor following. The 2026-11-10 amendment gives
  those placements to Step 4 as decisions, not code.
- No generic helper for the rule. It is applied by the banker only; the lumberjack (step 6) is the
  second user, and a helper before then would be built without a consumer.
- The "inspect shows exactly one winning activity" run-sheet output is not built; the run sheet
  uses vanilla `/data`. The scenario runner gets a banker scene in step 7.
- The dev scene `activity_priority_pilot` and the raid action are not built as runner scenes. The
  raid case is a component test that forces the RAID activity the way vanilla's `SetRaidStatus`
  does; no real raid is started.

## Requirement ledger

| ID | Requirement (Phase 13) | Implementation | Automated evidence | Negative case | Status |
| --- | --- | --- | --- | --- | --- |
| P13.1 | Assign safety, route, work, civic, social, and idle features to explicit activities and priorities. | Plan rules 1 to 8; frozen decisions in the manifest and verifier; banker in WORK at priority 1. | `verifyNavigationArchitecture` requires the four frozen decisions. | A frozen decision that differs from the verifier fails the build. | PASS for the banker; begging and governor following frozen, not moved |
| P13.2 | Preserve vanilla panic, raid, rest, meet, work, and core behavior. | Only the banker's WORK package changes; `BankerSchedule` changes only the banker's bell hours. | Package-contents tests keep every other profession's package unchanged; mixed-village scene passes on both targets. | Other professions never receive the banker behavior. | PASS (automated) |
| P13.3 | Define which mod work resumes and which reacquires. | Plan rule 3: every job reacquires; the banker re-resolves its bank on each start. | Resume steps in the panic, trade, and rest tests. | Behavior keeps no state across a stop. | PASS (automated) |
| P13.4 | Priority tests: work versus panic, raid, sleep, trade, meeting, profession change. | `BankerActivityPriorityGameTests`. | Panic at the travel checkpoint and at the work side, trade, rest hours, raid (component), profession change (component), bell hours. | See below. | PASS (automated); raid forced, not a real raid |
| P13.5 | One clean stop and no simultaneous conflicting movement intent. | `canStillUse` activity check; `release` erases only the banker's own target. | Each interruption test checks, for 40 ticks after the activity changes, that the behavior is not running and no walk target is banker-owned. | Before the activity check, panic and rest left the behavior running (found by these tests). | PASS (automated) |
| P13.6 | Rule for how a mod job holds WORK without alternating the walk target. | Plan rule 2; Step 3's package change; priority 1 and start only while `WALK_TARGET` is absent. | Step 3's 400-tick hold tests, and the bell-hours hold here. | Step 3 recorded the failure with vanilla's gate left in. | PASS (automated) |
| P13.7 | Freeze begging and governor-following placement. | Plan rules 5 and 6; manifest and verifier. | Verifier. | Not applicable. | PASS (decision); implementation in steps 8 and 12 |
| P13.8 | Name the inventory-bank trip as a lumberjack competitor. | Plan rule 7. | None; a plan decision. | Not applicable. | PASS (documented) |
| M4.1 | Developer observes interruptions and bell-hour work in game. | Run sheet below. | None; only the developer can pass this. | Not applicable. | BLOCKED (waiting for the developer) |

### Negative case

Before the activity check, `BankerWorkSideBehavior` kept running after the Brain left the WORK
activity. This was caught by the new interruption checks and fixed; the plan records it as rule 1.

## Recorded results (HEAD 4e319b1c plus Step 4, uncommitted)

Compile, `verifyNavigationArchitecture`, and `verifyRegressionCoverage` pass (304 GameTests discovered).
GameTests ran 302 on each target. On 1.21.1 the failures are exactly the five baseline ones. On 1.21.4
they are the baseline six (the five plus the known flaky `normalfarmerharvestsandreplantsmaturewheat`)
and the Step 3 candidate test, an expected failure there. All eight new tests pass on both targets.
Mixed-village runner lines:

```text
1.21.1: work PASS after 118 ticks | trade PASS after 0 | panic PASS after 38 | calm PASS after 38 | meet PASS after 158 | rest PASS after 98
1.21.4: work PASS after 131 ticks | trade PASS after 0 | panic PASS after 51 | calm PASS after 31 | meet PASS after 91 | rest PASS after 91
```

Flaky tests seen while building this step, not caused by it: on 1.21.4 `generatedvillagerinitialemeraldsgostraighttobank`
failed in two runs and passed in the next. It sets the shared level day time itself, so it races with other
unbatched tests that also set it. Several new tests here set the day time inside their own batch and restore
it. The raid and profession-change tests needed fixture fixes (a grounded mob, a start position inside
vanilla's 9-block job-site range); the bell-hours test now waits 40 ticks at the work side before judging.
Panic, rest, and the other interruption tests check two ticks after the activity changes because the Brain
changes activity after its own tick.

## Developer run sheet for M4.1

Use a village with a banker whose bank does not confine it (an artificial bank), plus one natural
bank if you want to confirm the bell hours there. For the commands, stand near the banker.

1. `.\gradlew :1.21.1:runClient`, then repeat with `:1.21.4:runClient`.
2. Bell hours. `/time set 8500`: the banker is at the back of the bank. `/time set 9500` (vanilla
   villagers are now at the bell): the banker stays and works at the back of the bank. Failure signs:
   it walks toward the bell, wanders, or stands at the front. `/time set 11500`: the banker may
   idle and wander like an ordinary villager. `/time set 13000`: it goes to bed.
3. Panic while walking. `/time set 8000`, then stand the banker away from the bank (push it or lead
   it with a lead and release) so it is walking back. Spawn a pillager next to it with
   `/summon minecraft:pillager ~3 ~ ~ {NoAI:1b}`. The banker should run from it. Failure signs: it
   keeps walking to the bank, or it alternates between running away and the bank.
4. Panic at the bank. Repeat with the banker at the back of the bank. It should run and, after
   `/kill @e[type=pillager]`, return to the back of the bank within about 30 seconds.
5. Trading. Right-click the banker; it should turn to you and stop working. Close the screen and it
   returns to the back of the bank.
6. Profession loss. Break the bank (or `/data modify entity <banker> VillagerData.profession set
   value "minecraft:none"` in a throwaway world). The villager should stop going to the bank at
   once and behave like an unemployed villager.
7. Raid (optional): `/raid` is not a vanilla command; use `/effect give @s minecraft:bad_omen 1 5` and
   enter the village to start one. The banker should hide or flee like other villagers and not
   walk back to the bank until the raid ends.

Record anything that looks wrong, with the version.
