# Banner waypoints — plan

Status: implemented on 2026-10-20 on the `feature/banner-waypoints` branch (steps 1–7). Written as a
plan the same day; the "How it turned out" section at the end records where the implementation
departed from the plan.

## Goal

Every waypoint becomes a Minecraft banner, drawn on the world map and the minimap as a "diegetic"
pixel-art sprite: a banner planted in the ground, with pole, crossbar, cloth and shadow, in the same
pixel-perfect style as the fog. The design is fully customisable as at the vanilla loom: base colour,
patterns and the patterns' colours, using the patterns registered in the game (including those from
datapacks and mods).

## How it is today

- `Waypoint` keeps a name, initials, position, dimension, `color` (a 16-colour palette), visibility
  and kind (`MANUAL`, `DEATH`). `WaypointStore` saves everything to `waypoints.json` in the world's
  folder through Gson (`saves/<world>/diorama/` in singleplayer, `.minecraft/diorama/<world>/` on
  servers).
- `WaypointOverlay` draws a coloured circle with the initials, and an arrow at the edge when the
  waypoint is out of view. The world map and the minimap use the same code.
- `WaypointEditScreen` edits name, initials and colour. `WaypointHud` draws in the world.
  `WaypointShare` shares in Xaero's chat format, and `XaeroImport` imports.

## Data model

- A new `BannerDesign` (an immutable record): `DyeColor base` and a `List<Layer>` with
  `Layer(String pattern, DyeColor color)`. The pattern is kept by id (`minecraft:creeper`,
  `mymod:...`), not by registry index, so it survives datapack changes. An unknown id is skipped when
  drawing and kept when saving.
- An optional `Waypoint.banner`. Old waypoints don't have the field: they use a plain banner with the
  base colour nearest to `color`. Nothing is rewritten until the player edits it. The JSON stays
  readable by earlier versions, which ignore the new field.
- Layer limit: 6, like the loom. A setting allows up to 16 for those who want more detail ("fully
  customisable"); above 6, the banner is still valid in the mod, it just can't be copied to a vanilla
  item.
- Death waypoints use a default banner of their own (black with a skull and a border), editable like
  any other.

## Composing the sprite (CPU)

1. Read the cloth's masks from `textures/entity/banner/<asset>.png` through the resource manager, so
   resource packs change the look. The banner's front takes 20×40 texels of the entity texture. Each
   pattern's path comes from the `BANNER_PATTERN` registry's `assetId`; differences between versions
   are isolated with Stonecutter.
2. Compose like `BannerRenderer`: the dyed base, then each layer as an alpha mask dyed with its colour,
   combined in order.
3. Produce two resolutions in pixel art, downscaling by averaging and then quantising to the colours
   present, so the design stays crisp:
   - world map: 10×20 cloth (half the texture), the whole banner ~14×26;
   - minimap: 6×12 cloth, the whole banner ~9×16.
4. A hand-drawn diegetic frame: a wooden pole and crossbar outlined in dark ink, the pole's tip, a
   1 px shadow on the cloth on the side away from the map's sun, and a dotted shadow on the ground.
5. Waving: 4 precomposed frames in which columns of the cloth drop 0–1 px in a wave running out from
   the pole. The frame changes every ~250 ms, with no new shader. It respects an "Animate banners"
   option for those who prefer them still.

The sprites go into a 256×256 `MapTexture` atlas (256 KiB), with an LRU cache by the design's hash (at
most 128 designs, 4 frames and 2 sizes each). It only recomposes when a design changes or resources
reload (a hook beside `FogTextures.reload()`).

## Drawing on the map

- World map: the foot of the pole sits exactly on the waypoint's block. A whole GUI pixel scale (1x,
  or 2x when zoomed in), centred on whole pixels, like the player's marker. The name on a little
  parchment plate on hover or at zoom ≥ 2.
- Minimap: the small version. Out of view, the banner goes to the edge, a little smaller, with the
  current arrow pointing the way.
- Overlap: banners further south stand in front. When they are less than half a sprite apart, they
  gather into a group showing the number of waypoints.
- Hover and click keep using the same `WaypointOverlay.Pin` areas, now the size of the sprite.
- Fog style: on the sepia antique map, a "Sepia banners" option runs the sprite through the fog's
  ramp; by default they keep their colours, to stand out.
- In the world (`WaypointHud`): the icon uses the same small sprite instead of the circle.

## Editor (loom style)

A `BannerEditScreen`, opened from `WaypointEditScreen`:

- A large preview of the banner at a whole scale (4x), with the pole, cycling through the waving
  frames.
- 16 dye swatches for the base and for the selected layer.
- A scrolling grid of every registered pattern, each thumbnail already dyed in the chosen colour,
  with its translated name in the tooltip.
- A list of layers: add, remove, move up and down, change colour.
- Undo and redo. A "Random" button. A "Copy from the banner in hand" button: reads the layers
  component and the base colour of the held item (or of the first banner in the inventory). A "Copy
  to the item" button is out of scope (it would need the server).
- Keyboard and controller: arrow navigation and visible focus, like the mod's other screens.

## Sharing and importing

- `WaypointShare`: appends the design at the end of the chat line in a compact field (base and layers
  in base64 of short ids). Those without the field, or using Xaero's, get the waypoint with the usual
  colour.
- `XaeroImport`: creates plain banners in the nearest colour.

## Steps

1. `BannerDesign`, migrating `color`, Gson serialisation and JUnit tests (round trip, an old file, an
   unknown id).
2. The CPU compositor and the cached atlas; JUnit tests with synthetic masks (layer order, dyeing,
   downscaling and quantising).
3. Drawing on the world map and the minimap, the edge, groups, hover; the death marker.
4. The loom-style editor and importing the held item.
5. Waving, the sepia option, the HUD icon.
6. Sharing through chat and importing from Xaero's.
7. Documentation (the README and this file) and a single client scenario
   `DIORAMA_TESTS=banner-waypoints` with captures of the map, the minimap and the editor.

Steps 1–3 already deliver the new look. The editor (4) is most of the work.

## Compatibility and risks

- The banner pattern API changed between versions (a data registry since 1.20.5, renames in 26.x).
  All access to the registry and the textures sits in one isolated class with Stonecutter blocks;
  compile Fabric and NeoForge 26.3 and 1.21.1.
- Resource packs with larger banner textures (32x, 64x): the mask is read at whatever resolution comes
  and downscaled to the sprite's size.
- Multiplayer: everything is client-side; no server needed.
- Performance: composing only when a design changes; drawing on the map is quads from an atlas, like
  today's icons. No new texture per waypoint.
- Waypoint files stay compatible both ways.

## How it turned out

- Classes: `waypoint/BannerDesign` (plain data, its own dyes with the colours of
  `DyeColor.getTextureDiffuseColor()`), `waypoint/WaypointJson` (Gson; the `banner` field is only
  written when present), `waypoint/BannerPatternCatalog` (all access to the `BANNER_PATTERN` registry,
  the textures and the item in hand), `render/BannerCompositor` (composing, downscaling, quantising,
  frame and waving, with no game classes) and `render/BannerAtlas` (atlas and cache).
- Real sizes: world map 14×30 (10×20 cloth), minimap/HUD/list 10×21 (6×12 cloth). The foot of the
  pole sits between the two wooden columns, on whole pixels. 2x from zoom 4 on the world map. Names on
  a parchment plate: on the world map only with the mouse over the banner; on the minimap, above the
  banner, for waypoints within 10 blocks of the player.
- The quantising palette is each layer's dye multiplied by the average colour of its mask.
- Atlas: 256×256 couldn't hold 128 designs × 4 frames × 2 sizes. It became 512×512 (1 MiB) with 48 LRU
  slots of 80×64 (4 wave frames + 1 still, in both sizes); writes are 16×16 squares. At most 6 new
  designs are composed per frame; meanwhile the waypoint shows as a dot in the base colour. The editor
  uses an atlas of its own, freed on closing. `BannerAtlas.reload()` runs beside
  `FogTextures.reload()`.
- Waving: the cloth's lower half drops 1 px in alternating pairs of columns, running with the wind
  from left to right; with the "Wave" option off, the still frame is used. Light comes from the top
  left: the right edge and the row under the crossbar are shaded.
- Sepia: a "Sepia on the antique map" option, applied only with the Antique map style (not the colour
  one), with the same `sepia()` ramp as `fog.fsh`, computed on the CPU as a variant of the design in the
  atlas.
- Editor: every control is a vanilla button (visible focus with Tab/arrows/controller); the arrows
  scroll the grid past its first/last row; Ctrl+Z, Ctrl+Y and Ctrl+Shift+Z. Clicking a pattern adds a
  layer in the "pattern dye"; choosing a dye with a layer selected recolours it. Thumbnails use the
  current base (or white/grey when the dye equals the base). Adding a pattern and taking the banner
  play the loom's sounds (1.0.0).
- `WaypointEditScreen`: the 16 swatches are now the banner's base dyes; "Edit banner…" opens the
  editor. A banner left unchanged isn't written; `color` is still saved (the base dye when it changes)
  for earlier versions and for Xaero's.
- Chat: ` diorama-banner:<base64>` at the end of the line when the design isn't the plain one of the
  colour; the name is shortened to 12 characters to fit, otherwise the field is left out. The [Add]
  link uses `/diorama waypoint add-banner …`; `add` stays the same.
- Tests: `BannerDesignTest` and `BannerCompositorTest` (JUnit) and the `DIORAMA_TESTS=banner-waypoints`
  scenario with the `banner-map`, `banner-map-close`, `banner-map-sepia`, `banner-minimap` and
  `banner-editor` captures.
- Out of scope, as planned: copying the design to an item.
