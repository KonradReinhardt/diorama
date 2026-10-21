# Revealing a region of the map

A player in creative, or with cheats (game master commands), can pick a stretch of the world map
they haven't explored and have it drawn. This is for builders and map makers who want to see the
land before going there; it is never available to a survival player without cheats.

## Using it

On the world map, right-click a corner of the region and pick **Reveal a region from here**. The
region follows the cursor, a chunk at a time, up to 64 × 64 chunks (1024 blocks a side); the slip at
the top gives its size. A left click on the opposite corner starts it, and a right click or `Esc`
lets it go. While it runs, the status slip shows how far it is and the region stays outlined; the
right-click menu then offers **Stop revealing** instead.

The outline is drawn at the player's height (or the cave slice's), like the coordinates of
unexplored ground in the right-click menu, so on much higher or lower land it sits a little off.

## Where it works

Only in singleplayer (and on a LAN world from the host), on 26.3 (Fabric and NeoForge): the chunks
come from the integrated server, and a server elsewhere never sends chunks far from the player.
Diorama is client-side, so there is no way to ask a dedicated server for them.

The entry isn't offered without a saved map, nor under a roof (the Nether) while fog of war is on,
where the map may only show blocks the player has seen.

## How it works

`RegionReveal` (client thread) orders the region's chunks in rows across the map as the camera
faces, so tiles fill a band at a time. `GeneratedChunks` asks the integrated server for each one
with `ServerChunkCache.getChunkFuture(..., FULL, true)`, which loads it or generates it if it never
was, plus its neighbours at `BIOMES` for blending tints along its edges. The request is made off the
server thread, where it would block the tick; once the chunk is there, its sections and light are
copied on the server thread into a `ChunkSnapshot`, as `SavedChunks` does for saved chunks.

The snapshot then goes through `MapLevel.put` like a chunk the player loaded: its tiles are baked,
saved and count as explored from then on, with the usual discovery ease. Chunks already on the map
are skipped, and a chunk the player loaded meanwhile wins over the server's copy. Seed previews
never come into it, and nothing calls `markSeen`: cave slices under fog of war stay hidden.

Generating land the player never visited changes the world save, as walking there would: those
chunks exist in the world from then on.

## Limits

- At most 12 chunks are asked for at once, and none while 24 or more surface tiles still wait for
  their background bake and save. Snapshots therefore stay within the map's usual budget of 4096
  chunks, whose trim keeps chunks under unsaved tiles.
- The server checks again, for every chunk, that the player is in creative or has game master
  permission; on the first refusal the region stops. The client stops it too when it sees the
  permission go, when the player leaves the world or changes dimension, or when the map's fog of war
  under a roof turns on. What was revealed before stays on the map, like land the player explored.
- The integrated server pauses with the game menu, and the region with it.

## Test

`DIORAMA_TESTS=reveal-region DIORAMA_RENDER_DISTANCE=8 ./gradlew --configure-on-demand :26.3:runClientGameTest`
picks a 4 × 4 chunk region 48 chunks from the player through the right-click menu, checks the
region started is the one clicked, and that its middle goes from unexplored to covered. It then
zooms out past the overviews and back three times and checks the fog never eases away from the
region again. Screenshots: `reveal-01-unexplored`, `reveal-02-picking`, `reveal-03-revealed`,
`reveal-04-after-zooming`.
