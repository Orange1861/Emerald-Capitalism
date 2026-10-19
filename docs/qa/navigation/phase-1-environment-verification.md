# Phase 1 navigation environment verification

This run sheet is the manual half of Phase 1. Automated GameTests run first in
both supported targets; the ordinary-world checks below are then performed in a
fresh disposable world for each target. These checks validate the player-facing
client and vanilla-style villager setup. They are not a replacement for the
isolated GameTest harness, and a GameTest failure must not be hidden by a manual
success.

## Exact launch commands

Run these from the repository root, one target at a time:

```powershell
.\gradlew :1.21.1:runClient --no-daemon --no-configuration-cache --console=plain
```

```powershell
.\gradlew :1.21.4:runClient --no-daemon --no-configuration-cache --console=plain
```

Record the exact Git commit, target, Java version, and command before each run.
Do not reuse the world between targets or between verification attempts.

## Disposable-world setup

1. Create a new world through the client UI. Select **Creative**, enable
   **Allow Cheats**, and select a **Superflat** world. Use a new name that has
   not been used for another verification run.
2. In the world, run:

   ```text
   /gamemode creative
   /gamerule doDaylightCycle false
   /time set day
   /weather clear
   /difficulty normal
   /gamerule doMobGriefing false
   ```

3. Make a bounded, navigable test area around the player. Keep the inside
   roughly 9x9 blocks, at least two blocks high, with a clear opening or gate so
   the villager has a real route rather than a sealed box. Avoid slopes, caves,
   water, hostile mobs, redstone, and unrelated mod blocks. The purpose is to
   make the route and workstation visible and repeatable, not to emulate a
   generated village.
4. Give yourself and place one bed, one profession-appropriate workstation,
   and ordinary building blocks. Useful vanilla workstation choices are:
   composter (farmer), fletching table (fletcher), smithing table (toolsmith),
   blast furnace (armorer), lectern (librarian), cartography table
   (cartographer), brewing stand (cleric), stonecutter (mason), cauldron
   (leatherworker), and barrel (fisherman).
5. Spawn one ordinary villager inside the bounded area:

   ```text
   /summon minecraft:villager ~ ~ ~
   ```

   Place the bed and workstation within the bounded area after spawning. Do not
   use a profession-specific custom entity, a structure template, or a
   pre-existing world village for this baseline.
6. Wait through the normal profession-acquisition window. Watch both the
   villager's profession and its movement toward and around the workstation.
   Give the run at least 60 seconds after the workstation is placed; allow up
   to 120 seconds before classifying a no-movement result as a failure. Keep the
   area loaded and remain close enough to observe it.

## Success evidence

For each target, a successful ordinary-world check has all of the following:

- the client launched with the exact command above and reached the world;
- the world is a new Superflat Creative world with cheats enabled;
- the villager remains an ordinary vanilla villager, acquires the intended
  profession, and keeps it while the workstation remains available;
- the villager makes visible, collision-free progress through the bounded area
  toward the workstation and does not become permanently stuck at the boundary;
- the client remains stable with no crash report or relevant error in the client
  log.

Save a screenshot or short recording showing the world setup, workstation,
villager profession, and movement. Also preserve the client log location and
the target's exact Git commit. If the run is repeated, record the new world
name and timestamp rather than replacing the earlier evidence.

## Failure evidence and classification

For a failure, record the target version, exact commit, Java version, command,
world name, world type, cheats setting, workstation, elapsed wait, and a short
description of the observed state. Preserve the screenshot or recording, the
client log, and any crash report. A failed profession claim, no movement after
the full wait, collision/stuck behavior, or a client crash is a failure even if
the automated GameTests pass.

Use these distinctions when reporting it:

- **Harness/fixture:** the setup did not establish the stated vanilla baseline
  or the automated structure/test fixture was malformed. Correct the fixture or
  record the harness failure; do not change a required test to make it pass.
- **Cross-version/build configuration:** the target did not launch with the
  intended mappings, Java toolchain, resources, or client configuration.
- **Mixin audit configuration:** the audit did not run a fresh report, could not
  confirm the selector, or could not prove the required mixins were applied.
- **Genuine production behavior:** the stated setup was valid and the villager
  or client behavior was wrong. Assign it to the navigation-refactor phase that
  owns that behavior; Phase 1 does not repair later-phase gameplay.

The manual run is an ordinary-world smoke check, not a substitute for the
automated harness. Complete the automated 1.21.1 and 1.21.4 GameTests first,
preserve their required pass/fail counts and logs, then perform this manual
check in a fresh world for each target.

## Phase 1 evidence record

The CI report and local `build/reports/phase1/phase-1-evidence.json` are the
authoritative machine-readable records. They must include the exact commit and
Java identity, static discovered count, runtime launched/executed count,
required passed/failed tests, optional failures, final Gradle outcome, and log
or report path for both targets. A report from another commit, an incomplete
audit, or a stale log is not evidence.

## Current automated verification record

Run date: 2026-10-19. Exact commit:
`f409d615f592301f64415e1b5e65fadfd49e73b4`. Both target toolchains reported
Java `openjdk version "21.0.9" 2025-10-21 LTS`; the target logs identified
NeoForge 21.1.219 for 1.21.1 and 21.4.157 for 1.21.4. Static source
discovery found 265 GameTests, while each server launched and executed 263.
Each run had 254 required passes, 9 required failures, and 0 optional failures.
The final Gradle task outcome was failure for both GameTest tasks. Evidence logs
are [1.21.1](../../../build/reports/phase1/gametest-1.21.1.log) and
[1.21.4](../../../build/reports/phase1/gametest-1.21.4.log).

The direct GameTest failure names were:

- **1.21.1:** `normalvillageraiharvestspumpkinatabsoluteposition`,
  `contestedgovernormaykillbankemployeewithoutvillageopinionpenalty`,
  `malformedbankstatefailsloudly`,
  `normalvillageraidescendsladderandcompleteslowerwork`,
  `normalvillageraiascendsladderandcompletesupperwork`,
  `normalvillagerunloadreloadreleasesladderclaim`,
  `twonormalvillagerscontendforoneladderandoneworktarget`,
  `normalvillagerblockedtopexitcancelsandreplanswithoutcollision`,
  `boatescapepreemptsandthenresumesarealpumpkinworkjourney`.
- **1.21.4:** `normalvillageraiharvestspumpkinatabsoluteposition`,
  `contestedgovernormaykillbankemployeewithoutvillageopinionpenalty`,
  `malformedbankstatefailsloudly`,
  `normalfarmerharvestsandreplantsmaturewheat`,
  `normalvillageraidescendsladderandcompleteslowerwork`,
  `normalvillageraiascendsladderandcompletesupperwork`,
  `normalvillagerunloadreloadreleasesladderclaim`,
  `twonormalvillagerscontendforoneladderandoneworktarget`,
  `normalvillagerblockedtopexitcancelsandreplanswithoutcollision`.

Fresh mixin reports were generated for both targets at the same commit. Each
report has schema 2, 18 configured mixins (17 server mixins), 265 discovered
tests, a confirmed audit selector, and complete application evidence: all 17
required server mixins were observed with `applied=true`, with no missing or
unapplied required mixin. The baseline still classified as
`BASELINE_FAILED`, so no subtractive unit was allowed to claim a removal
result. The fresh audit report paths are
[1.21.1](../../../versions/1.21.1/build/reports/mixin-audit/report.json) and
[1.21.4](../../../versions/1.21.4/build/reports/mixin-audit/report.json).
The independent audit baseline had 10 required failures in each target,
demonstrating run-to-run fixture/behavior variability rather than stale output:

- **1.21.1 audit baseline:** the direct failures above except the boat journey,
  plus `staleownercannotstoparealbrainjourneyafterprioritypreemption` and
  `ladderdestructionreleasesloadedentityclaim`.
- **1.21.4 audit baseline:** the 9 direct failures above, plus
  `ladderdestructionreleasesloadedentityclaim`; the direct run's failure set
  did not include that ladder-destruction test.

Failure ownership is intentionally left for later phases: pumpkin and mature
wheat behavior are **Phase 15 farmer/crop work**; ladder ascent/descent,
destruction, unload cleanup, contention, and blocked exits are **Phase 14
route capabilities**; the stale movement-owner and boat-preemption failures
are **Phase 12 movement intent**; the contested governor failure is **Phase 18
mayor following/civic work**; and malformed bank state is **Phase 19 banker and
inventory-bank work**. These are genuine later-phase behavior/regression gates
or their current nondeterministic fixtures, not Phase 1 infrastructure fixes.

The setup gate, regression manifest, unit tests, both compilations, complete
build, and `git diff --check` passed. The two GameTest tasks and two mixin audit
tasks failed as recorded above. Therefore the Phase 1 gate is **NOT COMPLETE**;
the report does not claim completion, and no later-phase gameplay behavior was
changed to make the gate pass.

Phase 1 is complete only when the required setup, unit/regression tests,
1.21.1 and 1.21.4 compilation, both GameTest servers, both mixin audits, the
complete build, and this manual smoke check have passed. Later-phase gameplay
failures remain explicitly recorded instead of being reclassified as a Phase 1
pass.
