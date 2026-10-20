package dev.diorama.render;

import dev.diorama.bake.TileImage;
import dev.diorama.bake.TileKey;
import java.util.*;

/** CPU staging for one appearance revision. No image is visible before the complete revision. */
final class AppearanceRefresh {
    final Set<TileKey> pending = new HashSet<>();
    final Set<TileKey> overviews = new HashSet<>();
    final Map<TileKey, TileImage> images = new LinkedHashMap<>();
    final Map<TileKey, TileFreshness.Version> versions = new HashMap<>();
    final Map<TileKey, Integer> generations = new HashMap<>();
    final Set<TileKey> diskOnly = new HashSet<>();
    /** Staged images that drew their whole tile, so the store can vouch for their look. */
    final Set<TileKey> complete = new HashSet<>();
    java.util.concurrent.CompletableFuture<Void> overviewBarrier;

    AppearanceRefresh(Collection<TileKey> resident) {
        for (TileKey key : resident) {
            if (key.lod() == 0) pending.add(key);
            else overviews.add(key);
        }
    }

    boolean ready(TileKey key) { return versions.containsKey(key) && !pending.contains(key); }
    boolean tracks(TileKey key) { return pending.contains(key) || versions.containsKey(key) || diskOnly.contains(key); }

    void stage(TileImage image, TileFreshness.Version version) {
        stage(image, version, true);
    }

    void stage(TileImage image, TileFreshness.Version version, boolean retain) {
        if (!image.isFull()) throw new IllegalArgumentException("Appearance refresh requires full images");
        if (retain) images.put(image.key(), image);
        versions.put(image.key(), version);
        generations.put(image.key(), image.generation());
        pending.remove(image.key());
    }

    void forget(TileKey key) {
        pending.remove(key);
        overviews.remove(key);
        images.remove(key);
        versions.remove(key);
        generations.remove(key);
        diskOnly.remove(key);
        complete.remove(key);
    }

    void evicted(TileKey key, java.util.function.Consumer<TileImage> persist) {
        if (key.lod() == 0 && overviews.stream().anyMatch(overview ->
            Math.floorDiv(key.tileU(), 1 << overview.lod()) == overview.tileU()
                && Math.floorDiv(key.tileV(), 1 << overview.lod()) == overview.tileV())) {
            // An overview still needs this child even when no detailed viewport draws it.
            diskOnly.add(key);
            TileImage image = images.remove(key);
            if (image != null) persist.accept(image);
        } else forget(key);
    }
}
