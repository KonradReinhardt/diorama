# Changelog

## 1.0.0

Mappa Mundi's first stable release, a fork of Diorama by Dara Adedeji under a name of its own (the mod
id and folders stay `diorama`, so Diorama's saved maps, waypoints and settings carry over), built for every supported target: Fabric 1.21–1.21.1, 1.21.2–1.21.3,
1.21.4, 1.21.5, 1.21.6–1.21.8, 1.21.9–1.21.10, 1.21.11, 26.1–26.1.2, 26.2 and 26.3, and NeoForge 1.21,
1.21.1, 1.21.3, 1.21.4, 1.21.5, 1.21.8, 1.21.10, 1.21.11, 26.1.2, 26.2 and 26.3.

Since upstream 0.2.1:

- **Map directions.** The camera can face north (default), east, south or west; each direction keeps its own saved map.
- **Pixel-perfect block grid.** Every block face is 4×3 map pixels at a 45° pitch, one ray per pixel, outlines from the faces' geometry, and the world map rests on whole screen pixels.
- **Render styles.** Plain, Outlined, Painted (default) and Painted without outlines, computed while baking.
- **Fog styles.** Clouds (default), Antique map and Antique map (color), both in 16× pixel art; in singleplayer 26.3 the unknown is sketched from the seed's biomes without generating chunks.
- **Day and night lighting** on Fabric 26.3: surface tiles blend saved day and night colours with the world's time; torches and lava light the map at night.
- **Banner waypoints.** Waypoints are pixel-art banners designed like at the loom, shared through chat and importable from Xaero's Minimap.
- **A book over the world map.** Ribbons open its sections (Banners, Look, Layers, Radar); settings change live over the map. A right-click menu plants a banner, copies coordinates or teleports.
- **Reveal a region** (creative or cheats, singleplayer 26.3): pick two corners and the integrated server loads or generates those chunks for the map.
- **Drifting clouds** and **coast foam, ripples and waves**, drawn as shader passes from 1.21.6, never baked or saved.
- **Storage.** Singleplayer maps and waypoints live in the save (`saves/<world>/diorama/`); older maps move there once. Big maps stay on disk without limit and within a 512 MB GPU budget.
- **Appearance changes keep exploration.** Look changes are prepared in the background, the tiles on screen first; in singleplayer 26.3 unloaded tiles are redrawn from the world's saved chunks.
- **Mod Menu** entry on Fabric (optional, not bundled).

New in this release:

- **Regions.** Draw regions on the world map with a lasso, a polygon or a chunk brush; edit their corners, move them, add to them with Shift and cut them with Alt, erase them, undo and redo. A Regions ribbon in the book lists them nearest first and sets each one's name, dye, fill, where it shows and its banner (from the loom, the banner in hand or a waypoint's). The idea, and its lasso, polygon, eraser and undo, come from [MapScribble](https://github.com/radishsprouts/MapScribble) by radishsprouts (MIT), notes drawn on JourneyMap's map; no code is shared.
- Regions are drawn in a style of their own, picked from pictures (Marker, Political, Watercolor, Wash, Engraving, Illuminated), with a border band in their dye that fades inwards, as strategy games draw territories (on by default). Names and banners are written on the world map where they fit; the region you stand in shows under the minimap; an optional notice names the region you walk into. Shader pass from 1.21.6; older versions draw a flat look.
- Regions are saved per world in `regions.json` beside the waypoints, in world coordinates on the height of the ground they were drawn on; a file from a newer version is shown but never written over.
- The "Sepia banners" option turns region banners sepia too, and the full settings screen lays out in four columns where three don't fit.

- The pointer turns into a hand over everything that can be clicked (book controls, the right-click menu, banners on the map, dye swatches), "not allowed" over switched-off controls, a crosshair while picking a region's corner and a cross of arrows while dragging the map. The game only lets mods pick the pointer from 1.21.9.
- Sounds the interface missed: clicking a banner on the map turns the book's page, the right-click menu opens with a soft paper sound, the book's page arrows and close mark turn a page, Esc and the waypoint key turn it as they shut or open the book, revealing a region starts with the spyglass and ends with the cartography table, the dye swatches click and the banner editor sounds like the loom.
- New defaults, for configs that don't set them yet: the Painted style, the minimap cut to the shape of a vanilla map at a closer zoom (2 GUI pixels per block) and only players on the radar. Existing configs keep their choices.
- Periodic bake statistics are logged only with `DIORAMA_DEBUG=1`.
- Source indentation made consistent (tabs), and every design and testing document is in English.
