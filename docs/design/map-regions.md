# Map regions — plan

Status: plan written on 2026-10-21. Steps 1–6 implemented the same day on the `feature/map-regions`
branch; the "How it turned out" sections at the end record where they departed from the plan.
Steps 7 and 8 are still to do.

## Goal

The player draws regions on the world map — a kingdom, a farm district, a claim, "here be
dragons" — each with a colour and an optional banner, in the spirit of MapScribble
(<https://github.com/radishsprouts/MapScribble>), but native to this map:

- Freehand and point-by-point outlines, closed into filled regions; edit, move and delete them, with
  undo and redo.
- Every render style draws regions in a look of its own: Plain, Outlined, Painted, Painted without
  outlines, and the two antique map styles. Lines are pixel art: one art pixel is one GUI pixel, or a
  block when a block is larger, like the fog, the coast and the clouds.
- A region can carry a banner (`BannerDesign`, the loom-style editor already in the mod), planted at
  the region's label like a heraldic standard.
- Client-side and private, like waypoints. Shown on the world map and, optionally, on the minimap.

What MapScribble has that is out of scope for the first version: open freehand lines and free text
notes. They fit the same storage and could come later.

## How it is today

- Waypoints are world positions (`Waypoint`), saved per world in `waypoints.json` by `WaypointStore`
  (tolerant per-entry loading, atomic write through a `.tmp` file), drawn by `WaypointOverlay` through
  `Projection.u/worldV`, which already follows the map direction.
- `MapPick.standingSpot` finds the block a map pixel shows (the map is oblique: a screen point alone
  doesn't give the height). `MapScreen.target` falls back to the player's height where the map has no
  ground.
- Effects over the map (`MapEffect` CLOUDS and COAST, ≥1.21.6) are shader passes over a CPU-built
  mask: `CoastMask` rasterises a 512² map-space mask with an exact Euclidean distance transform, its
  origin snapped to 64 texels so panning rebuilds it only now and then, and `coast.fsh` draws 1 art
  pixel lines from the distance gradient.
- The book (`MapBook`) has ribbons Banners, Look, Layers, Radar, Minimap; Banners has List/Options
  tabs. `MapMenu` is the right-click card. `BannerEditScreen` and `BannerAtlas` draw and edit banners.

## Data model

`dev.diorama.region` (common code, no loader APIs):

- `MapRegion` (mutable, like `Waypoint`): `UUID id`, `String name`, `String dimension`,
  `int dye` (a `BannerDesign` dye index: the 16 dyes, so a region and its banner share a palette),
  `Fill fill` (`NONE`, `LIGHT`, `NORMAL`, `STRONG`), `List<Ring> rings`, `@Nullable BannerDesign banner`,
  `boolean visible`, `boolean onMinimap`, `boolean showLabel`, `long created`.
- `Ring`: the outline as world coordinates, `float[] x`, `float[] z` (half-block precision after
  simplifying) and `short[] y`, the ground height under each vertex when it was drawn.
  A region is a list of rings filled by the even-odd rule, so holes and several parts need nothing
  more. Rings are simplified (Douglas–Peucker, 0.5 block) and capped at 4096 vertices per region.
- Derived, not saved: bounds, area in blocks, the label anchor (pole of inaccessibility,
  "polylabel", so the name sits inside even C-shaped regions), recomputed on change.

Why world coordinates and not map pixels: they survive the four directions (each has its own map,
the regions are the same), the overview levels, the pixel-grid changes of the past and future
render styles.

### Heights and the oblique view

The map shows the ground at an angle (`v = z·¾ − y·¾`), so a world outline must be projected at
some height. Each vertex keeps the height of the ground the player pointed at: the cursor gives a map
point `(u, v)`, `MapPick.standingSpot` gives the block shown there, and the vertex is that point at
that height (`x` from `u`, `z = Projection.zAt(v, y)`). The outline is thereby draped over the
terrain where it runs, so it lands exactly where the player drew it.

Where the map knows no ground, or the ground is under fog of war, the vertex takes the height of
the nearest known vertex of the same stroke, else the player's height (as `MapScreen.guessedHeight`).
Fog of war is visual security: hidden terrain must not bend the outline, so `MapPick` is only asked
where the pixel is explored.

Known limit: the fill is the projected outline, so a mountain inside a region pokes out of the fill
near the region's north edge (by up to its height above the border, in ¾-units). Documented, not
modelled; an exact fill would need a world column per map pixel, which the tiles don't keep and which
isn't worth ~768 KiB per tile.

Cave layers: regions are drawn at the slice height (`y` replaced by the layer), border only.

### Storage

- `regions.json` next to `waypoints.json` in `WorldKey.folder` (`saves/<world>/diorama/` in
  singleplayer, `.minecraft/diorama/<world>/` on servers), so `MapFolders.adopt` moves it too.
- `{"version": 1, "regions": [...]}` through Gson (`RegionJson`), rings as compact arrays. Same
  tolerant loading as `WaypointStore`: a bad entry costs only itself and the file is copied aside
  before the next save. A file with a newer `version` is read but never overwritten (editing is
  disabled with a notice), so a downgrade can't destroy it.
- Atomic save (`.tmp` + `ATOMIC_MOVE`), once per finished operation (never during a drag), debounced
  to ~1 s for bursts like undo/redo.
- Banners serialise with the existing `BannerDesign` JSON.

## Drawing the regions

### The mask (CPU)

`RegionMask`, built like `CoastMask` per view (world map and minimap each own one):

1. Regions of the dimension whose bounds (projected) touch the view plus a margin; smaller regions
   last, so a region inside another stays visible.
2. Each ring is projected to map space (`Projection.u/worldV` per vertex) and rasterised by scanline,
   even-odd, into a map-space grid with the same texel and origin snapping as the coast (finest = one
   map pixel, coarser when zoomed out, 512²).
3. Distance to the region's own edge, inside it, by the exact EDT already in `CoastMask`, pulled out
   into a shared `DistanceField` helper.
4. Texel: RGB = the region's dye colour, A = 2 bits of fill strength + 6 bits of square-rooted
   distance (like the coast's encoding). Outside every region A = 0. No palette texture is needed.

Rebuilt only when the snapped origin, the texel size, the direction, the layer or a region changes
(a revision counter on `RegionStore`). Building is O(texels + vertices); at 512² it is in the same
budget as the coast mask, so it stays on the render thread like it; if it ever measures over ~2 ms it
moves to a worker, keeping the previous mask until the new one is ready.

### The pass (GPU, ≥1.21.6)

A new `MapEffect.REGIONS` with `shaders/core/regions.fsh` and `DioramaPipelines.REGIONS`, through
`MapCanvas.effect` (mask in sampler 0, the fog's noise in sampler 1, the parameters — look, texel
size, art pixel size, origin modulo the noise period, daylight — in the vertex colour).

Two draws of the same pass per frame:

- **Under the fog** (after the coast, before the fog): fill and border.
- **Over the fog**: the border only, where the fog covers it, at ~60%, so borders run on across
  unexplored land as on an old map, while the fill never paints over the unknown. Under the clouds
  effect, so drifting clouds still cross them.

Patterns are indexed by map-space pixel (like `PaintedFinish`), so nothing swims while panning.
Lines are drawn from the distance gradient at exactly one art pixel, like the coast's foam.

Before 1.21.6: the Plain look only, from a second flat texture built from the same raster (fill
colour × alpha, border texels opaque), drawn as an ordinary textured quad.

### Looks per render style

The look follows `Settings.renderStyle`; the two antique fog styles take over, because they recolour
the whole map as a period map (see "Decisions").

| Look | Border | Fill |
|---|---|---|
| **Plain** | 1 art px solid line in the dye | flat translucent dye (light 15%, normal 30%, strong 50%) |
| **Outlined** | 2 art px: outer line in dark ink (dye × 0.35), inner line in the dye | flat dye, plus a 3 art px band of stronger dye along the inside of the edge (a political map) |
| **Painted** | a thin sepia ink line (`PaintedFinish` ink, `#5A2E22` family) that wobbles ±1 art px with world-anchored noise, as if drawn by hand | watercolour wash: alpha broken by paper grain (the fog noise), pigment pooling darker in the outer 4 art px |
| **Painted without outlines** | none | the same wash with stronger pooling, which reads as the edge |
| **Antique map** (sepia) | dash-dot ink line (the period border "– · – ·") | diagonal hatching in the dye run through the fog's `sepia()` ramp when `antiqueTint()`; a hand-tinted band 5 art px wide along the edge |
| **Antique map (colour)** | dash-dot ink line | the hand-tinted band in the full dye, interior left almost bare (as hand-coloured 18th-century maps) |

`Fill NONE` keeps the border (and the band, where the look has one).

### Labels and banners (CPU, over the passes)

Drawn after the fog and before waypoints, in screen space:

- The name at the label anchor, only when the region is wide enough on screen to hold it, fading out
  below that. Plain and Outlined: the vanilla font with shadow in the dye's readable text colour
  (`WaypointOverlay.textColour`). Painted and antique: ink colour, the antique looks in spaced
  capitals, the way old maps letter provinces.
- With a banner: the banner (`BannerAtlas`, map size, waving like waypoints) planted above the name,
  the pole's foot on the anchor. With the region small on screen, only the banner (small size) is
  drawn; much smaller, nothing. Sepia banners on the antique map follow `sepiaBanners`.
- Hovering a region (the mask texel under the cursor, read back from the CPU raster, so no GPU
  readback) brightens its border and shows the name plaque even when the label is hidden.

### Minimap

Fill and border with the same look, no labels, banner only when the label anchor is in view, for
regions with `onMinimap` and with the global `minimapRegions` on. Optional: a short title "Entering
<region>" when the player crosses into a region (`regionEnterNotice`, off by default), from the CPU
point-in-region test once a second.

## Drawing tools

A **region mode** on `MapScreen`, entered from the Regions ribbon ("New region"), the right-click
card ("Draw a region here") or an unassigned key binding. A small parchment toolbar at the top
(`BookSprites`, like `MapMenu`'s card) holds the tools, the 16 dyes, the fill strength, undo, redo
and Done.

| Key | Tool | Use |
|---|---|---|
| 1 | Lasso | drag to trace; releasing closes the outline. Tiny outlines (< 4 blocks²) are discarded |
| 2 | Polygon | click vertices; click the first one, double click or Enter to close; Backspace removes the last |
| 3 | Edit | drag vertices; click an edge to add one; right-click a vertex to remove it; drag inside to move the region |
| 4 | Eraser | click a region to delete it (undoable) |

- While drawing, the left button belongs to the tool: panning moves to the right or middle button,
  the wheel still zooms, Space still recentres. Outside region mode nothing changes.
- The stroke is sampled in screen space every 2 GUI px, turned into world vertices on release
  (heights through `MapPick`), simplified, and committed as one operation.
- Shift while closing a lasso or polygon adds it to the selected region, Alt subtracts it (see step 7).
- `RegionHistory`: Ctrl+Z, Ctrl+Y and Ctrl+Shift+Z, 100 operations, cleared when the world or
  dimension changes. Operations are value snapshots of the changed region, not diffs.
- After closing a new outline the book opens at the Regions section with the region selected and its
  name being edited, as a right-click does for waypoints.
- Map keys (C, PgUp/PgDn, the map key) never fire with a text field focused; Esc leaves region mode
  first, then shuts the book, then the map.

## The book

A new ribbon **Regions** (purple, `minecraft:feather`, the cartographer's quill) between Banners and
Look, with tabs:

- **List** (`minecraft:feather`): the regions of the dimension sorted by distance to the player, each
  slot showing its banner or a dye swatch; "New region"; a hidden filter.
- **Detail**, on the right page: name, the 16 dyes, fill strength, area (blocks and chunks), banner
  (preview; "Edit banner…" opens `BannerEditScreen` and returns to the book; "From a waypoint…" copies
  a waypoint's design; "From the banner in hand" through `BannerPatternCatalog`; "Remove"), show on
  the minimap, show the label, Centre on the map (`SmoothZoom`), Edit shape (region mode with the Edit
  tool), Hide/Show, Delete with a confirming second click. Choosing a banner whose base differs from
  the region's dye offers to adopt the base dye.
- **Options** (`minecraft:loom`): `mapRegions`, `minimapRegions`, `regionLabels`,
  `regionEnterNotice`, declared in `SettingsCatalog` (new `Section.REGIONS`) so `SettingsScreen` and
  Mod Menu list them too.

Clicking a region's label or banner on the map opens it in the book (in region mode, the tool acts
instead).

## Architecture summary

New code:

- `region/MapRegion`, `region/Ring`, `region/RegionStore`, `region/RegionJson`,
  `region/RegionGeometry` (simplify, area, even-odd contains, polylabel, scanline raster),
  `region/RegionHistory`, `region/RegionTool` (the tools' state machine, pure, testable).
- `render/RegionMask` and `render/DistanceField` (shared with `CoastMask`), `render/RegionLabels`.
- `MapEffect.REGIONS`, `DioramaPipelines.REGIONS`, `shaders/core/regions.fsh` (+ the pipeline JSON
  where older targets need it).
- `ui/RegionsPage`, the toolbar `ui/RegionToolbar`, `MapBook.Section.REGIONS`, `MapMenu` entries.
- Lang keys in `en_us.json` and `pt_br.json`.

Threads: everything runs on the client thread except, if measured necessary, the mask build. Workers
get an immutable copy of the projected rings. No new texture per region: one mask texture per view
(two on <1.21.6), the existing banner atlas.

## Steps

1. **Model and storage**: `MapRegion`, `Ring`, `RegionJson`, `RegionStore`, `RegionGeometry`.
   JUnit: round trip, damaged entry, newer version kept read-only, simplify, even-odd with a hole,
   polylabel inside a C shape.
2. **Drawing, Plain look**: `DistanceField` pulled out of `CoastMask` (coast unchanged, its tests
   pass), `RegionMask`, `regions.fsh`, the two draws around the fog, minimap; a debug command
   `/diorama region add-test` until the tools exist. JUnit: raster of a projected ring per direction,
   encoding.
3. **Tools**: region mode, lasso and polygon, heights through `MapPick` respecting fog of war,
   eraser, undo/redo, toolbar, key bindings. JUnit for `RegionTool` (closing, discarding, panning
   remap).
4. **Book**: Regions ribbon, List/Detail/Options, right-click card entries, Edit tool.
5. **Looks**: Outlined, Painted, Painted without outlines, Antique and Antique colour; labels per look.
6. **Banners**: the banner on the region, the editor round trip, copying from a waypoint or the hand,
   drawing at the anchor, hover.
7. **Extras**: add/subtract (rasterise both at block resolution, combine, trace back with marching
   squares, simplify — no geometry library), a chunk brush that paints whole chunks (claims), the
   "Entering <region>" title.
8. **Docs and tests**: README, this file's "How it turned out", `DIORAMA_TESTS=regions` with captures
   of each look, the minimap, the book page and a drawing flow; build Fabric and NeoForge 26.3 and one
   <1.21.6 target for the fallback.

Steps 1–3 give a usable feature in the Plain look; 5 and 6 carry most of the identity.

## Decisions to confirm

- **Antique fog styles override the render style for regions.** Recommended, since with the antique
  map the whole map reads as a period map. The alternative is following only `renderStyle` and
  tinting with sepia.
- **Borders over the fog, fill under it.** Recommended; the alternative hides regions in unexplored
  land entirely.
- **Banner owned by the region** (a copy of a design) rather than a link to a waypoint. Recommended:
  deleting a waypoint shouldn't strip a kingdom of its standard; "From a waypoint…" covers reuse.
- **Freehand lines and text notes** (MapScribble's other tools): left for later.

## Compatibility and risks

- Saved maps are untouched; `regions.json` is a new file, ignored by earlier versions.
- Targets below 1.21.6 get the Plain look only (no effect pipelines); the banner and labels work
  everywhere banners already do.
- The pixel-perfect view (`View.pixelPerfect`) snaps the mask's screen mapping with the tiles, as the
  coast does, so borders don't shimmer while the zoom slides between steps.
- Very large regions (thousands of blocks across) cost only their projected outline per rebuild;
  the vertex cap keeps hand-drawn ones small.
- Multiplayer: client-side only, nothing sent to servers, per server folder like waypoints. Sharing
  regions (a file export or a chat link) is not planned.

## How it turned out (steps 1–3)

- Classes: `region/MapRegion`, `region/Ring`, `region/RegionGeometry` (bounds, even-odd area and
  inside test, polylabel, Douglas–Peucker on the half-block grid, scanline fill, `fromMap`),
  `region/RegionJson`, `region/RegionStore` (with the undo/redo history), `region/RegionTool` (the
  lasso and polygon strokes, pure), `render/RegionMask`, `shaders/core/regions.fsh`,
  `MapEffect.REGIONS`/`DioramaPipelines.REGIONS`, and `ui/RegionMode` (the mode, its toolbar and
  the stroke preview). `WaypointJson` now shares its UUID and banner adapters.
- No `DistanceField`: the mask measures each texel's distance to the projected outline exactly
  (segment by segment, in pieces no longer than the 8-texel band it cares about), instead of a
  distance transform over the raster. The shader reads it smoothed between texels, so a region's
  outer edge falls between texels and its line stays smooth at every zoom; where two regions meet
  the line follows the texels. `CoastMask` is unchanged.
- Mask texel: green holds the signed distance (eighths of a texel around 128), red the dye, fill,
  highlight and inside bits, blue a tag per region. Texels just outside a region carry its dye and
  tag, so the smoothed edge has a colour on both sides. Regions are filled biggest first.
- The border is the art pixel inside a region with a 4-neighbour art pixel in another region or
  none (one GUI pixel, or a block zoomed in, as the fog). The pass over the fog draws those lines
  alone at 55%, everywhere; over explored land it lies on the same line drawn below.
- Before 1.21.6 a plain texture (fills, edges solid at texel resolution) is drawn as a quad with
  linear sampling.
- Saving is immediate after each operation, as waypoints do; no debounce was needed.
- Heights: a stroke point takes the ground's height only where its coverage cell is explored and
  `MapPick` finds ground; otherwise the nearest known point's along the stroke, else the player's.
- Entry points until the book has its ribbon (step 4): "Draw a region" on the right-click card,
  and an unbound key binding (opens the map with the tools on, or toggles them). The toolbar sits
  on a parchment strip at the bottom centre: lasso (lead), polygon (tripwire hook), eraser (brush),
  the 16 dyes, the fill, undo/redo (the book's arrows) and Done. Keys 1–3 pick the tools, Enter
  closes a polygon, Backspace removes its last corner, Escape lets the stroke go, then leaves the
  mode. Opening the book or a cave slice leaves the mode. New regions are named "Region N".
- Settings: `mapRegions` (Layers page) and `minimapRegions` (minimap settings) for now; they move
  to the Regions ribbon's Options in step 4.
- Tests: `RegionGeometryTest`, `RegionStoreTest`, `RegionToolTest` (JUnit) and
  `DIORAMA_TESTS=regions` (captures `regions-zoom-*`, `regions-lasso-*`, `regions-polygon-*`,
  `regions-eraser-pointing`, `regions-erased`, `regions-minimap`).

## How it turned out (step 4)

- `MapBook.Section.REGIONS` (purple, feather) between Banners and Look, with List (feather) and
  Options (cartography table) tabs. `RegionsPage`: rows on the left page (dye square, name, area),
  nearest the player first, paged with the book's arrows, and "New region"; the right page has the
  name written in place, the 16 dyes (`DyeSwatch`, shared with the toolbar), fill, on the map, on
  the minimap, the area in blocks² and chunks, Centre, Edit shape and Delete (a second click
  confirms). Banners and labels on the page wait for steps 5 and 6.
- Every change from the page is one step of the store's history, so Ctrl+Z on the map takes it
  back; the name is written as one step when the region is left (another region picked, the
  section changed, the book shut).
- Options: a `SettingsCatalog.Section.REGIONS` with `mapRegions` (moved from Layers), the
  minimap's `minimapRegions` offered beside it, and a paragraph on the tools. The settings screen
  lists Regions under Banners.
- A region just drawn opens the book on it with its name field taking the keyboard, as planned;
  shutting that book goes back to drawing, so several regions can be drawn in a row.
- The edit tool (key 3; the eraser moved to 4, sponge icon; edit is the brush): a click picks the
  region; its corners show as small paper squares (where they crowd closer than 6 px only some are
  drawn, all can be taken); a corner dragged follows the ground under the pointer; a click on a
  side adds a corner there; a right click takes one away (the last three of a ring stay, or the
  ring goes if the region has others); a drag inside moves the whole region along the ground, each
  corner settling on the ground under it as it is let go. The map draws the change as it is made
  (`MapViewport.preview`), and it is kept as one step on letting go. Coordinates stay on the
  half-block grid.
- Clicking a region on the map, outside region mode, opens it in the book (labels are step 5); the
  pointer over one shows its name. The region the book shows, the edit tool's and the eraser's is
  drawn brighter.
- `DIORAMA_TESTS=regions` now also names the lasso's region in the book, undoes and redoes it, edits
  Home (corner, removal, move, three undos) and uses the book (click on the map, dye, delete,
  Options): captures `regions-naming`, `regions-edit-*`, `regions-book`, `regions-book-options`.

## How it turned out (step 5)

- The looks are in `regions.fsh` as planned (plain, outlined, painted, painted without ink, antique,
  antique colour), with these departures:
  - At the user's request the style is no longer tied to the render style: `RegionStyle` (AUTO plus
    the six), a map setting `regionStyle` (AUTO follows the render style, or the antique fog, as the
    plan's decision had it) and a style of each region's own (`MapRegion.style`, AUTO takes the map's;
    "Style" on the region's page). The mask carries each texel's resolved style in its alpha, so
    regions of different styles sit side by side; the pass's parameters no longer carry a look.
  - Also at the user's request, a border band as strategy games draw territories (`regionBand`:
    off, narrow 4 art pixels, wide 8): the dye strongest at the border (72% narrow, 68% wide),
    fading inwards with the distance to the power 1.6, laid over whatever style. After seeing it in
    game the user asked for it on by default (wide) and for the band to be the only colour inside
    while it is on, so the region's fill is left out then; the styles' own edge work (the outlined
    band, the painted pooling, the antique band) stays.
  - So bands fit, the mask measures distances up to 31 texels (quarters of a texel in green).
  - The antique style's band is always in sepia; the colour one keeps the dye.
  - Painted: the pen line is broken where a noise is low and wavers one art pixel inwards where
    another is high; the pooled edge stays with no fill, so a region with no fill still reads.
  - Plain and outlined lighten dark dyes for their lines, so black and grey regions show on dark
    ground.
- Labels (`RegionLabels`): at `MapRegion.anchor()` (polylabel, and the mean height of the corners),
  fading in as the region's span on screen makes room for the name; biggest regions first, a name
  overlapping one already placed is left out. Plain and outlined: the game's font with its shadow,
  in the dye (lightened if dark); painted: ink with a shadow of paper a pixel down and right (a
  full halo made thin letters read as pale ones); antique: spaced capitals in old ink, the same
  shadow. `regionLabels` setting and "Name on the map" per region. World map only.
- The detail page dropped its area line (the list shows it) to fit the style stepper.
- Tests: `RegionLookTest` (JUnit); `DIORAMA_TESTS=regions` captures `regions-look-*` (each style,
  near and far), `regions-look-chosen` (a chosen style over the plain render, one region of its own
  style) and `regions-band-*`.

## How it turned out (step 6, and the styles reworked)

- At the user's request the styles were reworked: they are chosen in one place only, the Regions
  section's Options page, as pictures (`BookSprites.regionThumb`, drawn by `book-sprites.py` at
  y 168: a red region on a patch of ground in each style), and they never follow the render style
  or the fog any more. No AUTO, no style per region (`MapRegion.style` is gone; a `style` field
  left in `regions.json` by the development builds is ignored). Renamed apart from the render
  styles: Marker, Political, Watercolor, Wash, Engraving, Illuminated (Marcador, Político,
  Aquarela, Aguada, Gravura, Iluminura); the default is Watercolor, and a setting saved under an old
  name reads as it. The mask still carries the style per texel.
- Banners (step 6): `MapRegion.design()` is the region's banner, or a plain one in its dye. On the
  world map a region with a banner of its own has it planted at its label point, its name under
  the foot; too small for its name, the small banner alone; smaller, nothing (`RegionLabels`). The
  sepia banners option tints it under the Engraving style. In the book the banner stands beside
  the name (a click on it, or the Banner button, turns to its page): large, with Loom…, Banner in
  hand (`BannerPatternCatalog.fromInventory`), No banner, Use its dye (when the base differs), and
  the waypoints' banners nearest first to copy one. Each is one step of the history.
- At the user's request, the region the player stands in shows under the minimap on the side away
  from the coordinates and biome: its banner (or the plain one) and its name, cut to the room left
  (`RegionStore.containing`, the smallest region shown on the minimap; off with `minimapRegions`).
- Tests: `RegionLookTest` reworked; `DIORAMA_TESTS=regions` captures `regions-look-<style>`,
  `regions-banner-map`, `regions-banner-far`, `regions-book-home`, `regions-book-banner` and the
  minimap with Home under it.

