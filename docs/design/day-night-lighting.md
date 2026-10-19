# Day/night lighting for Diorama

Status: design for review, not implemented. Target the Fabric 26.3 build first.

## Product behavior

The surface map should follow the current world's daylight and weather. At night, torches, lanterns, lava and other block-light sources should illuminate surrounding mapped surfaces. Returning to daytime must restore daylight without clearing tiles or exploration. Map direction, zoom, waypoints, overlays and the player's arrow must stay consistent.

The first release should keep the existing decorative sun/shadow direction. It changes daylight strength and color continuously; it does **not** animate geometric shadows around the sun's full orbit. Moving shadows require additional visibility data and a separate performance decision. Both limitations should be stated in the feature description.

Caves and dimensions with a ceiling keep their existing block-light rendering initially. The End and other fixed-light dimensions need explicit dimension policy, rather than an Overworld clock applied everywhere. The mod continues to use only data available to the client and must honor server restrictions.

## What the current code does

- `TileBaker.shade()` bakes the daytime sky, decorative sunlight, shadows, occlusion, color grading and emission directly into surface RGB pixels. A luminous block can already appear bright itself, but its light does not illuminate neighboring daytime surface pixels.
- The cave/under-roof branch already reads `VoxelReader.blockLight()` and `skyLight()` and produces warm local light. Reuse its sampling rules, not its large readability floor, for the outdoor night channel.
- `ChunkSnapshot` already copies sky and block light for every captured section. A first implementation does not need to rescan the live world from worker threads.
- `MapSession.lightSectionChanged()` and `lightUpdated()` currently invalidate surface lighting only for spawn overlays or roofed worlds. Night lighting needs a separate surface-light invalidation path.
- `TileImage`, `MapTile`, `MapCanvas.rect()` and `TileStore` currently carry one final color plane. The saved `OMT1` file contains only that color image; old files cannot reconstruct the locations of torches or their light fields.
- `MapLevel.colorsChanged()` now preserves same-projection textures and coverage during refreshes. Time changes must not call it: continuous clock motion is a draw-time input, not a reason to re-bake terrain.

## Recommended rendering model

Generate two color endpoints in one camera-ray pass, then blend them on the GPU:

1. **Day endpoint:** preserve the current surface appearance, including decorative shadows.
2. **Night endpoint:** reuse the hit material/albedo, occlusion, emission, sampled block light and a small moon/sky ambient term. Block light is sampled in the open cell in front of the visible face, matching the existing cave convention.
3. **Coverage:** retain a separate, shared validity mask. Neither darkness nor a low light value may mean that a pixel is unknown.
4. **Frame lighting:** derive a normalized daylight weight and color/weather parameters from a version-specific client environment adapter. Upload one small immutable lighting state per frame.

Conceptually, with colors in linear space:

```
color = mix(nightRGB, dayRGB, daylightWeight)
alpha = explorationCoverage
```

Torch contributions must already be present in the day endpoint too, at lower apparent contrast. Otherwise a torch would visibly turn on only when the blend starts. The night endpoint has a low configurable ambient floor for readability, with emission clamped independently from diffuse occlusion. Do not use the cave branch's fixed `0.42` base outdoors; it would wash out the night.

The first version should avoid a separate night camera-ray trace: compute both endpoints from the same hit, normal, material and lighting samples. Water, foliage and translucent contributions need both endpoints accumulated per sample, rather than tinting an already composited daytime tile afterward.

A future version could store material and lighting coefficients and evaluate more lighting on the GPU. That permits a more accurate weather/moon response, but increases format, shader and memory complexity. It is not needed to validate the requested behavior.

## Component changes

| Component | Responsibility |
| --- | --- |
| New `EnvironmentLighting` value + adapter | Read the client dimension's time/sky/weather state on the client thread; handle fixed-time dimensions and the midnight wrap; publish immutable draw parameters. Verify the actual 26.3 methods before coding the adapter and isolate older-version differences here. |
| `TileBaker` | Produce day/night endpoints from one ray traversal, including water/transparency and block-light sampling. Keep the frozen `BakeContext` per job. |
| `TileImage` | Carry optional night pixels and matching mip levels for full tiles and partial patches. Missing night data is a supported legacy state. |
| `MapTile` / `TileCache` | Own and account for all resident planes. Replace patches together and retain their shared coverage. |
| `MapCanvas` / GPU backends | Add a lit-map draw operation binding the endpoints plus lighting uniforms; support state-render and legacy backends separately. Keep icons and labels outside this lighting pass. |
| `MapLevel` | Track terrain and lighting freshness separately. Schedule bounded light updates without removing the visible tiles or coverage. |
| `MapSession` | Route relevant surface light events to the light channel; time/weather changes update only environment parameters. |
| `TileStore` / overview builder | Persist optional night data and build matching overview planes without changing the meaning of exploration. |
| Settings / Mod Menu | Add `Follow world time` (default on once validated), `Always day`, `Always night`; optional night readability slider. Reuse the shared configuration screen. |

## Invalidation rules

| Trigger | Required work |
| --- | --- |
| Normal passage of time, sunrise/sunset, weather strength | Draw parameters only. No tile generation, snapshot capture, disk writes or fog-mask invalidation. |
| Server `/time set`, frozen daylight cycle, dimension change | Update environment state from that dimension. Do not extrapolate time when the server has stopped it. Permit a short presentation transition, not a multi-minute interpolation around midnight. |
| Terrain/material edit | Recompute both endpoints for the affected tile patches. |
| Light packet / torch placed or removed | Refresh affected light data and night/day local-light contributions. Include neighboring sections, vertical reach and diagonals. Coalesce repeated events and prioritize visible tiles. |
| Appearance settings | Use same-projection refreshes or uniforms where mathematically valid; retain textures and exploration coverage. |
| Camera direction or pitch | Use the corresponding projection cache. World-space visibility progress stays independent. |
| Fog-of-war restrictions become stricter | Immediately hide disallowed cave/roofed coverage. Keeping stale colored tiles is never allowed to bypass a server rule. |

A dedicated lighting generation/version must reject stale light patches after a terrain change. A light-only refresh cannot replace coverage or geometry produced by a newer terrain bake. Unit-test this ordering explicitly.

## Saved maps and compatibility

A legacy RGB image has no reliable information about nearby torches. Do not claim to recover block lighting from its brightness, and do not clear legacy maps to force migration.

Recommended migration:

- Continue reading existing `.omt`/`OMT1` files and their coverage.
- Add an atomic tile bundle containing day/night planes, validity flags, projection identity and schema version. Use a new `.omt2` extension alongside the existing files during migration, preserving original files for rollback.
- Prefer a validated new bundle; fall back to the old tile if it is absent or corrupt.
- For old tiles with no night plane, display a documented daylight-dimming approximation. Do not invent torch positions. Upgrade those tiles when their chunks are actually revisited.
- Write the new bundle only after all planes for the same terrain generation are available. Never combine a new day image with an old night image silently.
- Derive overview tiles from paired children, using linear-color downsampling and coverage-aware weighting. Mixed old/new children retain explicit light-data availability.
- Avoid a one-shot rewrite of an entire explored world. Migration is incremental and cancellable; no deletion of the old map is part of enabling this option.

This is a proposal, not a promise of a final on-disk format. Validate bundle size, generation matching and rollback with a fixture corpus before fixing the schema.

## Performance and memory gates

A 512x512 RGBA tile is 1 MiB at level 0 and roughly 1.33 MiB with a complete mip chain. At the current 192 GPU-tile limit, one plane is already roughly 256 MiB, before overlays. A second full-resolution plane could add another 256 MiB. Merely doubling the textures under the existing count limit is not acceptable.

Before enabling by default:

- Measure the existing day renderer on representative worlds in Fancy and Fast modes.
- Add a byte budget that accounts for every map plane, mip and overlay; keep the tile-count limit as a second bound. Evict cold tiles to stay within the chosen memory target.
- Upload paired endpoints only for visible/nearby tiles, with a per-frame upload budget. Do not stall the client thread waiting for night data or disk reads.
- Reuse the camera intersection and expensive occlusion samples for the two endpoints. Profile incremental light patches separately from a full terrain bake.
- Target no additional tile jobs or tile-file writes during a full time-only day/night cycle. This is an enforceable test, not an FPS estimate.
- Investigate lower-resolution light data only after visual tests; naive upsampling can bleed torch light through walls or across height discontinuities.

Record p50/p95 frame time, bake/patch latency, GPU bytes, snapshot cost, queue length and bytes written. Choose numerical frame-time limits from the measured baseline before implementation phase 2.

## Delivery sequence

1. **Fixtures and measurements:** establish current screenshots and performance counters, inspect the time/weather APIs for 26.3, and create a deterministic test world with an open field, canopy, bridge, water and a torch-lit structure.
2. **Paired CPU data:** extend full/partial tile results and make the baker produce endpoints in one traversal. Add tests for light sampling, emission, coverage, ordering and mip edges. Keep the feature disabled by default.
3. **26.3 GPU prototype:** implement the two-texture surface pass and environment interpolation. Add the shared settings controls. Confirm that time-only changes create no bake or persistence work.
4. **Persistence and LOD:** implement incremental legacy fallback, atomic bundles, mixed-data overview tiles, cold-world reload and byte-budget eviction. Do not ship a prototype that works only while all chunks are loaded.
5. **Compatibility and release gate:** validate fixed-light dimensions, weather, negative coordinates, four map directions, Fast/Fancy, resource reloads and server restrictions. Extend to older Fabric and NeoForge only after 26.3 passes; document any temporary platform limitation.

Each phase gets a separate feature branch/PR based on the integration branch. No implementation is included in this planning change.

## Acceptance tests

- Take equivalent screenshots at noon, sunset, midnight and sunrise; verify continuous transitions and identical exploration coverage.
- At midnight, place then remove a torch: nearby ground changes light, while unaffected tiles retain identity and coverage. Test a chunk corner, a bridge and a wall to catch reach/occlusion errors.
- Verify a visible light-emitting block and its surrounding illumination separately. Test water and glass compositing.
- Freeze daylight, change time abruptly, cross midnight and change dimensions. Fixed-light dimensions must not pulse on an Overworld clock.
- Reopen a world at night with its chunks unloaded. Previously upgraded tiles retain block lighting; legacy tiles use their explicit fallback without disappearing.
- Load corrupt or partially written bundles and fall back safely. Reject mixed terrain/light generations.
- Change every display/quality setting, toggle fog rules and rotate through all four directions. No time or appearance change may reset world exploration.
- Measure a complete time-only cycle: zero terrain re-bakes, zero lighting re-bakes, zero map writes, and no coverage-mask rebuild caused solely by the clock.

## Decisions for review

Recommended defaults are: follow world time, fixed decorative shadow direction for the first release, warm Minecraft block-light approximation without colored-light-mod support, incremental legacy fallback, and a measured GPU byte cap. Full animated solar shadows and colored light are separate features because the current client light snapshots do not supply those extra visibility/color fields.
