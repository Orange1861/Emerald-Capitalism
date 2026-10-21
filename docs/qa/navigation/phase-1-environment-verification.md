# Phase 1 navigation environment verification

This run sheet is the manual half of Phase 1. Automated infrastructure and
evidence checks run first in both supported targets; the ordinary-world checks
below are then performed in a fresh disposable world for each target. These
checks validate the player-facing client and vanilla-style villager setup.
They are not a replacement for the isolated GameTest harness, and a GameTest
failure must not be hidden by a manual success.

## Phase 1 gates

The Phase 1 infrastructure gate is separate from the final regression gate.
Infrastructure passes when Java/Gradle setup, `verifyPortSetup`, unit tests,
architecture/regression discovery, both compilations, both launched GameTest
servers, and both fresh mixin audits produce the required evidence. The
GameTest and audit results may contain only the exact assigned gameplay
failures in [`gradle/navigation-regression-baseline.json`](../../../gradle/navigation-regression-baseline.json).
Any new, removed, renamed, unexpectedly passing, or unexpectedly failing
identity requires review and fails the infrastructure gate. Launch failure,
zero executed tests, stale evidence, missing selector confirmation, missing
required-mixin application evidence, or an unassigned failure also fails it.

The final regression gate is the release gate in Phase 24 of the navigation
plan. It requires every required GameTest and mixin audit to pass before the
navigation refactor is declared complete. Phase 1 does not delete, disable,
weaken, reclassify, or make optional any existing gameplay failure to satisfy
its infrastructure gate.

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

## Required developer confirmation

Run the exact `runClient` commands above once for 1.21.1 and once for 1.21.4,
using a new ordinary world for each run. The check passes only when the client
reaches the world without a crash or relevant mixin error, the ordinary
villager acquires the workstation profession, and it visibly walks
collision-free toward and around the workstation within 120 seconds. It fails
on a crash, mixin error, profession failure, no movement after the full wait,
permanent obstruction, or target-specific setup difference. Preserve the world
names, timestamps, target commits, client logs, and screenshots/recordings, or
explicitly report why an artifact could not be preserved.

The explicit confirmation must identify both targets and say either:
`Phase 1 manual smoke check PASS for 1.21.1 and 1.21.4`, with the evidence
locations, or `Phase 1 manual smoke check FAIL`, with the target-specific
failure classification and evidence locations. Until that confirmation is
provided, this item remains `BLOCKED`; generated files, automated tests,
screenshots not received from the developer, or Luna's source review cannot
mark it `PASS`.

## Phase 1 evidence record

The CI report and local `build/reports/phase1/phase-1-evidence.json` are the
authoritative machine-readable records. They include the exact commit and
Java identity, static discovered count, runtime launched/executed count,
required passed/failed tests, optional failures, final Gradle outcome, audit
classification, selector/application evidence, assigned owning phase, and log
or report path for both targets. The tracked
`gradle/navigation-regression-baseline.json` is compared as an exact set; a
report from another commit, an incomplete audit, stale evidence, or a baseline
set difference is not evidence of a passing infrastructure gate.

## Current automated verification record

Run date: 2026-10-21. Exact commit:
`ddb529c37b0192964ea0b85cb7b6a0535ea955f2`. Both target toolchains reported
Java `openjdk version "21.0.9" 2025-10-21 LTS`; the target logs identified
NeoForge 21.1.219 for 1.21.1 and 21.4.157 for 1.21.4. Static source
discovery found 265 GameTests, while each server launched and executed 263.
The 1.21.1 run had 254 required passes and 9 required failures; the 1.21.4
run had 252 required passes and 11 required failures. Both had 0 optional
failures. The final Gradle task outcome was failure for both GameTest tasks.
Evidence logs are [1.21.1](../../../build/reports/phase1/gametest-1.21.1.log)
and [1.21.4](../../../build/reports/phase1/gametest-1.21.4.log).

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
  `normalvillagerblockedtopexitcancelsandreplanswithoutcollision`,
  `ladderdestructionreleasesloadedentityclaim`,
  `staleownercannotstoparealbrainjourneyafterprioritypreemption`.

Fresh mixin reports were generated for both targets at the same commit. Each
report has schema 2, 18 configured mixins (17 server mixins), 265 discovered
tests, a confirmed audit selector, and complete application evidence: all 17
required server mixins were observed with `applied=true`, with no missing or
unapplied required mixin. The baseline still classified as
`BASELINE_FAILED`, so no subtractive unit was allowed to claim a removal
result. The fresh audit report paths are
[1.21.1](../../../versions/1.21.1/build/reports/mixin-audit/report.json) and
[1.21.4](../../../versions/1.21.4/build/reports/mixin-audit/report.json).
The separate mixin-audit baseline had 10 required failures in each target.
It is a second evidence suite, not independent implementation proof, and its
exact identities are tracked separately from the runtime GameTest baseline:

- **1.21.1 audit baseline:** `boatescapepreemptsandthenresumesarealpumpkinworkjourney`,
  `contestedgovernormaykillbankemployeewithoutvillageopinionpenalty`,
  `ladderdestructionreleasesloadedentityclaim`, `malformedbankstatefailsloudly`,
  `normalvillageraiascendsladderandcompletesupperwork`,
  `normalvillageraidescendsladderandcompleteslowerwork`,
  `normalvillageraiharvestspumpkinatabsoluteposition`,
  `normalvillagerblockedtopexitcancelsandreplanswithoutcollision`,
  `normalvillagerunloadreloadreleasesladderclaim`, and
  `twonormalvillagerscontendforoneladderandoneworktarget`.
- **1.21.4 audit baseline:** `contestedgovernormaykillbankemployeewithoutvillageopinionpenalty`,
  `ladderdestructionreleasesloadedentityclaim`, `malformedbankstatefailsloudly`,
  `normalfarmerharvestsandreplantsmaturewheat`,
  `normalvillageraiascendsladderandcompletesupperwork`,
  `normalvillageraidescendsladderandcompleteslowerwork`,
  `normalvillageraiharvestspumpkinatabsoluteposition`,
  `normalvillagerblockedtopexitcancelsandreplanswithoutcollision`,
  `normalvillagerunloadreloadreleasesladderclaim`, and
  `twonormalvillagerscontendforoneladderandoneworktarget`.

Failure ownership is intentionally left for later phases: pumpkin and mature
wheat behavior are **Phase 15 farmer/crop work**; ladder ascent/descent,
destruction, unload cleanup, contention, and blocked exits are **Phase 14
route capabilities**; the stale movement-owner and boat-preemption failures
are **Phase 12 movement intent**; the contested governor failure is **Phase 18
mayor following/civic work**; and malformed bank state is **Phase 19 banker and
inventory-bank work**. These are genuine later-phase behavior/regression gates
or their current nondeterministic fixtures, not Phase 1 infrastructure fixes.

The setup gate, regression manifest, unit tests, both compilations, complete
build, and `git diff --check` passed. Both GameTest servers launched and
executed the expected 263 tests, and both audit reports were fresh with valid
selector and required-mixin application evidence. Their nonzero task outcomes
are caused by the exact assigned gameplay failures above, so the **Phase 1
infrastructure gate is PASS** for this recorded baseline.

The **developer manual-verification status is BLOCKED** pending the explicit
two-target confirmation required above. The **full regression gate is FAIL / not
complete** until every required GameTest and mixin audit passes; that final
all-tests-pass gate is owned by Phase 24. No later-phase gameplay behavior was
changed to make Phase 1 pass.

### Status summary

| Item | Status | Evidence / reason |
| --- | --- | --- |
| Infrastructure gate | **PASS** for the recorded baseline | Configuration, verification tasks, compilation, launched/executed servers, fresh audits, logs, and exact baseline comparison are valid. |
| Developer manual verification | **BLOCKED** | The developer has not supplied the required ordinary-world observations and explicit confirmation for both targets. |
| Full regression gate | **FAIL / not complete** | Required gameplay failures remain and must be fixed by their owning phases before Phase 24 can pass. |

### Failure ownership index

| Exact test identity | Target(s) | Owning phase |
| --- | --- | --- |
| `normalvillageraiharvestspumpkinatabsoluteposition` | 1.21.1, 1.21.4 | Phase 15 — farmer crop/pumpkin work |
| `normalfarmerharvestsandreplantsmaturewheat` | 1.21.4 | Phase 15 — farmer crop/pumpkin work |
| `normalvillageraidescendsladderandcompleteslowerwork` | 1.21.1, 1.21.4 | Phase 14 — route capabilities/ladders |
| `normalvillageraiascendsladderandcompletesupperwork` | 1.21.1, 1.21.4 | Phase 14 — route capabilities/ladders |
| `normalvillagerunloadreloadreleasesladderclaim` | 1.21.1, 1.21.4 | Phase 14 — route capabilities/ladders |
| `twonormalvillagerscontendforoneladderandoneworktarget` | 1.21.1, 1.21.4 | Phase 14 — route capabilities/ladders |
| `normalvillagerblockedtopexitcancelsandreplanswithoutcollision` | 1.21.1, 1.21.4 | Phase 14 — route capabilities/ladders |
| `ladderdestructionreleasesloadedentityclaim` | 1.21.1, 1.21.4 | Phase 14 — route capabilities/ladders |
| `boatescapepreemptsandthenresumesarealpumpkinworkjourney` | 1.21.1 | Phase 12 — movement-intent publication |
| `staleownercannotstoparealbrainjourneyafterprioritypreemption` | 1.21.1 | Phase 12 — movement-intent publication |
| `contestedgovernormaykillbankemployeewithoutvillageopinionpenalty` | 1.21.1, 1.21.4 | Phase 18 — mayor following/civic work |
| `malformedbankstatefailsloudly` | 1.21.1, 1.21.4 | Phase 19 — banker/inventory-bank work |

The runtime GameTest and mixin-audit rows are separate evidence records. The
audit suite is not counted as an independent implementation proof when it
derives from the same gameplay failure; both remain visible because both are
required gates.
