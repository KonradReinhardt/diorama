package dev.diorama.render;

import dev.diorama.bake.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AppearanceRefreshTest {
    @Test void aStagedCacheMissIsReadyUntilItsSourceIsInvalidatedAgain() {
        TileKey key = new TileKey(TileKey.SURFACE, 10, -10);
        var refresh = new AppearanceRefresh(List.of());
        assertFalse(refresh.ready(key));
        refresh.stage(TileImage.full(key, new int[][]{new int[]{-1}}, new byte[]{-1}), new TileFreshness.Version(0, 0));
        for (int frame = 0; frame < 1000; frame++) assertTrue(refresh.ready(key));
        refresh.pending.add(key);
        assertFalse(refresh.ready(key));
    }
    @Test void evictingDetailCannotLetItsOldParentCommitBeforeTheNewChild() {
        TileKey key = new TileKey(TileKey.SURFACE, -1, -1);
        var refresh = new AppearanceRefresh(List.of(key, key.parent()));
        refresh.evicted(key, image -> fail("No staged pixels yet"));
        assertTrue(refresh.pending.contains(key));
        assertTrue(refresh.diskOnly.contains(key));
        var image = TileImage.full(key, new int[][]{new int[]{-1}}, new byte[]{-1});
        refresh.stage(image, new TileFreshness.Version(0, 0));
        List<TileImage> persisted = new ArrayList<>();
        refresh.evicted(key, persisted::add);
        assertEquals(List.of(image), persisted);
        assertFalse(refresh.images.containsKey(key));
        assertTrue(refresh.overviews.contains(key.parent()));
    }
    @Test void outOfOrderResultsWaitForEveryResidentTileAndKeepOverviewsSeparate() {
        TileKey a = new TileKey(TileKey.SURFACE, 0, 0), b = new TileKey(TileKey.SURFACE, -1, -1);
        var refresh = new AppearanceRefresh(List.of(a, b, a.parent()));
        var version = new TileFreshness.Version(1, 2);
        refresh.stage(TileImage.full(b, new int[][]{new int[]{-1}}, new byte[]{-1}), version);
        assertEquals(Set.of(a), refresh.pending);
        assertEquals(Set.of(a.parent()), refresh.overviews);
        assertFalse(refresh.images.containsKey(a));
        refresh.stage(TileImage.full(a, new int[][]{new int[]{-1}}, new byte[]{-1}), version);
        assertTrue(refresh.pending.isEmpty());
        assertEquals(2, refresh.images.size());
    }

    @Test void evictionReleasesStagedMemoryAndCannotKeepARevisionWaiting() {
        TileKey a = new TileKey(TileKey.SURFACE, 0, 0);
        var refresh = new AppearanceRefresh(List.of(a, a.parent()));
        refresh.stage(TileImage.full(a, new int[][]{new int[]{-1}}, new byte[]{-1}), new TileFreshness.Version(0, 0));
        refresh.forget(a);
        refresh.forget(a.parent());
        assertTrue(refresh.pending.isEmpty());
        assertTrue(refresh.images.isEmpty());
        assertTrue(refresh.versions.isEmpty());
        assertTrue(refresh.overviews.isEmpty());
    }
}
