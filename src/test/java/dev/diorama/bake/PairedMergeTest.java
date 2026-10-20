package dev.diorama.bake;

import dev.diorama.Projection;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PairedMergeTest {
    @Test void unloadedLegacyPixelsDoNotInventAnUpgradeWhileKnownBlackRemainsAvailable() {
        int[] saved = new int[Projection.TILE_PIXELS * Projection.TILE_PIXELS];
        java.util.Arrays.fill(saved, 0xff123456);
        int[] day = new int[saved.length], night = new int[saved.length];
        TileBaker.merge(day, night, new TilePixels(saved, null), 0, 0, Projection.TILE_PIXELS);
        assertArrayEquals(saved, day);
        assertNull(TileBaker.nightMips(night, Projection.TILE_PIXELS));
        night[0] = 0xff000000;
        assertNotNull(TileBaker.nightMips(night, Projection.TILE_PIXELS));
    }

    @Test void keepingSavedCoverageAlsoKeepsItsLightingOrExplicitLegacyAvailability() {
        int[] oldDay = new int[Projection.TILE_PIXELS * Projection.TILE_PIXELS];
        int[] oldNight = new int[oldDay.length];
        oldDay[0] = 0xff123456; oldNight[0] = 0xff001122;
        int[] day = {0x7fabcdef}, night = {0x7f112233};
        TileBaker.merge(day, night, new TilePixels(oldDay, oldNight), 0, 0, 1);
        assertEquals(oldDay[0], day[0]); assertEquals(oldNight[0], night[0]);
        day[0] = 0x7fabcdef; night[0] = 0x7f112233;
        TileBaker.merge(day, night, new TilePixels(oldDay, null), 0, 0, 1);
        assertEquals(oldDay[0], day[0]); assertEquals(0, night[0]);
    }
}
