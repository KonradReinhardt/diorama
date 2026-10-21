# The painted render style

Implemented on 2026-10-20 on the `feature/painted-style` branch. The option replaces the old
outlines toggle: `Settings → Look → Render` switches between **Plain** (no outlines), **Outlined**
(the previous look), **Painted** (the default since 1.0.0) and **Painted, no outlines**. The
preference is shared by the minimap, the world map and the Mod Menu screen.

**Painted, no outlines** (`MapStyle.ink` false) is the same finish without any ink line: not on
edges, not between colours, not on the coast. The per-face paint, the two-tone light, the water in
washes with its rings and foam, the edge highlight and the texture stay.

## Goal

Make explored terrain look like a hand-painted fantasy map: flat areas of colour, cel-shaded light,
subtle ink lines and drawn coasts, without the texture and soft shading of Minecraft's blocks.

## Where it happens

Everything is computed while baking tiles (CPU, `TileBaker` + `PaintedFinish`), not in a shader. So
the style works the same on all 21 targets and loaders, joins the same atomic appearance refresh
(`MapStyle.looksLike/looksHash` include `painted`, so `appearance.bin` and the background queue
redraw saved tiles) and the day/night compositor still only blends the two planes.

### Light (`TileBaker`)

- **Two tones**: each surface gets either its sunlit colour or a single cast-shadow colour. No
  corner occlusion (AO), sky occlusion or altitude gradient; walls are only a little darker than the
  ground (0.86).
- **Crisp shadows**: one ray per pixel on the block grid (`docs/design/pixel-grid.md`), without the
  sun's jitter, so a shadow is a flat patch cut out along each face's texels.
- **Palette**: golden sun, bluish-lavender shadow, a little more ambient light (`MapStyle.paint()`),
  and a pigment curve where nothing reaches pure black or blows out to white (a slightly violet
  floor, a shoulder in the highlights).
- **Plants** on the ground weigh less (0.22 instead of 0.4), so grass doesn't speckle the terrain.
- **Water in washes**: vertical depth falls into four bands (shallow < 1.25, < 3.5, < 8 and deep),
  each darker and showing less of the bottom; the biome's colour is pulled 55% toward the teal of a
  painted map.

### Finish (`PaintedFinish`)

It takes the traced patch with a 12 px border and returns only the core:

1. **Paint**: a Kuwahara filter over the grid's faces (each one a 4×3 px cell), on the albedo: a
   face's dab is the average of the 2×2-face window that varies least, applied at 90%. Each pixel
   changes by the ratio between the dab and its albedo, so the faces' colour melts into patches while
   the per-texel light (shadows) stays intact; nothing leaves the grid. Day and night get the same
   factor.
2. **Ink**: a warm sepia line on the pixels behind an edge (weighted by the exact distance the
   `TileBaker` works out from the faces), a lighter line on the dark side of neighbouring faces of
   very different colours, a firm line on the land along every coast, and bluish rings where the
   water changes wash. Only the 4 neighbours: 1 px, never thicker at corners. The stroke's weight
   varies with world noise, like a pen by hand; the strongest line wins instead of adding up. No
   outline of the bottom through the water.
3. **Light**: a thin warm highlight on sunlit edges facing the camera, and a line of foam on the
   water along the land.
4. **Texture**: short brushstrokes following a slowly turning direction field (long and horizontal
   on water), the same strokes 4× larger on land (so open fields show brushwork at medium zoom too),
   a large uneven wash with a slight warm/cool drift, and paper grain. All of it subtle, so as not to
   bring back the texture step 1 removed.

### Guarantees

- Every pattern is indexed by world pixel, never by position in the patch: a partial patch is
  identical to the same pixels of the whole tile and neighbouring tiles have no seams
  (`PaintedFinishTest.aPatchMatchesTheSamePixelsOfAWholeArea`).
- Alpha is preserved and unknown pixels stay unknown: the style never grows coverage or reveals
  terrain (`keepsEveryAlphaSoCoverageNeverGrows`).
- The extra reach (12 px, three blocks) is within the margins `TileInvalidation` already uses (shadow
  and sky occlusion already ask for much more), so edits redraw everything they affect.
- Switching styles keeps exploration and resident tiles (the `settings-refresh` scenario), and the
  tiles on screen change together (the `style-swap` scenario; see "Switching styles").
- Plain and Outlined use nothing of the painted finish; all three styles share the grid and the
  geometric outline.

## Sharpness

Tiles are magnified with NEAREST whenever a texel covers at least one real screen pixel (zoom × GUI
scale × overview level size), and the drawn view snaps to a whole number of screen pixels per texel;
see `docs/design/pixel-grid.md`.

## Migration

Old configs only have `outlines`: `true` becomes Outlined, `false` becomes Plain, and the field is no
longer written. The default for new configs is Painted since 1.0.0 (before, Outlined).

## Cost

The finish is one linear pass over the area: per-face averages, one Kuwahara per face (not per
pixel), 4 neighbours and table lookups. Measured on a whole 512² tile with day and night (JUnit, best
of 8, on this machine): ~31 ms, against ~0.2–0.34 s average bake per tile in the client test's logs
with the grid. The brush, wash and grain tables are generated once when the class loads (256², 128²,
128²).

## Switching styles

Since the style comes from the bake and saved tiles keep only RGB, switching styles (or depth
shading) traces the resident tiles again. The appearance refresh used to wait for *all* resident
tiles and swap everything in a single frame; with dozens or hundreds of tiles at ~0.2–0.3 s per tile
per baker, a click in the book took seconds to show. Now the swap is **viewport-first**
(`AppearanceRefresh.swappable`, `MapLevel.swapStaged`):

- The queue starts with the tiles the views draw. They swap **together, in the same frame**, as soon
  as all of them are ready: the screen never shows half in one style and half in the other.
- Resident tiles no view draws (including the preloaded ring past the edge, `MapLevel.OFF_SCREEN`)
  swap as they arrive, up to 4 per frame; nobody sees them change.
- Overviews stay behind the writer's barrier and swap together last, after every detailed tile.
- Before each swap, generation and terrain/light freshness are checked again; a stale result goes
  back to the queue and holds the on-screen group. Once swapped, a tile follows the usual path (edits
  go straight up) and isn't traced again by the revision. `appearance.bin` is stamped at the swap, as
  it used to be at the single commit.
- Bakes still queued when the generation changed (a new style) skip their work as they start, instead
  of tracing a whole tile that would be thrown away.

Measurement (`DIORAMA_TESTS=style-swap`, Fabric 26.3, 1600×900 window, on this machine, 2026-10-20):
the scenario visits four places 400 blocks away to leave 57 resident tiles (109 MiB), comes back to a
fixed spot (8.5, 8.5), opens the book at Look and clicks the style. Time from the click until the 4
tiles on screen change, and until the whole revision is done:

| Switch | Before (screen = whole revision) | After: screen | After: whole revision |
| --- | --- | --- | --- |
| Outlined → Painted | 4421 ms | **875 ms** | 4223 ms |
| Painted → Outlined | 3148 ms | **448 ms** | 3150 ms |

The scenario fails if only some of the tiles on screen change, if a tile on screen loses its texture
or coverage, or if the click doesn't pick the style. The total work doesn't change; only the order in
which it becomes visible. With more tiles on screen (zoomed out, a larger window) the time grows with
them, not with the resident tiles.

Keeping the previous style's textures to switch back instantly was considered and **not** adopted:
each resident tile costs ~1.9 MiB of GPU memory (day + night), switching back already takes under
half a second, and the cache would save no bakes: the disk and `appearance.bin` would still need the
pixels in the chosen style, so the same tiles would be traced anyway, now with textures outside the
GPU budget (then 256 MiB, 512 MB today).

## Limits

- Caves, the Nether and the End also get paint, ink and texture; there the light is already the
  game's and the two-tone step only removes the AO.
- Historical pixels that can't be redrawn (multiplayer, versions without `SavedChunks`) keep the
  look they were saved with, as in the other styles.
- Far out, mipmaps average lines and brushstrokes away; what holds the style at that scale are the
  flat areas, the palette and the water in washes.

## Validation

`DIORAMA_TESTS=painted DIORAMA_RENDER_DISTANCE=8 ./gradlew --configure-on-demand :26.3:runClientGameTest`
captures a village and a coast in all three styles up close, Painted at a medium zoom, the minimap and
night (Fabric 26.3).
