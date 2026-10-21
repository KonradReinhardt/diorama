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
        assertTrue(refresh.tracks(key), "A staged cache miss must still receive terrain invalidations");
        refresh.pending.add(key);
        assertFalse(refresh.ready(key));
        refresh.forget(key);
        assertFalse(refresh.tracks(key));
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

    private static TileImage image(TileKey key) {
        return TileImage.full(key, new int[][]{new int[]{-1}}, new byte[]{-1});
    }

    @Test void tilesOnScreenSwapTogetherOnlyOnceEveryOneIsStaged() {
        TileKey a = new TileKey(TileKey.SURFACE, 0, 0), b = new TileKey(TileKey.SURFACE, 1, 0), off = new TileKey(TileKey.SURFACE, 9, 9);
        var refresh = new AppearanceRefresh(List.of(a, b, off));
        var version = new TileFreshness.Version(0, 0);
        Set<TileKey> screen = Set.of(a, b);
        refresh.stage(image(a), version);
        assertEquals(List.of(), refresh.swappable(screen, 4), "Half the screen must not change style");
        refresh.stage(image(b), version);
        assertEquals(Set.of(a, b), Set.copyOf(refresh.swappable(screen, 4)));
        assertFalse(refresh.detailDone(), "The tile off screen is still on its way");
    }

    @Test void tilesOffScreenSwapAsTheyArriveInBoundedGroups() {
        List<TileKey> off = new ArrayList<>();
        for (int i = 0; i < 6; i++) off.add(new TileKey(TileKey.SURFACE, i, 5));
        TileKey shown = new TileKey(TileKey.SURFACE, 0, 0);
        List<TileKey> resident = new ArrayList<>(off);
        resident.add(shown);
        var refresh = new AppearanceRefresh(resident);
        for (TileKey key : off) refresh.stage(image(key), new TileFreshness.Version(0, 0));
        // The tile on screen isn't ready, which doesn't hold back the ones nobody sees.
        List<TileKey> group = refresh.swappable(Set.of(shown), 4);
        assertEquals(4, group.size());
        assertFalse(group.contains(shown));
        for (TileKey key : group) assertNotNull(refresh.swap(key));
        assertEquals(2, refresh.swappable(Set.of(shown), 4).size());
    }

    @Test void theWholeScreenSwapsEvenPastTheOffScreenLimit() {
        List<TileKey> screen = new ArrayList<>();
        for (int i = 0; i < 12; i++) screen.add(new TileKey(TileKey.SURFACE, i, 0));
        var refresh = new AppearanceRefresh(screen);
        for (TileKey key : screen) refresh.stage(image(key), new TileFreshness.Version(0, 0));
        assertEquals(12, refresh.swappable(Set.copyOf(screen), 2).size());
    }

    @Test void aTileOnScreenQueuedAgainHoldsBackTheWholeScreen() {
        TileKey a = new TileKey(TileKey.SURFACE, 0, 0), b = new TileKey(TileKey.SURFACE, 1, 0);
        var refresh = new AppearanceRefresh(List.of(a, b));
        refresh.stage(image(a), new TileFreshness.Version(0, 0));
        refresh.stage(image(b), new TileFreshness.Version(0, 0));
        // Newer terrain under b invalidates its staged image.
        refresh.pending.add(b);
        assertEquals(List.of(), refresh.swappable(Set.of(a, b), 4));
    }

    @Test void aSwappedTileLeavesTheRevisionWithoutBeingStagedAgain() {
        TileKey a = new TileKey(TileKey.SURFACE, 0, 0);
        var refresh = new AppearanceRefresh(List.of(a));
        var staged = image(a);
        refresh.stage(staged, new TileFreshness.Version(0, 0));
        refresh.complete.add(a);
        assertSame(staged, refresh.swap(a));
        assertTrue(refresh.swapped.contains(a));
        assertFalse(refresh.tracks(a), "Edits to a swapped tile take the ordinary path");
        assertFalse(refresh.ready(a));
        assertTrue(refresh.images.isEmpty() && refresh.versions.isEmpty() && refresh.complete.isEmpty());
        assertEquals(List.of(), refresh.swappable(Set.of(a), 4));
        assertTrue(refresh.detailDone());
        refresh.evicted(a, image -> fail("Nothing staged"));
        assertFalse(refresh.swapped.contains(a), "An evicted tile joins the revision again when it comes back");
    }

    @Test void overviewsAndDiskOnlyChildrenNeverSwapEarly() {
        TileKey child = new TileKey(TileKey.SURFACE, -1, -1), shown = new TileKey(TileKey.SURFACE, 0, 0);
        var refresh = new AppearanceRefresh(List.of(shown, child.parent()));
        refresh.pending.add(child);
        refresh.diskOnly.add(child);
        refresh.stage(image(child), new TileFreshness.Version(0, 0), false);
        refresh.stage(image(child.parent()), new TileFreshness.Version(0, 0));
        assertEquals(List.of(), refresh.swappable(Set.of(child, child.parent()), 4));
        refresh.stage(image(shown), new TileFreshness.Version(0, 0));
        assertEquals(List.of(shown), refresh.swappable(Set.of(shown), 4));
        refresh.swap(shown);
        assertTrue(refresh.detailDone());
        assertTrue(refresh.tracks(child), "Its overview still waits on the disk-only child");
        assertTrue(refresh.images.containsKey(child.parent()));
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
