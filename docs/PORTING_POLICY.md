# Porting policy

This project uses Stonecutter to build one shared implementation for multiple
NeoForge targets. Shared gameplay, tests, resources, registrations, packet
contracts, persistence schemas, AI rules, and client presentation live under
`src/`. The `versions/<target>/` directory is target metadata plus an
explicitly allowlisted override only when a real API or resource format is
different. It must never contain a copied source tree.

The allowlist is `gradle/porting-overrides.json`. A new target override must
include its exact path there and explain the API or resource difference in the
change description. If a file is usable by more than one target, it belongs in
the shared tree. Do not add a generic compatibility layer to avoid deciding
where a version difference belongs.

## Target setup

Run `verifyPortSetup` before adapting source. It validates the shared-source
layout and coverage manifests, resolves the 1.21.4 Parchment artifact, and
selects the Java 25 toolchain for 26.1. The current legacy mapping coordinate
is:

```text
org.parchmentmc.data:parchment-1.21.4:2025.03.23@zip
```

This coordinate was confirmed against the official Parchment Maven repository
on 2026-08-31. The target properties remain the source of truth for Minecraft,
NeoForge, mappings, and Java versions.

## Mixin porting units

`gradle/mixin-audit.json` is the authoritative inventory for every loaded
common and client mixin configuration. Each unit records its gameplay
invariant, focused GameTests, target method, injection point, supported-event
alternative assessment, and—where server automation cannot exercise a client
hook—the linked manual smoke check.

Run `:1.21.1:auditMixins` on the active target before porting a mixin. The
server-side units run against a passing all-enabled baseline and then in fresh
GameTest JVMs with one unit disabled. A passing disabled run is only a removal
candidate: verify the target's supported event/API and retain the mapped
invariant test before deleting a hook. Client units are explicitly marked for
the client smoke matrix because a dedicated GameTest server cannot prove a
client render-layer injection.

## Regression boundaries

`gradle/regression-coverage.json` keeps the required checks visible and
machine-validated. The coverage must include:

- bank, village, block-entity, and entity persistence, including malformed and
  boundary data plus dirty notifications;
- packet codecs and menu open data, with dedicated-server authority checks for
  sender, menu, dimension, range, permissions, and current state;
- villager AI, trades, breeding, hunger, inventory, reputation, navigation, and
  conversion behavior; and
- client smoke checks for every custom renderer, renderer layer, and screen.

`gradle/client-smoke.json` is the renderer/screen checklist. For each entry,
use a disposable client world and capture before/after screenshots at normal,
small, and large GUI scales where applicable. Exercise every real open path,
control, tab, text field, state transition, and close/reopen cycle. A client
smoke pass does not replace a dedicated-server run.

The version-sensitive ownership points remain registrations, packet
registration/handlers, resource loaders, persistence adapters, and client
renderers/screens. Keep differences at those seams and pass stable plain data
through the existing core and presentation helpers.
