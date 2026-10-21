# A pixel-perfect map on the block grid

Implemented on 2026-10-20 on the `feature/pixel-grid` branch. It applies to all three render styles
(Plain, Outlined and Painted), the world map and the minimap, on every target.

## Problem

With the camera at 50° and 4 px per block horizontally, a block's top took ~3.06 pixel rows and its
side ~2.57. Faces alternated between 3 and 4 rows, and the 4 samples per pixel mixed neighbouring
faces at the edges and softened shadows into half pixels. On screen, a texel magnified by a
non-integer factor (2.4 screen pixels at zoom 3.2 with a 3× GUI, for example) also came out with
alternating widths. The outline compared average depths against a threshold, so it drew the relief
of the bottom through the water and wobbly lines where the average oscillated.

## Projection

`v = z · zScale − y · yScale` with `zScale = 3/4` (`Projection.TOP_ROWS / PIXELS_PER_UNIT`) and
`yScale = zScale · cot(pitch)` (`Projection`). It is the earlier angled view scaled vertically, so a
block's top always takes 3 pixel rows. The default pitch went from 50° to 45°: there the side also
takes 3 rows, so **every visible face is 4 pixels wide by 3 tall**, every block edge falls on a
pixel boundary and every 4×3 px cell of the grid shows exactly one face. The proportions stay close
to those before the grid (top 3.06 → 3 rows, side 2.57 → 3), with the same oblique view and the same
horizontal scale.

The first version of the grid (the same day) used 4×4 faces (`zScale = 1`); it made buildings
visibly taller and narrower (+31% on tops, +56% on walls) and was replaced by the 4×3 one.

Each pixel's ray steps `zScale` down and `yScale` north per step; the depth of water crossed,
measured along that step, is already vertical.

## Bake

- **One ray per pixel, through its middle.** Since the pixel lies entirely inside one face, the 4
  antialiasing samples add nothing but blur to shadow and light. Shadows, AO and sky occlusion are
  now evaluated per texel, without the sun's jitter. Baking is about 4× faster (the average tile in
  the client test went from ~0.7 s to ~0.2–0.34 s).
- Quality now only affects the fog: both modes produce the same pixels, so it left
  `BakeContext.appearance()` and changing Quality doesn't redraw the map.
- `LOOK_REVISION` 3.

## Geometric outline

Each ray records the face the pixel shows: a top (a `y` plane) or a south face (a `z` plane). Where
the ray crossed water, the face is the **water's surface**, never the bottom. For each pair of
neighbouring pixels (right and below), `TileBaker.edges` works out where the two planes are on the
screen line between them (`faceDepth`):

- if they coincide, the faces meet (a top and its side, a floor against a wall) and there is no
  line;
- if not, the farther pixel is **behind** (`behind`) the other by the difference, in blocks along
  the view, and the nearer one is **in front** (`front`).

The result is exact to the pixel, with no averaging or noise: a 1-block step gives 1.41, a 2-block
step 2.83. Outlined darkens (0.38) the pixels behind a step of 2 blocks or more (`OUTLINE_DEPTH`
1.5, as before); Painted uses `behind` for the weight of the ink and `front` for the edge highlight.
Lines only use the 4 neighbours, so they never thicken at corners. Inside water there is no outline
at all; the coast keeps Painted's line and foam.

## Painted on the grid

The paint (Kuwahara) now works per **face**: each 4×3 px cell has the average albedo of its land
pixels, and the dab comes from the 2×2-face window that varies least. Each pixel is multiplied by the
ratio between the dab and its own albedo, so the face's colour changes while the per-texel light
(shadows) stays intact, and no edge leaves the grid. Lines between colours only appear between
different faces. The patch's border is 12 px (a multiple of both face measures), and faces are
located by world pixel coordinate, because a tile's rows (512 px) don't fall on multiples of 3.

## Pixel-perfect display

`MapViewport.View.pixelPerfect()`: when a texel covers at least one screen pixel, the drawn view
rounds the zoom to a whole number of screen pixels per texel and shifts the centre by less than half
a pixel, so every texel edge falls on a pixel edge. The minimap always draws with this view; tiles,
fog, radar, waypoints and the marker all use the same view. Below one screen pixel per texel nothing
changes (trilinear mipmaps).

On the world map, when magnified, the wheel goes straight to the next whole number of screen pixels
per texel (`SmoothZoom.scroll` with `pixelZoom`; ×1.25 below that, as before). While the zoom
animates, the view is drawn as it is, without rounding, and only snaps once it stops, always on a
whole step. Rounding every frame made the zoom move in jumps, and a ×1.25 step often came back to the
same value.

## Saved maps

Pixels of the previous projection can't be reprojected without the geometry, so the grid saves into
new folders: `surface-grid3-45` and `under-roof-grid3-45-…` (plus the direction suffix). The old
folders (`surface`, `surface-50`, `under-roof-50…` and `surface-grid-45`, from the 4×4 grid) stay
intact on disk and are never deleted. The grid's map starts empty and fills in by exploring again. A
future migration could, in singleplayer 26.3, trace the old rays through `SavedChunks` to find which
columns were already explored and redraw them on the grid.

## Validation

- JUnit `BlockGridTest`: every face is 4×3 px and every edge falls on a whole pixel; faces that meet
  have the same depth and a 2-block step gives 2√2; `pixelPerfect` gives a whole number of screen
  pixels per texel, aligns the edges and moves the centre by less than half a pixel.
- JUnit `PaintedFinishTest`: patch == whole tile, alpha preserved, a patch off the grid is refused.
- `DIORAMA_TESTS=painted DIORAMA_RENDER_DISTANCE=8 ./gradlew --configure-on-demand :26.3:runClientGameTest`
  captures a village and a coast in all three styles up close, Painted at a medium zoom, the minimap
  and night (~5.5 min, against ~11 min before the grid and at render distance 12).
