package dev.diorama.render;

import dev.diorama.bake.TileImage;
import dev.diorama.bake.TileKey;
import java.util.*;

/**
 * CPU staging for one appearance revision. Tiles a view shows swap to the new look together, in
 * one frame, once every one of them is staged; the others swap as they arrive, a few at a time,
 * since nobody sees them change. Overviews wait for every detailed tile and their writer barrier,
 * and swap together last.
 */
final class AppearanceRefresh {
    final Set<TileKey> pending = new HashSet<>();
    final Set<TileKey> overviews = new HashSet<>();
    final Map<TileKey, TileImage> images = new LinkedHashMap<>();
    final Map<TileKey, TileFreshness.Version> versions = new HashMap<>();
    final Map<TileKey, Integer> generations = new HashMap<>();
    final Set<TileKey> diskOnly = new HashSet<>();
    /** Staged images that drew their whole tile, so the store can vouch for their look. */
    final Set<TileKey> complete = new HashSet<>();
    /** Detailed tiles already showing this revision; their later bakes are ordinary updates. */
    final Set<TileKey> swapped = new HashSet<>();
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

    /**
     * The staged detailed tiles that may swap now: every one on screen, but only once none on
     * screen is still pending, plus up to {@code offScreenLimit} that no view shows.
     */
    List<TileKey> swappable(Set<TileKey> onScreen, int offScreenLimit) {
        boolean screenReady = true;
        for (TileKey key : onScreen) if (key.lod() == 0 && this.pending.contains(key)) { screenReady = false; break; }
        List<TileKey> group = new ArrayList<>();
        int offScreen = 0;
        for (TileKey key : this.images.keySet()) {
            if (key.lod() > 0 || this.pending.contains(key)) continue;
            if (onScreen.contains(key)) { if (screenReady) group.add(key); }
            else if (offScreen < offScreenLimit) { group.add(key); offScreen++; }
        }
        return group;
    }

    /** Takes a staged detailed tile out of the revision to show it now. */
    TileImage swap(TileKey key) {
        TileImage image = this.images.remove(key);
        this.versions.remove(key);
        this.generations.remove(key);
        this.complete.remove(key);
        this.swapped.add(key);
        return image;
    }

    /** Whether every detailed tile has swapped, so only overviews and disk-only children remain. */
    boolean detailDone() {
        if (!this.pending.isEmpty()) return false;
        for (TileKey key : this.images.keySet()) if (key.lod() == 0) return false;
        return true;
    }

    void forget(TileKey key) {
        pending.remove(key);
        overviews.remove(key);
        images.remove(key);
        versions.remove(key);
        generations.remove(key);
        diskOnly.remove(key);
        complete.remove(key);
        swapped.remove(key);
    }

    void evicted(TileKey key, java.util.function.Consumer<TileImage> persist) {
        // A swapped tile already gave the writer its new pixels.
        if (key.lod() == 0 && !swapped.contains(key) && overviews.stream().anyMatch(overview ->
            Math.floorDiv(key.tileU(), 1 << overview.lod()) == overview.tileU()
                && Math.floorDiv(key.tileV(), 1 << overview.lod()) == overview.tileV())) {
            // An overview still needs this child even when no detailed viewport draws it.
            diskOnly.add(key);
            TileImage image = images.remove(key);
            if (image != null) persist.accept(image);
        } else forget(key);
    }
}
