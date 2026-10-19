package dev.diorama.data;

import dev.diorama.bake.TileKey;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TileReadCacheTest {
    private static TileKey key(int x) { return new TileKey(TileKey.SURFACE, x, 0); }
    @Test void boundsMemoryAndRetainsRecentlyReadTiles() {
        TileReadCache cache = new TileReadCache(3, 32);
        cache.put(key(0), new dev.diorama.bake.TilePixels(new int[4], null));
        cache.put(key(1), new dev.diorama.bake.TilePixels(new int[4], null));
        cache.get(key(0));
        cache.put(key(2), new dev.diorama.bake.TilePixels(new int[4], null));
        assertTrue(cache.contains(key(0)));
        assertFalse(cache.contains(key(1)));
        assertEquals(32, cache.bytes());
        cache.put(key(0), new dev.diorama.bake.TilePixels(new int[2], null));
        assertEquals(24, cache.bytes());
    }
    @Test void boundsMissingEntriesAndOversizedTiles() {
        TileReadCache cache = new TileReadCache(2, 16);
        for (int i = 0; i < 100; i++) cache.put(key(i), null);
        assertEquals(2, cache.size());
        cache.put(key(101), new dev.diorama.bake.TilePixels(new int[10], null));
        assertEquals(0, cache.size());
        assertEquals(0, cache.bytes());
    }
}
