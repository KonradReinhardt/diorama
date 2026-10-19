package dev.diorama.render;

import dev.diorama.bake.TileKey;
import java.util.HashMap;
import java.util.Map;

/** Client-thread revisions; a job owns both endpoints and must match both on completion. */
final class TileFreshness {
    record Version(long terrain, long light) {}
    private final Map<TileKey, Version> versions = new HashMap<>();
    Version get(TileKey key) { return versions.getOrDefault(key, new Version(0, 0)); }
    void changed(TileKey key, boolean lightOnly) {
        Version old = get(key);
        versions.put(key, new Version(old.terrain + (lightOnly ? 0 : 1), old.light + 1));
    }
    boolean accepts(TileKey key, Version version) { return get(key).equals(version); }
    void remove(TileKey key) { versions.remove(key); }
}
