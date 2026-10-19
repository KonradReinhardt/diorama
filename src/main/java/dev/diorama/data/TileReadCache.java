package dev.diorama.data;

import java.util.LinkedHashMap;

/** Access-ordered read cache; pending writes have separate ownership in TileStore. Caller locks. */
final class TileReadCache {
    private final int maxEntries;
    private final long maxBytes;
    private final LinkedHashMap<dev.diorama.bake.TileKey, dev.diorama.bake.TilePixels> entries = new LinkedHashMap<>(16, 0.75F, true);
    private long bytes;

    TileReadCache(int maxEntries, long maxBytes) {
        this.maxEntries = maxEntries;
        this.maxBytes = maxBytes;
    }

    boolean contains(dev.diorama.bake.TileKey key) { return this.entries.containsKey(key); }
    dev.diorama.bake.TilePixels get(dev.diorama.bake.TileKey key) { return this.entries.get(key); }
    int size() { return this.entries.size(); }
    long bytes() { return this.bytes; }
    void clear() { this.entries.clear(); this.bytes = 0; }

    void put(dev.diorama.bake.TileKey key, dev.diorama.bake.TilePixels pixels) {
        dev.diorama.bake.TilePixels previous = this.entries.put(key, pixels);
        this.bytes += weight(pixels) - weight(previous);
        var it = this.entries.entrySet().iterator();
        while ((this.entries.size() > this.maxEntries || this.bytes > this.maxBytes) && it.hasNext()) {
            this.bytes -= weight(it.next().getValue());
            it.remove();
        }
    }

    private static long weight(dev.diorama.bake.TilePixels pixels) { return pixels == null ? 0 : pixels.bytes(); }
}
