# A book interface over the world map — plan

Status: plan written on 2026-10-20 and revised the same day after the Painted render style and the
sharpness fix (`feature/painted-style`), and again after the pixel-perfect grid, Painted without
outlines and the option to tint the explored map on the antique map (`feature/pixel-grid`).
Implemented on 2026-10-20 on the `feature/book-map-ui` branch (steps 1–8); the "How it turned out"
section at the end records where the implementation departed from the plan. Interface sounds and the
hand pointer over clickable controls were added for 1.0.0 (see "After 1.0.0").

## Goal

Recast the world map's menus as an immersive pixel-art book interface (warm parchment pages, brown
ink outlines, a leather cover, gold corners), while still showing the map full screen:

- The map still takes the whole screen. The menus are a book opened over it.
- Navigation is through coloured side bookmarks (ribbons), each with the icon of a Minecraft item
  drawn by the game's item renderer, so resource packs change the icons.
- Options and waypoints/banners are in separate sections of the book.
- Settings that affect the world map are changed inside the world map itself, without switching
  screens. Only settings exclusive to the minimap go to another screen.
- A short zoom animation as the world map opens.

The reference image sets the aesthetic, not the pixels: every sprite is the mod's original art.

## How it is today

- `MapScreen`: the map full screen, a black status strip at the top, a help strip at the bottom and a
  vanilla "Settings" button in the top right corner that switches to `SettingsScreen(this)`. Clicking
  a banner opens `WaypointEditScreen`; a right click creates a waypoint; the U key opens
  `WaypointListScreen`; C, PgUp/PgDn and Space control caves, the slice and recentring.
- `SettingsScreen`: every option in three columns of vanilla buttons that cycle values, with a compact
  layout for 427×240. It is also the screen for Mod Menu and the settings key. Under Look, the old
  "Outlines" toggle became the **Render** cycle (`Settings.renderStyle`:
  `PLAIN`/`OUTLINED`/`PAINTED`, default `OUTLINED`, with a tooltip); old configs migrate from the
  private `outlines` field, which is no longer written.
- The **Painted** style (`docs/design/painted-style.md`): terrain becomes a hand-painted fantasy map at
  bake time (`TileBaker` + `PaintedFinish`): flat two-tone light, subtle sepia ink, foamy coasts,
  water in washes, brushstrokes and paper grain. Switching Render redraws the whole map in the
  background through the existing atomic refresh, without losing exploration.
- Map tiles are magnified with NEAREST whenever a texel covers ≥ 1 screen pixel
  (`MapViewport.magnified`, which counts the GUI scale and the overview level); below that, trilinear
  mipmaps. The map used to be blurry at 2× or 3× GUI.
- `WaypointListScreen`: a list with Edit/Hide/Delete/TP and the Xaero's import.
- Reusable pieces already exist: `Spring` (spring animation), `BannerAtlas`/`BannerCompositor`
  (pixel-art banners), `PaperFrame` (reading resource pack textures), `SmoothZoom`.

## How the book is organised

### States

- **Shut** (the default as the map opens): the map free and full screen; only the ribbons show,
  coming out of the screen's right edge, like bookmarks sticking out of the pages.
- **Open**: the book (two pages) shows over the map; the ribbons now come out of the book's right
  edge, and the current section's ribbon is longer and "held" by the page.
- The book remembers the last section and the selected waypoint while the game is running (static
  state in memory, not saved to disk). Coming back from a child screen (the banner editor, the
  minimap settings) reopens the book at the same section, without repeating the opening animation.

### Bookmarks (ribbons)

| Ribbon | Colour | Item (icon) | Content |
|---|---|---|---|
| Banners | red | `minecraft:red_banner` | the waypoint list + waypoint and banner options |
| Look | blue | `minecraft:filled_map` | render (Plain/Outlined/Painted/Painted no outlines), fog style, tint explored (antique map), seed cartography, always fog, drifting clouds, foam and ripples, shading, quality, lighting, direction |
| Layers | green | `minecraft:lantern` | surface/cave, Y slice (− / +), chunk grid, slime chunks, spawn spots |
| Radar | yellow | `minecraft:spyglass` | players, hostile, neutral, passive (with the faces from `MobFaces`), range |
| Minimap | cream | `minecraft:compass` | leaves for the minimap's options screen (with a small "leave" sign on the ribbon) |

Besides the ribbons: an "X" at the book's corner (as in the reference) shuts the book, not the map.

Sections with more content than two pages use the tabs on the book's top edge (like the reference's
four tabs), also with item icons. Planned only for Banners: **List** (`minecraft:red_banner`) and
**Options** (`minecraft:loom`).

### Sorting the settings

Rule: everything that changes what the world map shows stays in the book, including what also
applies to the minimap (radar, overlays, look, waypoints). Only what is exclusive to the minimap
leaves the map:

- **In the book**: `renderStyle`, `fogStyle`, `antiqueTintsExplored`, `seedPreview`, `alwaysFogOfWar`,
  `mapClouds`, `mapCoast`, `depthShading`, `quality`, `lighting` (when
  `EnvironmentLighting.supported()`), `mapDirection`, `chunkGrid`, `slimeChunks`, `spawnSpots`,
  `radar*`, `waypointsOnMap`, `waypointsInWorld`, `deathMarkers`, `animateBanners`, `sepiaBanners`,
  `extraBannerLayers`.
- **Outside (the Minimap ribbon)**: `minimap`, `minimapSize`, `minimapCorner`, `minimapFrame`,
  `minimapZoom`, `compass`, `coordinates`, `biome`, `autoCaves` (to confirm while implementing whether
  `autoCaves` affects the world map's starting layer through `MapSession.autoLayer`; if it does, it
  goes to Layers).

Changes apply live: the book calls `Settings.save()` as today, and `MapSession` still gathers quality
and look refreshes into the next tick, keeping exploration and resident textures through the existing
barriers. No extra rebake for being in the book. `quality` no longer redraws the map (with the grid,
one ray per pixel in both modes; only the fog changes), and `antiqueTintsExplored`, `fogStyle`,
`mapClouds`, `mapCoast` and `lighting` are composition only, so they change at once. `renderStyle` and
`depthShading` redraw the whole map in the background with every change, so the book only applies
them on click (no previewing on hover) and the Render cycle mustn't go through intermediate values:
the Render slots pick the value directly.

### What the pages hold

- **Banners / List**: a left page with a 4×3 grid of slots (like the reference), one banner per slot
  (`BannerAtlas`, small size), sorted by distance and paged; a bottom area with "New", a hidden filter
  and "Import from Xaero's (N)" when there is something to import. A right page with the selected
  waypoint: a large waving banner, the name editable right on the page, coordinates, distance and
  actions (Centre on the map, Edit banner, Hide/Show, Share, Teleport with permission, Delete with a
  confirming second click).
- **Banners / Options**: the six waypoint/banner options.
- **Look**: render as four slots with still thumbnails (Plain, Outlined, Painted, Painted no outlines),
  at the top of the page since it is the most visible choice; fog style as three slots (Clouds,
  Antique map, Antique map (color)), with the "tint explored" toggle right below, disabled with a hint
  when the style is Clouds (it only applies to the two antique maps); direction as a four-slot compass
  rose; the rest as toggles and cycles. The thumbnails are fixed sprites generated by
  `book-sprites.py` from `DIORAMA_TESTS=painted` captures reduced by hand to pixel art, not live tiles.
  Painted suits the book (sepia ink, warm paper): the book palette's ink colour `#5A2E22` can be
  checked against `PaintedFinish`'s `INK` so both look like the same pen.
- **Layers**: surface/cave mode and the Y slice with buttons (the mouse equivalent of C and
  PgUp/PgDn); overlays. Slime chunks shows disabled with a hint when there is no seed.
- **Radar**: four categories with icons, and the range.

The loom-style banner editor (`BannerEditScreen`) stays a screen of its own, because it is too large
for a page; it comes back to `MapScreen` with the book open. `WaypointEditScreen` and
`WaypointListScreen` stay for use outside the map (the new waypoint key in the world, the list key
outside the map, existing tests).

### Working with the map

- The map stays interactive with the book open: dragging, scrolling and right-clicking outside the
  book work as today, to see the options' effect live. Events inside the book only go to the book's
  widgets.
- Clicking a banner on the map: opens the Banners section with it selected (instead of opening
  `WaypointEditScreen`). A right click on the map: creates the waypoint and selects it in the list
  with its name being edited.
- "Centre on the map" animates the view to the waypoint through the existing `SmoothZoom`/centre.
- Esc shuts the book first; a second Esc shuts the map. The U key (list) inside the map opens the
  Banners section. With a text field focused, the map's keys (C, Space, PgUp/PgDn, the map key) must
  not fire.
- The black status strip becomes a small parchment label in the top left corner (mode, coordinates,
  chunks, "baking"). The help strip becomes the tooltip of a discreet "?" in the bottom corner, shown
  by itself for a few seconds only on the session's first opening.
- The vanilla "Settings" button leaves `MapScreen`.

## Architecture

New code in `dev.diorama.ui` (common, without loader APIs):

- `SettingsCatalog`: a declarative list of the options (`key`, `section`, `scope` MAP/MINIMAP, values,
  getter/setter, value label, tooltip, availability). A single source for `SettingsScreen`, the book
  and Mod Menu, keeping the options shared as the project asks. `SettingsScreen` becomes built from the
  catalog, with an optional section filter (`new SettingsScreen(parent, Section.MINIMAP)` for the
  Minimap ribbon); without a filter it still shows everything for Mod Menu and the settings key.
- `BookLayout` (pure, testable): given the GUI size, returns the rectangles of the book, pages,
  ribbons, tabs and "X". A target of ~320×200 GUI px for both pages; below ~360×210 it uses a
  single-page mode with page-turning arrows. Everything in whole pixels.
- `MapBook`: state (shut/opening/open/shutting, section, tab, page, selection), input routing and
  drawing; `MapScreen` only delegates to it.
- Pages: `AppearancePage`, `LayersPage`, `RadarPage`, `BannersPage` (list + detail) and
  `BannerOptionsPage`, built from the catalog.
- Pixel-art widgets: `BookToggle` (an ink box/seal), `BookCycle` (‹ › arrows with the value),
  `BookButton`, `BookSlot` (a grid slot), `Ribbon` and `BookTab`. They extend `AbstractWidget`
  /`AbstractButton` to keep focus through Tab/arrows/controller and narration, like the current screens.
- `WaypointListModel`: sorting by distance, the TP permission and the Xaero's import pulled out of
  `WaypointListScreen`, used by the old screen and the book. `WaypointDraft`: validation of names and
  coordinates pulled out of `WaypointEditScreen` for inline editing.
- `BookSprites`: the single point of contact with GUI APIs that change between versions (`blitSprite`
  and drawing an `ItemStack` in the GUI), isolated with Stonecutter blocks, as `PaperFrame` does with
  `NativeImage`.

### Sprites and resource packs

- Sprites of its own in `assets/diorama/textures/gui/sprites/book/` (cover, left/right page, spine,
  slot, separator, base ribbon, tab, "X", toggle, arrows), with `nine_slice`/`tile` `.png.mcmeta` for
  the GUI atlas. Resource packs can replace each one.
- The ribbon is a neutral sprite tinted with the section's colour, so a pack replaces a single texture.
- The icons are real `ItemStack`s drawn by the item renderer: they follow resource packs' item models.
- The PNGs are generated by a reproducible script, `docs/design/book-sprites.py`, in the same spirit
  as `antique-sprites.py`, with the palette: page `#F5C99B`, slot `#EAB384`, ink `#5A2E22`, leather
  `#9C5233`, gold `#E8B83A`, saturated red/blue/green/yellow/cream ribbons.
- One texel = one GUI pixel, like the vanilla GUI. No new dynamic texture: only GUI atlas sprites and
  the existing `BannerAtlas` (with its limit of 6 compositions per frame).
- Vanilla book sounds (`BOOK_PAGE_TURN` when switching sections) for immersion; they respect the
  volume settings.

## Animations

All short, in real time and interruptible:

- **Zoom in on opening (~200 ms)**: a pure `OpeningZoom` class with a cubic ease-out from a factor of
  0.85 to 1 over the view's target zoom, anchored on the player (or the centre when not following).
  It starts at `max(minZoom, target × 0.85)`, so it never asks for a zoom below what the level
  supports. The slightly wider view at the start asks for at most ~38% more area, almost always
  already resident; no rebake. It only runs on the instance's first `init()` (not on resize nor when
  coming back from a child screen), ends at once on scrolling, dragging, `lookAt()` (tests) or a change
  of direction, and doesn't touch `SmoothZoom`'s target. The ribbons slide in from the edge over the
  same span. The drawn view only snaps to whole screen pixels per texel (`View.pixelPerfect`) when the
  zoom is at rest (`SmoothZoom.moving()` false); the opening must count as "moving" so it doesn't move
  in jumps, and the target must be a whole pixel step (as the wheel does when magnifying), so there is
  no "snap" at the end. If the factor crosses `MapViewport.magnified`'s limit (one texel = one screen
  pixel), sampling switches from trilinear to NEAREST mid-animation; check it in the captures.
- **Opening/shutting the book (~180 ms)**: `Spring.SOFT`: the book rises a few pixels and fades in.
- **Switching sections (~120 ms)**: the content slides slightly, like a page turning.
- **Ribbons**: hovering stretches the ribbon 3 px (`Spring.BOUNCY`); the selected one is longer.

## Steps

0. Before starting: banners, sharpness and Painted are already in `development` (merge `6d245c3`); the
   pixel-perfect grid, the whole-step zoom, Painted without outlines and the tint-explored option are
   in `feature/pixel-grid`, on top of it. The new `feature/book-map-ui` branch starts from
   `development` after merging `feature/pixel-grid` (or from it, if it isn't in yet), so the book uses
   the new options.
1. `SettingsCatalog` and `SettingsScreen` built from it, with no visual change; a section filter.
   JUnit tests of the catalog.
2. `BookSprites` with Stonecutter, sprites generated by the script, `BookLayout` and the book's
   widgets. JUnit tests of the layout.
3. `MapBook` in `MapScreen`: states, ribbons, input routing, Esc, removing the Settings button, the
   status label and help.
4. The Look, Layers and Radar pages from the catalog, with live effect.
5. The Banners section: `WaypointListModel`, `WaypointDraft`, the grid, the detail, inline editing,
   working with click/right click/centre, the Xaero's import, going to `BannerEditScreen` and back.
6. The Minimap ribbon: `SettingsScreen(MapScreen, MINIMAP)` and back with the book at the same
   section.
7. `OpeningZoom` and the book's animations with `Spring`.
8. Client tests, cross-compiling, documentation (the README, this file, `CLAUDE.md`, `en_us.json` and
   `pt_br.json`).

## Planned verification

- JUnit (`./gradlew --configure-on-demand :26.3:build`):
  - `SettingsCatalog`: every public field of `Settings` shows up once (the private legacy `outlines`
    field, only read during migration, is left out); the minimap's have MINIMAP scope; every
    translation key exists in `en_us.json` and `pt_br.json` (including `render_style.*` and
    `antique_tint`).
  - `BookLayout`: at 427×240, 640×360 and 960×540 nothing leaves the screen or overlaps; single-page
    mode kicks in below the limit.
  - `OpeningZoom`: starts at the factor, ends exactly at the target, is monotonic and stops when
    cancelled.
- A new client scenario, `DIORAMA_TESTS=book-map`: opens the map and waits for the zoom to end (the
  final zoom equals the target); captures each section; toggles a look option in the book and checks
  `config.json`, `MapStyle` and that the screen is still `MapScreen`; goes through the Minimap ribbon
  and back without repeating the zoom; a click on a banner opens the section with it selected; renames
  inline; repeats the overlap check at 1280×720; typing "c" in the name doesn't switch the layer.
- In `book-map`, switch Render to Painted through the book and check that `MapStyle.get().painted()`
  turns on, that the map behind still has coverage and that the screen is still `MapScreen`; toggle
  "tint explored" and check that `submittedJobs` doesn't change. Measure zoom and anchor through
  `MapScreen.heldView()`, because `view()` returns the view already snapped to whole pixels.
- Also run `settings-refresh` (which already toggles Painted), `painted`, `modmenu` (the Mod Menu
  screen stays complete) and `banner-waypoints`, which touch the changed screens.
- Compile the targets with different GUI APIs: Fabric 26.3, NeoForge 26.3, 26.1, 1.21.11, 1.21.6,
  1.21.4 and 1.21.1 (the signatures of `blitSprite` and of drawing items change between them).

## Risks and limits

- GUI APIs for sprites and items change between 1.21.1, 1.21.2–1.21.5, 1.21.6+ and 26.x: everything
  goes through `BookSprites`; check each target's mapped sources before writing the blocks.
- Small screens (427×240): the book covers almost the whole height; single-page mode avoids cramped
  text.
- The map keeps rendering in full behind the book; the extra cost is a few dozen sprite quads and up to
  12 banners from the existing atlas.
- `XaeroImport.find()` does I/O; today it runs in the list's `init()`. In the book, run it only when
  the Banners section first opens, and preferably on a worker, so it doesn't stall the render thread.
- Out of scope: the book style in the minimap's/Mod Menu's `SettingsScreen` and in `BannerEditScreen`
  (they can come later, reusing `BookSprites`).

## Open decisions

1. Clicking outside the book: proposed to keep interacting with the map (drag/zoom/right click)
   instead of shutting the book.
2. Editing a waypoint: proposed inline in the book, with only the loom editor on a screen of its own.
3. The minimap's options screen: proposed to keep the filtered vanilla screen for now, without the
   book style.

## How it turned out

- **Code**: `ui/SettingsCatalog` (the single list; each option has `shown` — the version supports it
  — and `active` — another option leaves it without effect, like the tint under Clouds), `ui/MapBook`
  (state, ribbons, animation, drawing, input), `ui/PageFlow` (spreads the content over the pages),
  `ui/BookPages` (Look, Layers, Radar, Banner options), `ui/BannersPage`, `ui/BookLayout`,
  `ui/BookWidget` (the base with every widget version block), `ui/BookWidgets`, `ui/BookSprites`,
  `ui/OpeningZoom`, `waypoint/WaypointDraft` and `waypoint/WaypointListModel`. `SettingsScreen` is
  built from the catalog, with the same three-column layout, and accepts just some sections in one
  column (`new SettingsScreen(map, Section.MINIMAP)`).
- **Sprites**: instead of GUI atlas sprites with `.mcmeta`, a single sheet,
  `assets/diorama/textures/gui/book.png` (256×256), generated by `docs/design/book-sprites.py` with no
  dependencies, drawn by `MapCanvas.blit` with its own nine-slice (fixed corners, repeated edges and
  middle, one texel = one GUI pixel). Why: `blitSprite` changes signature across four groups of
  versions, while `MapCanvas` already handles them all. Resource packs replace the whole sheet; the
  regions are listed in `BookSprites`. Cover and page are 64×64 so the middle costs few quads.
- **Thumbnails** of Render and of the fog style are drawn procedurally by the script (an island of 4×4
  blocks in each style), not reduced from captures.
- **Icons**: items looked up by id in the registry (`BookSprites.stack`), because in 26.x banners
  became a `ColorCollection` in `Items`. The Banners ribbon uses `white_banner`: red vanished on the red
  ribbon. Radar: `player_head`, `creeper_head`, `wolf_spawn_egg`, `cow_spawn_egg`. Layers:
  `grass_block` and `lantern` for the two views.
- **Drawing layers**: the book starts on a new stratum (`nextStratum()` from 1.21.6, `flush()` before),
  otherwise the map's text showed over the pages.
- **Layout**: tabs rise 18 px (the 16 px icon didn't fit in 14). Two pages of up to 160×196; below
  ~300 px of GUI width, one page at a time. The content is a flow: what doesn't fit moves to the next
  page and, past the open pair, to further sheets turned by the arrows at the bottom.
- **Shut**: a parchment label in the top left corner (mode, coordinates, chunks, "drawing") and a "?"
  seal with the controls in its tooltip; for the first 6 s of the session's first opening the controls
  show by themselves on a card. The black strip and the help line are gone.
- **Banners**: a right click on the map creates a draft in the book (Save/Discard), which only goes
  into `WaypointStore` on saving. An existing banner is edited in place: name and position apply with
  each valid keystroke (an invalid coordinate shows red) and are written when the selection or section
  changes, the book shuts or the map is left. "Centre" glides the view to it; "Delete" asks for a second
  click. With several Xaero's worlds, "From Xaero's…" opens the classic list to choose.
  `XaeroImport.find()` was split into `request()` (client state, main thread) and `find(Request)`
  (files, on a worker).
- **Animations**: a 200 ms zoom in on opening, from 0.85× to a whole pixel step (the starting zoom is
  snapped first, so there is no snap at the end); the book rises 10 px and fades in through
  `Spring.SOFT`; the ribbons slide from the edge to the book; hovering stretches the ribbon 3 px.
  Switching sections kept only the page sound (`BOOK_PAGE_TURN`), without the planned 120 ms slide.
- **Keyboard**: Esc shuts the book before the map; with a field focused, the map's keys (C,
  PgUp/PgDn, the map key, U) don't fire; Space presses the focused widget instead of recentring.
- **Translations**: `pt_br.json` now translates every key of the mod (it used to cover only part).
- **After the first delivery** (the same day, on request):
  - The Banners ribbon and tab draw the map's own banner sprite (plain white, waving with the "Wave"
    option), no longer an item.
  - The ribbon has a notched tail on both sides (36×22). Shut, it shows whole 3 px from the screen's
    edge and, on hover, slides 3 px out; open, only its right tip comes out of the cover.
  - A rustle of cloth (`ARMOR_EQUIP_LEATHER`, at interface volume, pitch 0.9–1.25, at most one every
    60 ms) when pointing at a banner on the map or in the book's grid.
  - A right click anywhere on the map opens a card (`ui/MapMenu`) with the coordinates and: plant a
    banner here (a draft in the book), copy the coordinates (`x y z`, or `x ~ z` where the height isn't
    known) and teleport (with `/tp`; on terrain the map hasn't seen, `/spreadplayers x z 0 1 false @s`,
    offered only without a roof, and only if the server sends the command). Outside known terrain, x
    and z come from the inverse projection at the player's height (or the cave slice's), without reading
    anything the fog hides. Esc shuts the card before the book and the map.
  - Click actions rebuild the widgets on the next frame (`MapScreen.rebuild`), not in the middle of
    handling the click; a freshly planted banner's name field takes the focus as it is built.
- **Tests**: JUnit for `SettingsCatalog`, `BookLayout`, `OpeningZoom` and `WaypointDraft`; the
  `DIORAMA_TESTS=book-map` scenario. The `waypoints` scenario now expects the draft in the book after
  the right click and carries on with its field checks in the classic `WaypointEditScreen`.

## After 1.0.0

- **Pointer** (from 1.21.9, where the game lets screens pick it; `BookWidget.pointer`,
  `MapScreen.pointer`): a hand over every book control, ribbon, tab and right-click menu row, and
  "not allowed" over switched-off ones, as vanilla's buttons do; the menu's header and the help seal,
  which do nothing, keep the arrow. On the map: a hand over a banner, a crosshair while a region's
  other corner is picked and a cross of arrows while the map is dragged.
- **Sounds**: a click on a banner on the map turns the book's page, as picking it in the book does; the
  right-click menu opens with a softer, higher page sound (`BookWidgets.card`); the page arrows and the
  close mark turn a page instead of clicking; Esc and the waypoint key turn it as they shut or open the
  book; revealing a region starts with `SPYGLASS_USE` and ends with `UI_CARTOGRAPHY_TABLE_TAKE_RESULT`.
