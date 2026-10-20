package dev.diorama.bake;

import dev.diorama.data.ChunkSnapshot;
import org.jspecify.annotations.Nullable;

/**
 * Chunks the game itself saved, for re-drawing explored map tiles whose chunks aren't loaded when
 * the map's look changes. Only available where the world is on this machine (singleplayer).
 * Reads block on disk, so call from bake threads only.
 */
public interface SavedTerrain extends AutoCloseable {
	/** The saved chunk, or null if it was never fully generated, can't be read, or the world closed. */
	@Nullable ChunkSnapshot load(int chunkX, int chunkZ);

	@Override
	void close();
}
