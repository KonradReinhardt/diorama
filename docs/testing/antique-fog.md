# Antique map validation — 0.4.0

## GPU: dense clouds

The `DIORAMA_TESTS=fog-gpu-bench` scenario, run on its own on Minecraft 26.3, Fabric, JDK 25 and
OpenGL, on an RTX 3070 with NVIDIA driver 616.56. The test uses the real production fragment shader on
a 512×512 framebuffer, with a fully unknown mask and the mod's own procedural noise texture. It
compares the interpolated noise with the same version of the shader on the original path. Each
measurement warms up 20 draws and times 80, with GPU timer queries; below is the median of three pairs.

| Fancy pass of dense clouds | Time per draw |
| --- | ---: |
| Reference, noise per layer | 0.0981376 ms |
| Interpolating the ends | 0.0590592 ms |
| Time saved in that pass | 39.82% |

A second run, after the final shader adjustments, measured 0.1334784 ms for the reference and
0.0899712 ms optimised: 32.59% less. GPU clocks and load vary; both runs point to 33–40% less time in
this specific pass. The image comparison measured an average RGB error of 0.2136 out of 255 and a
maximum of 6. The frontier with the terrain uses the original path. This is not an FPS measurement,
nor a promise of that gain in the whole game, on other GPUs or at another zoom.

The benchmark's first prototype didn't isolate Minecraft's pixel transfer state and crashed in the
driver during `glReadPixels`. It was fixed to save, normalise and restore the pack/unpack buffers and
parameters and to use native memory of explicit size. The fixed isolated benchmark completed
successfully. That code lives only in the test source set and isn't included in the mod's JAR.

## Cartography and real rendering

The first `antique-fog` completed on the real client: paper and sepia on explored terrain, pixel
cartography at 6000/6000 outside the exploration, the Clouds style preserved, and interpolated zoom
with the point under the cursor fixed in every frame. Changing only the style didn't add terrain bake
jobs.

Two real runs recorded 7709/7656 biome queries, spending 62.52/71.11 ms of sampling in total, with a
worst query of 0.356/0.293 ms. These costs are spread over the worker, not concentrated in one frame.
Measured initialisation/capture: 68.2 ms at first and 215.7 ms after adding the registries' full
fingerprint and the copy of the parameter tree. Initialisation isn't part of the continuous sampling
budget.

The preview didn't mark 6000/64/6000 as explored. Chunk queries, `getBaseHeight`, structures or block
generation don't exist in the adapter. A faithful reproduction of coasts and height isn't promised:
the data are biomes at Y=64.

## Automated tests and compatibility

The unit suite includes negative coverage and the cache's CRC, persisted reads without repeating
samples, refinement reusing the coarse samples, an unavailable seed, isolation between worlds, fog
transition/reprojection and zoom with a duration independent of FPS. The existing persistence,
scheduler, lighting and appearance update tests remain in the suite.

Final local result: **43 tests per target, zero failures/errors**, with builds of Fabric 26.3,
Fabric 1.21.1, NeoForge 26.3 and NeoForge 1.21.1.

The Fabric 26.3 client completed the combined run in 10 min 21 s:

```text
DIORAMA_TESTS=antique-fog,day-night,settings-refresh,fog-of-war,saved-map
```

It passed the prediction in an unknown region, real zoom, lighting in all four directions and both
qualities, resource reloads, fog rules in caves, settings preserving coverage and reopening the world
with its saved map/lighting, including terrain beyond the loading distance. The time-only change kept
zero new jobs, writes, snapshots, masks or coverage. That run's capture measured 6894 samples,
71.69 ms in total, a worst query of 0.356 ms and initialisation of 172.5 ms.

Fabric 1.21.1 also completed `antique-fog,settings` in game, including rendering through
`ImmediateCanvas`, parchment without a seed, smooth zoom and layout. Both NeoForge builds were
compiled and unit tested, without a new NeoForge client in that round.

After the integrated suite, the adjustment to keep the discovery clock during refinements got a new
unit test; the suite grew from 42 to 43. The final shader also got an isolated GPU run with a visual
fixture of the seven symbols, including the mountains' orientation and the cubic grid. The long
integrated suite wasn't repeated after these targeted adjustments.

Final Fabric 26.3 JAR: `diorama-0.4.0+26.3.jar`, 490287 bytes.
SHA-256: `7acaed49524ec3e77636bffc248b1e4299ac6bedf912dc31a9b45ee682149a91`.

## Limits of the measurement

The limits on caches/arrays and textures are explicit. No measurement of the retained memory of the
whole vanilla sampler was made to certify the 32 MiB target. No long-trajectory benchmark or p95
measurement of the whole frame was made. Local validation of four targets doesn't amount to running
the whole matrix of 21.

The original parchment texture is in
`src/main/resources/assets/diorama/textures/map/parchment.png`, 256×256 RGB, created with imagegen and
prepared with edge sampling without transparency.

## Visual polish, loading and appearance refresh — 2026-10-20

Run on Minecraft 26.3, Fabric, JDK 25, the same machine as the measurements above.

- Builds with JUnit: Fabric 26.3, NeoForge 26.3, Fabric 1.21.1 and NeoForge 1.21.1. `FogRevealTest`
  gained a case with restarts every 16 ms (tiles arriving every frame): the test requires more than
  74% revealed in 240 ms. By the same calculation (not measured in game), the earlier 600 ms
  smoothstep would reach about 3%.
- Real client, combined run completed in 9 min 41 s:

  ```text
  DIORAMA_TESTS=fog-of-war,settings-refresh,whole-map-refresh,antique-fog,day-night,saved-map
  ```

  The time-only cycle kept zero jobs, writes, snapshots and masks.
- `whole-map-refresh` (new): saves the world, drops every snapshot in memory (as when reopening the
  world) and toggles outlines. The revision was redrawn from the saved chunks and published at once:
  154 tiles in 5.6 s on the first run and 20 tiles in 3.4 s on the final run. The player's tile got the
  current look and no previously unknown coverage cell was revealed.
- `antique-fog` gained captures of the frontier (`antique-frontier`, `antique-frontier-close`,
  `clouds-frontier`) for visual inspection of edges, watercolour, coasts and symbols.

The whole game's FPS and the GPU time of the opaque pass under the tiles weren't measured. The cost of
redrawing old tiles from disk depends on how many chunks each tile crosses; it runs in the
background, at most one job while a view waits for tiles, and doesn't block the render thread.

## 16x pixel art and a conservative frontier — 2026-10-20

- Builds with JUnit: Fabric 26.3, NeoForge 26.3 and Fabric 1.21.1.
- Real Fabric 26.3 client, completed in 5 min 10 s:
  `DIORAMA_TESTS=fog-of-war,settings-refresh,antique-fog,day-night`.
- The `antique-frontier`, `antique-frontier-close` (zoom 2.5) and `antique-frontier-far` (zoom 0.2)
  captures show sprites the same size on screen at all three zooms and the ink line on the terrain's
  real edge. An intermediate version left partial terrain visible beyond the line at zoom 2.5; the
  "nothing explored" flag now uses raw coverage and the final capture no longer shows the strip.
- The flicker while zooming wasn't measured automatically; the identified cause (the mask's level of
  detail switch treated as an animated discovery) was removed.

## Sepia, dense sprites, block-sized pixels and pixel-art clouds — 2026-10-20

- Builds with JUnit: Fabric 26.3, NeoForge 26.3, Fabric 1.21.1 and NeoForge 1.21.1.
- `antique-fog` on the real client, with captures of the antique map in sepia and colour and of the
  clouds at zooms 0.2, 0.6 and 2.5. At zoom 2.5 a fog pixel is the size of a block; at the others, one
  GUI pixel.
- Regression on the real client, completed in 9 min 15 s:
  `DIORAMA_TESTS=fog-of-war,settings-refresh,whole-map-refresh,day-night,saved-map`.
  The time-only cycle stayed without new jobs, writes, snapshots or masks.
- `fog-gpu-bench` was removed: it measured the replaced volumetric shader. No GPU time or FPS
  measurement of the new clouds was made.

## Frayed edge, land without icons and the vanilla marker — 2026-10-20

Verification reduced on request: a Fabric 26.3 build with JUnit, a Fabric 1.21.1 compile and one
`DIORAMA_TESTS=antique-fog` run on the real client, inspecting the captures. The other regression
scenarios weren't repeated in this round.

## A frontier stable while zooming, the ocean, the minimap marker and tags — 2026-10-20

A Fabric 26.3 build with JUnit, NeoForge 26.3 and Fabric 1.21.1 compiles, and one
`DIORAMA_TESTS=antique-fog` run inspecting the captures. The stripes on the ocean weren't reproduced
in the test world: the fix (invalidating down to the bottom of the water when a neighbouring chunk
loads, ignoring saved chunks whose light isn't ready and redrawing saved tiles once) was made from
reading the code and needs confirmation in the user's world. Classing by tags wasn't tested with real
datapacks or mods.
