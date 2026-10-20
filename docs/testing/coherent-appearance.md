# Coherent appearance updates (0.3.1)

Appearance settings previously replaced each tile as its CPU bake completed.
Preserving coverage alone did not prevent a screen with multiple different looks.

`AppearanceRefresh` now stages a complete revision. Existing textures and coverage
remain visible until every required result is ready, then one render-thread commit
updates the resident set. Superseding settings discard the preceding staging set.
Terrain/light freshness and visibility generations are checked again at commit.

Resident overview tiles wait for a writer barrier: merge and write detailed tiles,
then rebuild their parent hierarchy. Loaded detail sources needed by a distant
overview are refreshed even if those sources have no GPU tiles. These disk-only
results are handed to the writer without retaining extra GPU textures. Evicting a
detail tile cannot remove an outstanding source required by a resident overview.

A foreground cache miss is treated as ready once staged. Repeated viewport requests
must not bake it again while the revision waits for other tiles. Staged foreground
tiles also remain participants in terrain invalidation before their first upload.

The normal terrain path retains its two-tile upload budget. An explicit appearance
revision uploads the bounded resident set together as a one-time exception; this
avoids keeping a second GPU cache. CPU staging is temporary and proportional to
resident image data. Very large resident sets can cause a longer individual commit;
the small local fixture is not an upper-bound performance guarantee.

## Regression coverage

`atomic-appearance` freezes the simulation, records several detailed tile identities
and upload counters, and loads a level-3 overview. It changes outlines, depth shading
and the chunk grid. While a revision is pending, no probe may receive an upload.
Completion must increment exactly one appearance revision and update all probes
together, retaining their identities and coverage. It repeatedly requests an absent
tile during the transaction and bounds submitted jobs to detect duplicate bakes.

JUnit tests exercise out-of-order staging, eviction, overview-dependent children,
staged cache misses and the writer barrier. The barrier test changes a child twice
and verifies that two overview levels contain the newest color before completion.

Additional client scenarios cover day/night transitions, settings preservation,
strict cave fog and reopening saved maps. The clock-only scenario now waits for
appearance staging as well as worker jobs to drain before measuring its zero-work
invariant.

## Reproduction

Local validation on 2026-10-20 passed 35 JUnit tests per target on Fabric
26.3/1.21.1 and NeoForge 1.21.1/26.3. The combined
`atomic-appearance,day-night,settings-refresh,fog-of-war,saved-map` client run
passed on Fabric 26.3. In its small fixture, the outline/depth/grid commits each
updated 22 resident tiles in 5.48–6.59 ms; a later 42-tile revision took 7.80 ms.
The time-only lighting sweep again produced zero jobs, writes, snapshots,
fog-mask rebuilds or exploration changes.

```sh
./gradlew --configure-on-demand :26.3:build :1.21.1-neoforge:build
DIORAMA_TESTS=atomic-appearance,day-night,settings-refresh,fog-of-war,saved-map ./gradlew --configure-on-demand :26.3:runClientGameTest
```

The `Committed appearance revision` log line records resident tile count and the
duration of the single commit. Screenshots and logs use the usual client-game-test
output directory, which is replaced on the next run.

## Saved-image limitation

This change preserves the existing file format and explored coverage. A historical
RGB tile cannot recover its original geometry, unshaded color, outline mask or torch
positions. Pixels with no available terrain snapshots retain their saved appearance
until revisited. This patch fixes partial publication of recalculable tiles; it
does not claim exact restyling of source data that was never persisted. New visual
layers such as the planned parchment fog must be composed separately from these
baked colors.
