package dev.diorama.bake;

import dev.diorama.data.ChunkSnapshot;
import dev.diorama.data.SeenBlocks;
import java.util.Map;

/** Captures the chunks and visibility a retiring view still needs to save. */
public record FrozenChunks(Map<Long, ChunkSnapshot> chunks, int minY, int maxY, int topY, int roof, SeenBlocks seen, boolean nightLighting) implements ChunkSource {
    public FrozenChunks(Map<Long, ChunkSnapshot> chunks, int minY, int maxY, int topY, int roof, SeenBlocks seen) {
        this(chunks, minY, maxY, topY, roof, seen, true);
    }
    public FrozenChunks { chunks = Map.copyOf(chunks); }
    @Override public ChunkSnapshot chunk(int x, int z) { return chunks.get(ChunkSnapshot.key(x, z)); }
    @Override public boolean isSeen(int x, int y, int z) { return seen.isSeen(x, y, z); }
}
