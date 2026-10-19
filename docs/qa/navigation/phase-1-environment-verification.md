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

Phase 1 is complete only when the required setup, unit/regression tests,
1.21.1 and 1.21.4 compilation, both GameTest servers, both mixin audits, the
complete build, and this manual smoke check have passed. Later-phase gameplay
failures remain explicitly recorded instead of being reclassified as a Phase 1
pass.
