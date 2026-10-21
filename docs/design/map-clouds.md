# Clouds over the map

Small pixel-art cumulus clouds drift over the explored map and cast their shadows on the ground.
They are an effect drawn over the map, not part of it: nothing is baked into tiles, saved, or
counted as explored, and switching them on or off never redraws a tile.

## Settings

- **Drifting clouds** (`mapClouds`, Look, on by default): on the world map, in its book and in
  the settings screen.
- **Clouds on the minimap** (`minimapClouds`, Minimap, off by default): they would hide much of its
  small view.

Both need the render pipelines of 1.21.6 and later (`MapEffect.supported()`); older versions don't
list them. Clouds are only drawn on the surface under an open sky: not in cave slices, under a
roof (the Nether) or where the dimension has no sky light (the End).

## Look

Each cloud is a small cumulus of boxes, two blocks square and two tall, on a flat base: a tall
middle puff with lower ones either side and in front, and sometimes a tall one behind, each a dome
cut into boxes. The map sees them as it sees blocks, through the same oblique projection
(`v = 3/4 · (depth − y)` at the 45 degree pitch): white tops, foreshortened like block tops, and the
pale blue south face of every step under them, so each step down toward the viewer reads as a
band of shade, like the lobes of a pixel-art cumulus. The face of the lowest box along a cloud's
base is a deeper blue. There is no ink outline. With the sepia antique map (and its tint of the
explored map on), the tones are paper and umber instead.

Pixels follow the map's art grid, as in the fog (`fog.fsh`): one GUI pixel on a grid aligned to the
framebuffer, or, once a block is bigger than a GUI pixel, the blocks' own faces, one unit across
and 3/4 of a unit down, so the clouds' pixels lie on the terrain's. Clouds are a couple of dozen
blocks across and fade out as the map zooms out past a few blocks per art pixel, where they would
only flicker.

The shadow is the boxes' footprint, square too, 40 blocks below the bases and pushed away from
the tiles' sun (`TileBaker.SUN_AZIMUTH_*`, west and slightly south, 50 degrees up): about 30
blocks east and 16 north of the cloud on the plane, so on the map it falls to the lower right. It
is a flat wash, deeper under the thicker middle, and only with the sun up. By night clouds sink
toward a moonlit blue.

## Weather and wind

Clouds don't cover the map evenly. A weather field anchored to the world (the fog's noise texture
over 8192 blocks) decides where they gather: in clear weather a few patches some hundreds of
blocks across, in rain most of the map (rain comes from `EnvironmentLighting`, so it only counts
where day and night lighting is supported). Each cloud has a cell of its own, 128 blocks square,
and fewer than half the cells have one, placed anywhere within 38 blocks of the cell's middle, so
they are scattered rather than lined up and never crowd each other. Sizes lean small (a radius of
3.5 to 9.5 blocks, mostly near the low end), and each has its own shape: a tall middle puff, lower
ones either side at hashed distances and heights, sometimes one in front and a taller one behind.
The cells drift east with the wind, 1024 blocks per loop of `GameTime` (about 0.85 blocks a
second). A cloud's size follows the weather where it is, so it grows from nothing as it drifts
into a cloudy patch and shrinks away as it leaves.

## How it is drawn

`MapViewport.draw` draws, in order: the fog's opaque underlay, the tiles, the coast
([coast.md](coast.md)), the clouds, then the fog over everything. The clouds are one quad over the view through their own pipeline,
`DioramaPipelines.CLOUDS` (`fog.vsh` with `clouds.fsh`), through `MapCanvas.effect`. They read the
fog's mask (sampler 0), so clouds and shadows only show over explored ground and fade with it at
the frontier, and the fog's noise (sampler 1) for the weather. Neither is rebuilt for them.

The vertex colour packs what else the shader needs (`MapClouds.params`): the mask's scale, the
mask origin modulo the weather's period (8192 blocks across, 6144 map units down, which is 8192
blocks of depth), daylight, rain, the map direction (the sun turns with it), the art pixel and
its phase, and the sepia flag. The mask origin snaps to at least 128 units, so it fits in six bits a
side, and every world-anchored pattern repeats with that period, so nothing jumps while panning;
the cells' hash also repeats with the wind's 1024 blocks, so nothing jumps as `GameTime` wraps.

Per pixel, `sight` follows the map's line of sight, `(u, w0 + y, y)` for falling height `y`, row
by row of boxes from the south, and stops at the first box whose south face or top it meets.
Only the cells it can reach are gathered (`REACH` covers a cloud's jitter and puffs, so at most two
by two), and of those only clouds whose box the line crosses: at most 20 puffs.

## Testing

`DIORAMA_TESTS=clouds DIORAMA_RENDER_DISTANCE=8 ./gradlew --configure-on-demand :26.3:runClientGameTest`
captures the world map close up, at the usual zoom and zoomed out, in clear weather and in rain,
and the same view with clouds off and on to compare. The `settings` scenario checks that
switching them keeps the baked map.
