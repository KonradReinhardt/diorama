# Foam and ripples on the coasts

Foam breathes in and out along the water's edge and dashed ripples roll in toward the shore, on
the world map and the minimap. Like the clouds ([map-clouds.md](map-clouds.md)) it is an effect
drawn over the map: switching it never redraws a tile.

## Setting

**Coast foam and ripples** (`mapCoast`, Look, on by default), in the world map's book, the settings
screen and Mod Menu. It needs the render pipelines of 1.21.6 and later (`MapEffect.supported()`).
Only detailed surface tiles carry what it needs, so it shows on the surface layer, zoomed in far
enough that the map draws detailed tiles, and fades out once an art pixel spans a few blocks.

## Look

Everything is pixel art on the map's art grid: one GUI pixel on a grid aligned to the framebuffer,
or, once a block is bigger, the blocks' own top faces, 1 unit across and 3/4 of a unit down, so
foam lies on the water's blocks. Distances from the shore are measured on the water's plane in
world blocks, across the map in u and down it in depth (`v / 0.75` at the 45 degree pitch), so a
line a set distance from the shore is foreshortened like the blocks are.

- **Foam**: white along the water's edge, 0.4 to 1.3 blocks wide, swelling and drawing back every
  four seconds. Each stretch of coast keeps its own beat (world-anchored noise), so the swell rolls
  along the shore. It breaks into clumps here and there, less at the crest, and leaves a few
  bubbles behind as it draws back.
- **Ripples**: pale dashed lines an art pixel thick, 3.5 blocks apart, rolling toward the shore at
  0.7 blocks a second from 11 blocks out, fainter further out.
- By night both dim toward a moonlit blue; with the sepia antique map (and its tint of the explored
  map on), they take paper tones.

All speeds are whole cycles per loop of `GameTime` (1200 s), and the noise repeats with the
periods the mask origin is passed modulo, so nothing jumps while panning or as time wraps.

## Data: the surface plane

The GPU tiles are only colour, so bakes record what the coast needs: a byte per pixel
(`bake.Surface`): `KNOWN` where the pixel was traced, `WATER` where its ray went through a water
surface (`TileBaker.outWater`), and `SHORE` where it shows land that really meets the water. The
map's drawn edge between land and water isn't always a coast: a tower on an island, or a cliff,
stands in front of water further north and hides it, and foam along its top and upper walls would
float in mid-air. So after tracing, `TileBaker.markShores` looks at each land pixel next to a
water pixel (or on the baked area's edge): it is shore only if the block its ray hit has water
beside it, straight or diagonally, from two blocks below it to one above. Beaches, banks and the
foot of a wall are shore; the rest of a tall wall and its top are not. Zero is unknown: pixels kept from tiles saved before bakes recorded
it, or never traced. It travels with the pixels everywhere they go: full bakes and patches
(`TileImage.surface`, `Patch.surface`), the redraw from saved chunks, `TileBaker.merge` (a pixel
kept from the saved tile keeps its saved bits), and `TilePixels.surface` in the store.

The store saves it beside each detailed tile's OMT2 bundle, as `<u>_<v>.omts` (`SurfaceFile`):
the tile's key, the CRC of the day plane it was baked with, and the deflated plane, written
atomically after the bundle. The bundle itself is unchanged, so builds that don't know surfaces
read maps as before. A bundle such a build writes later no longer matches the CRC, and its stale
plane is ignored rather than trusted; nothing is deleted. Overviews record none.

`BakeContext.LOOK_REVISION` went to 5 for the plane and 6 for shores, so in singleplayer on 26.3 tiles saved before are redrawn
from the world's saved chunks in the background, nearest the views first, and gain their plane
(only pixels already explored are redrawn). Elsewhere (multiplayer, older versions) a tile gains it
when it is baked again, as the player passes by; until then its coasts show no effect.

On the client, `MapTile` keeps the plane as two bitsets (known, water): 64 KB a tile.

## Drawing

`CoastMask`, one per view, samples the surface bits of the detailed tiles on the GPU under each of
its 512 x 512 texels (texel from a map pixel up to 8 units, a power of two, snapping its origin
every 64 texels), then a two-pass chamfer distance gives each texel's distance to the nearest
shore in blocks, a texel being `texel` blocks across and `texel / 0.75` deep. Only shore texels
count: land that only hides water, and unexplored ground, draw no foam. Red holds the distance in
sixteenths of a block (up to 16), green water, blue land. It is built again when the view's
origin or texel changes, and at most every 100 ms as tiles arrive.

`MapViewport.draw` draws the coast after the tiles and before the clouds and the fog, as one quad
through `DioramaPipelines.COAST` (`fog.vsh` with `coast.fsh`) via `MapCanvas.effect`: the coast
mask in sampler 0, the fog's noise in sampler 1, and `MapViewport.coastParams` in the vertex
colour: the texel size, sepia, daylight, the mask origin modulo 256 units across and 768 down (in
fours), and the art pixel with its phase.

## Testing

`DIORAMA_TESTS=coast DIORAMA_RENDER_DISTANCE=8 ./gradlew --configure-on-demand :26.3:runClientGameTest`
finds a shore near the player, asserts the bake recorded the water there, and captures it close up
over a few frames, at the usual zoom, further out, and with the effect off; then builds a tower on
an island in a pool high in the air, asserts its top is land but not shore, and captures it with
the effect on and off. `SurfacePlaneTest`
covers saving and reading the plane, patches, and a bundle rewritten by another build;
`CoastMaskTest` the distances.
