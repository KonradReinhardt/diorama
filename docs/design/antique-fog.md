# Antique map, seed cartography and eased discovery

Implemented in version 0.4.0. The existing default style is still Clouds. The preference is shared by
the minimap, the world map and the Mod Menu screen.

## Look

`Settings → Look → Fog style → Antique map`. Explored terrain keeps its own colours, lightly
desaturated (66% of the saturation) and warmed. The `Antique: tint explored` option
(`Settings.antiqueTintsExplored`, on by default, for both antique maps) turns that tint off: the
explored map keeps the render style's colours and only the unknown becomes an old map. It is
composition only (`Settings.antiqueTint()` picks the path while drawing), so switching it neither
rebakes nor writes tiles; the `antique-fog` scenario checks this and captures
`antique-explored-own-colours`. The real map keeps its blocks, buildings, shadows and, on the
compatible backend, night lighting. The same grading exists in `map_light.fsh` (Fabric 26.3) and in
the tile mode of `fog.fsh` (the other backends). Markers keep their colours. The
`textures/map/parchment.png` texture is an original 256×256 RGB asset; its alpha gets tileable noise
generated on the client, used for organic edges without an extra texture.

Both fog styles are pixel art in the style of 16x textures. Each art pixel is one GUI pixel (the GUI
scale in framebuffer pixels, up to 4), on a grid aligned to the framebuffer, and it doesn't grow as
the zoom goes out. Zoomed in until a block is larger than a GUI pixel, the grid becomes the map's
block grid, so fog pixels are the size of terrain blocks. `MapViewport` passes the grid's size and
phase (where the world's origin falls on screen), so it follows the map in whole-pixel steps. Each
colour is computed once per art pixel.

Styles: Clouds, Antique map (sepia, the antique map's default) and Antique map (color), in the same
picker in the settings. Settings saved with "Antique map" stay sepia.

Antique map:

- Frontier: the antique map's mask has 512 texels and is conservative: a texel only counts as
  explored if its least known coverage cell is complete (≥ 90%), after rounding in map units. The
  outline sits at the 0.75 threshold of the bilinear field, inside the explored texels. On top of
  that, the edge is frayed like that of a hand-coloured map (reference: Civilization VI): the known
  share within two radii (6 and 22 blocks, growing only once the art pixel exceeds them) is compared
  with parchment noise anchored to the map and blended smoothly between scales, so the edge recedes
  in broad lobes and small notches, leaves pockets of fog and keeps the same shape when zooming. It
  only recedes inward, never uncovering terrain without data. A one-pixel ink line, two bands of
  scorched paper and a one-pixel ink shadow inward follow the frayed edge.
- Sepia: fills stay in the light half of a ramp from umber to parchment, like washes on paper;
  coasts and sprites use the whole ramp, like ink strokes. Water is a little darker than land. The
  colour variant uses the biome palette.
- Biomes: flat fills per class, with a one-pixel shadow on edges between classes; each point's class
  is the heaviest among the four neighbouring texels. On the CPU, each texel bilinearly weighs the
  preview's four samples, at a point shifted by world-anchored noise, so the edges are curved.
- Water: shallow and deep from the water field, a dashed line offshore, one-pixel foam on the water
  side and a one-pixel dark outline on the land side, only where both sides have a prediction.
- Land without icons: biomes only show as washes, with the one-pixel shadow on edges between classes.
  The sea keeps its details: foam, offshore dashes and a 16×16 ripples sprite
  (`docs/design/antique-sprites.py`) at world points spaced by a power of two of units (at least 20
  art pixels).
- Paper: the parchment's blotches in three flat tones.

Clouds: the cloud height at each art pixel comes from noise and known coverage, drawn as puffs in
three steps of flat tone. A step down to the south is the shaded underside of the puff above; to the
north, the lit edge. Clouds cast a dotted shadow (every other pixel) to the south-east, on the map.
The tone follows daylight, and caves use their own palette. The old eight-layer volumetric shader and
its optimisation were replaced.

An opaque pass of the same shader is drawn before the tiles (the u coordinate shifted by 16, decoded
in the shader). Where the terrain has holes, the fog shows, not a flat colour. On the antique map, the
pass over the tiles only draws near the frontier: flags in the mask mark texels with no terrain
nearby (from raw coverage) and fully explored texels. In Clouds, each point read checks its own
texel's flags, so only the coverage blur counts toward their reach. Switching the mask's level of
detail while zooming doesn't animate the discovery, and coverage-only changes rebuild the mask at
most every 40 ms. Clouds keeps the 256-texel mask.

## Predicted cartography

The initial implementation only queries Overworld biomes in Minecraft 26.3 singleplayer, on Fabric
and NeoForge. It needs a compatible `NoiseBasedChunkGenerator` and `MultiNoiseBiomeSource`. Worlds
without that context, multiplayer, caves, the Nether, the End and earlier versions keep showing
parchment. The Seed cartography option turns the prediction off without changing the style.

`SeedBiomeSource` captures the seed, frozen registries and the generator's configuration on the
integrated server. The worker creates its own `RandomState`, resolver and biome parameter search tree.
It doesn't use the active generator's caches. The capture is an entry task, not a query per sample
or per frame. The worker may wait for that capture; the render thread never waits.

Each biome is classed by its `minecraft:` and `c:` tags (ocean, mountain, forest, desert, snow,
swamp) and, without a tag, by its name. So biomes from datapacks and mods that declare the common tags
fall into the right class. Sampling queries biomes at height 64. Biomes and the real coastline may
differ: it is not an exact prediction of height, rivers, beaches or terrain of chunks generated by
earlier versions. It doesn't query `getBaseHeight`, structures, blocks or light, nor request chunk
generation/loading. Multiplayer's hashed seed isn't treated as the original seed. The observed map
always replaces the prediction.

## Costs and cancellation

- Regions of 4096×4096 blocks: first 16×16 samples, step 256. At a zoom of at least 0.15 it refines up
  to 64×64, step 64, and at a zoom of at least 0.45 up to 128×128, step 32, always reusing the earlier
  samples.
- One lowest-priority worker and one active region at a time. It processes at most 16 regions of the
  viewport, centre first; it doesn't walk the whole world.
- Continuous movement waits 250 ms. Changing the viewport cancels the current region before the next
  sample. Interruptible pauses of up to 20 ms keep leaving the world and target changes responsive.
- Slices of about 2 ms, followed by a pause of 9 times the time spent: a target of 100 ms of sampling
  per second on a lowest-priority thread. It is cooperative: a single call may exceed the slice; above
  50 ms (a machine busy with bakes, generation or GC) the worker rests 1 s and carries on. The
  prediction is no longer switched off for the rest of the session. Creating the sampler, codecs and
  the disk cache are costs separate from sampling.
- A worker cache and a render cache of up to 960 references and 4 MiB of samples each, dropping the
  least used. They share immutable arrays; in the worst case of different sets, the arrays add up to
  less than 8 MiB. No unbounded job queues or per-region GPU textures.
- An independent disk cache, checksums and atomic publishing. About 64 MiB per dimension, cleaned up on
  open and every 16 writes. Temporary files don't count as results. Cache errors allow recomputing.
- The identity includes seed, version, algorithm, biome source, settings and the content of the noise,
  density and parameter registries. Changing a referenced datapack invalidates the prediction even if
  the registries' names stay the same.
- Rendering only queries ready data. A cursor reuses the row's region while rasterising. Once a still
  viewport is complete, there is no further sampling, per-frame disk reads or continuous mask uploads.

The prediction uses free channels of the fog's own mask. The GPU only adds one shared 256 KiB paper
texture, without a growing atlas of regions. Each viewport keeps a 256 KiB RGBA mask. The style uses
the same pipeline with two samplers, including on the older backends. It doesn't write
`.omt`/`.omt2`, doesn't call `markSeen` and doesn't add to exploration.

The limits on samples and textures are explicit; the original target of 32 MiB of total extra RAM,
including the vanilla sampler, still needs a specific measurement of retained memory. It isn't
presented as a proven ceiling.

## Discovery and zoom

`FogReveal` keeps the coverage shown and the target coverage. The shader applies a 420 ms cubic
ease-out transition in both styles, with 64 opacity steps. Each tile arrival restarts the clock from
the shown state; with smoothstep (zero speed at the start) the frequent restarts while opening the map
froze the reveal, while the ease-out keeps advancing with each restart.
Switching level of detail (overviews ↔ detailed tiles) restarts `FogReveal` without animating, like
switching the mask's texel: both change at different zooms, and animating the switch revealed the
frontier again at every zoom. While a tile of the level shown isn't on the GPU, the mask borrows the
coverage and terrain of resident tiles of another level (the one below first, then the overviews
above); neither the borrowing nor the tile that replaces it is animated. Only resident tiles lend, so
coverage the map dropped (fog of war, another folder) doesn't come back. Zoomed out (overviews),
nothing is animated: coverage there only changes when an overview is redone from tiles already shown
up close.
Only increases in coverage are animated. Decreases hide the data immediately; changing layer, world
or direction drops the earlier history. Moving the viewport reprojects the existing history, avoiding
restarting the discovery during a pan. The animation requests no terrain bakes or per-frame mask
uploads.

`SmoothZoom` accumulates the wheel into a target and interpolates the zoom's logarithm in real time.
The 75 ms constant gives a quick approach, with no per-tick jumps, keeping the point under the cursor
in every frame. Dragging or recentring cancels the pending animation; caves and maps without overviews
keep their zoom limit.

## Render optimisations

Fully revealed pixels, or pixels with nothing to draw, discard the fragment early, from the mask's
flags. The mask precomputes its columns and the GPU interpolates the discovery. Changing style is
composition, without reprocessing terrain bake pixels or deleting saved maps. The `fog-gpu-bench`
test was removed together with the volumetric shader it measured. The `antique-fog` test covers the
real shaders of both styles, prediction in unknown regions, separation from exploration, switching
styles without new bakes and zoom anchoring. Measured results are in
[docs/testing/antique-fog.md](../testing/antique-fog.md).
