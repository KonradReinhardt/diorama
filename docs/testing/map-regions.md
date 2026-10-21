# Map regions validation

Run on 2026-10-21 on Minecraft 26.3, Fabric, JDK 25 and OpenGL, on the `feature/map-regions` branch,
with `DIORAMA_RENDER_DISTANCE=8`. See [the design](../design/map-regions.md).

## Unit tests

`./gradlew --configure-on-demand :26.3:build` runs them all; the regions' own:

- `RegionGeometryTest`: area with a hole and with separate parts, the even-odd inside test, the
  label inside a C shape, simplifying a shaky square to its four corners on the half-block grid,
  scanline filling with a hole, a stroke landing on the ground it was drawn over, unknown heights
  taking the nearest known one, slips of the mouse refused, editing a ring, distance zero inside.
- `RegionStoreTest`: a save and load round trip with a banner, undo and redo and their history kept
  per dimension, a bad entry costing only itself (and the file copied aside), a newer file shown but
  never written over, rings written compactly.
- `RegionToolTest`: the lasso's spacing, a short lasso refused, the polygon closed on its first
  corner, by a double click, Backspace and Enter.
- `RegionShapesTest`: adding overlapping squares into one L, cutting a bite, cutting a hole, squares
  touching at a corner kept apart, neighbouring chunks merged into one square keeping their heights,
  cutting everything away.
- `RegionLookTest`: the region style apart from the render style, three bits for the shader, a
  region without a banner showing a plain one in its dye, dark dyes lightened for names.
- `SettingsCatalogTest` and `BookLayoutTest`, updated for the Regions section and its ribbon.

## Client scenario

`DIORAMA_TESTS=regions ./gradlew --configure-on-demand :26.3:runClientGameTest` lays five regions
around the player (one with a courtyard cut out, one C-shaped, two that meet, one with no fill, a
small one inside another), on the ground under the leaves, and then:

- captures them at zoom 4, 2, 1 and 0.25, and checks the map finds Home where it was laid;
- captures each style, Marker, Political, Watercolor, Wash, Engraving and Illuminated, near and far,
  then the border band narrow and wide, and Home's banner over its name near and far;
- turns the region tools on through the right-click card, draws a lasso by mouse (its area within
  15% of the circle traced), names it in the book that opens with the name field focused, shuts
  the book and is back in the tools; Ctrl+Z takes the name back, then the region, and Ctrl+Y
  brings both;
- draws a polygon with key 2, closing it on its first corner;
- with key 3 picks Home, drags a corner out (Home grows), takes a corner away with a right click,
  drags Home east by the distance dragged, and takes the three changes back with Ctrl+Z to the
  exact area it had;
- with Shift, a lasso over the lake's edge grows the lake and draws no region of its own; with Alt,
  a lasso inside Home cuts a third ring into it;
- with key 5, the chunk brush paints whole chunks over empty ground into a new region (at least the
  three aimed at; see below), and Alt with a click of it takes one chunk away;
- with key 4, the eraser highlights Farms under the pointer and erases it; Escape leaves the tools;
- clicking the bay on the map opens it in the book; a dye on its page dyes it; Delete, then Confirm,
  deletes it; Home's page and its banner's page open, and the regions' Options;
- shows the minimap, with Home, where the player stands, named under it with its banner.

It passed. Captures: `regions-zoom-*`, `regions-look-*`, `regions-band-*`, `regions-banner-*`,
`regions-lasso-*`, `regions-naming`, `regions-polygon-*`, `regions-edit-*`, `regions-shapes`,
`regions-brush-*`, `regions-eraser-pointing`, `regions-erased`, `regions-book*` and
`regions-minimap`, in `versions/26.3/build/run/clientGameTest/screenshots/`.

### What the captures showed and changed

- Black regions read poorly on dark forest: the Marker and Political lines lighten dark dyes.
- A full paper halo around painted names made the letters read as pale ones; it became a shadow.
- The lasso and edit drags drawn as planned; moving a region first left its outline jagged over a
  forest, where each corner took the crowns' height but kept its place: corners now settle as a
  stroke drawn there would, keeping the outline's shape on screen.
- With the border band on, the fill still tinted the inside; at the user's request the band is now
  the only colour inside.
- The minimap's biome line ("Old Growth Birch Forest") left no room for the region's name beside
  it: the region goes on a row of its own under the lines then.

## Cost

The scenario logs the region mask's builds. On the render thread at first, they took 12.8 ms on
average (64 builds), far over a frame's share: every pan past the snap, zoom step, edit and frame of
an edit drag paid it. The mask is now built on a worker of its own from outlines projected on the
render thread, the last mask drawn until the next is ready, and distances are measured only as far
as the bands reach at the view's zoom: 6.2 ms on average on the worker (63 builds, 389 ms in all),
none of it on the render thread.

## Other scenarios run

`book-map`, `banner-waypoints`, `waypoints` and `reveal-region` passed beside it (the book's new
ribbon, banner sepia, the right-click card and clicks on the map). `settings` first found the full
settings screen's second column, with the Regions section, running into Done at 427×240; the screen
now lays out in four columns where three don't fit, and `settings`, `screens` and `modmenu` (with
`-PdioramaModMenuTest=true`) passed.

## Limits

- The brush takes the chunk of the block the map shows under the pointer: over a forest the crown of
  a tree in front can stand for a chunk beside the one aimed at, as it looks. The scenario checks
  whole chunks and at least three, not the exact three.
- A region's fill is its outline projected at its corners' heights: a hill inside a region near its
  north edge can stand out of the fill (see the design).
- Before 1.21.6 regions are drawn flat (fill and edges at texel resolution), without the styles,
  the band or the lines over the fog; built for 1.21.1, not run.
- Multiplayer: regions are client-side only and never sent.
