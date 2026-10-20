# Day/night lighting validation

Target: Fabric 26.3, Diorama 0.3.0. Local validation on 2026-10-19 with JDK 25.0.1,
Windows, NVIDIA RTX 3070 and a 1600×900 game-test window.

## Behavior and regression gates

The `day-night` client scenario builds a stone platform at negative coordinates with a
torch at a diagonal chunk corner, a tall wall, a canopy, a bridge, water, glass and a
sea lantern. It captures noon, dusk, midnight and dawn, and tests both Fast and Fancy
quality in North, East, South and West projections.

The assertions check matching day/night coverage, neighboring torch illumination,
removal of that illumination, chunk-corner propagation, wall occlusion and populated
32×32 lighting patches. An accelerated clock sweep covers 0 through 24000 ticks in
1000-tick steps while simulation is frozen. After pending work drains, it requires:

- Zero additional tile jobs and tile-file writes.
- Zero additional chunk snapshots and fog-mask rebuilds.
- Identical exploration coverage and GPU allocations within 256 MiB.

The scenario also reloads resources and checks that Nether/End lighting does not
follow the Overworld clock. `settings-refresh` includes the lighting setting;
`saved-map` checks that a reopened tile still has its night texture when its chunks
are more than 1000 blocks from the player and unloaded.

JUnit covers paired and legacy persistence, corrupt/truncated bundles, projection
identity, partial upgrades, mixed overview availability, linear-color mips, read-cache
bytes, terrain/light revision ordering and duplicate light notifications. Legacy
`.omt` bytes are compared before and after an upgrade to verify rollback preservation.

## Local measurements

The baseline is integration commit `77729f8`, exported into a separate folder and
run with the same seed, platform and view. CPU samples include both quality modes
and all four directions; the frame samples use the default Fancy map. These are
small local samples, not an FPS guarantee or an end-to-end GPU latency benchmark.

| Measurement | Day-only baseline | Paired lighting |
| --- | ---: | ---: |
| Full tile, p50 | 165.81 ms | 181.96 ms |
| Full tile, p95 | 412.98 ms | 475.40 ms |
| Populated 32×32 patch, p50 / p95 | — | 2.27 / 2.62 ms |
| Minecraft frame timing, p50 | 0.78 ms | 0.83 ms |
| Minecraft frame timing, p95 | 1.02 ms | 1.15 ms |
| Resident map textures in fixture | — | 33,546,240 bytes (~32 MiB) |
| Chunk snapshot, p50 / p95 | — | 0.0164 / 0.0287 ms |
| Queued tiles after drain | — | 0 |
| Bytes written before clock sweep | — | 28,501,010 bytes |
| Time-only jobs / writes / snapshots / mask rebuilds | — | 0 / 0 / 0 / 0 |

The local acceptance target is p95 frame timing within 1 ms of baseline (2.02 ms),
full-tile p95 below twice baseline (825.96 ms), and a populated patch below 20 ms.
The measurements above meet those targets. CI records timings but enforces the
hardware-independent functional gates rather than applying RTX-specific timing
limits to software-rendered runners.

Each 512×512 RGBA plane with six mips allocates 1,397,760 bytes. A paired tile uses
2,795,520 bytes, plus 1 MiB if its overlay exists. Eviction counts all those resources,
keeping the previous 256 MiB memory target instead of doubling the old 192-tile
allocation. Upload credits are shared across catch-up ticks, permitting at most two
paired uploads between GUI frames. The CPU read cache also accounts for both planes.

## Reproduction

Local release validation passed all ten Fabric builds (1.21.1, 1.21.3, 1.21.4,
1.21.5, 1.21.6, 1.21.9, 1.21.11, 26.1, 26.2 and 26.3) and NeoForge 1.21.1/26.3,
with 30 JUnit tests per target and no failures. The combined
`day-night,directions,settings-refresh,modmenu,fog-of-war,saved-map` client run also
passed. The final added regression preserves unavailable legacy night data.

The builds above were repeated after integrating upstream `v0.2.1` (`d9f4a28`).
The `day-night,settings-refresh` client scenarios also passed again on the integrated
tree, including the zero-work clock sweep, resource reload and fixed-light dimensions.
The updated NeoForge 26.3 client-test shim and scenarios also compile. The remaining
nine NeoForge targets are declared in the 21-target CI matrix; they were not built
locally, so the matrix declaration must not be read as a passing result for them.

```sh
./gradlew --configure-on-demand :26.3:build :26.3:gametestClasses
DIORAMA_TESTS=day-night ./gradlew --configure-on-demand :26.3:runClientGameTest
DIORAMA_TESTS=day-night,directions,settings-refresh,modmenu,fog-of-war,saved-map ./gradlew --configure-on-demand -PdioramaModMenuTest=true :26.3:runClientGameTest
```

Screenshots and logs are emitted under `versions/26.3/build/run/clientGameTest/`.
The `LIGHTING METRICS` and `LIGHTING TIME-ONLY PASS` lines contain the measured
values and counter assertions. Running another client test replaces that folder.

## Compatibility and deliberate limits

Only Fabric 26.3 exposes and renders the dynamic surface lighting in this release.
Older Fabric and NeoForge GUI backends retain their daytime draw path. They do not
allocate a second surface plane when baking. This is an explicit temporary platform
limit, not a silently ignored lighting selector.

Caves, roofed dimensions and fixed-time dimensions retain their existing lighting.
The End is explicitly treated as fixed-light, even though 26.3 exposes sky light there;
non-fixed sky-lit dimensions use their own environment timeline rather than a hardcoded
Overworld clock. Decorative shadow direction remains fixed. Block light uses a warm
approximation; colored-light mod integration and orbiting solar shadows are deferred.

An old saved image cannot recover torch locations. Missing night pixels use a dimmed
day-color approximation; revisiting their chunks upgrades them incrementally.
OMT2 schema 1 stores tile/projection identity and a checksummed compressed payload.
Both endpoints are written in one atomic bundle, alongside untouched legacy files.
Night alpha measures availability while day alpha remains authoritative for coverage.
