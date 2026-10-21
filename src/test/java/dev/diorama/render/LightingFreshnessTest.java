package dev.diorama.render;

import dev.diorama.bake.TileKey;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LightingFreshnessTest {
    @Test void terrainAndLightChangesRejectOlderPairedResults() {
        var revisions = new TileFreshness();
        var key = new TileKey(TileKey.SURFACE, -1, -1);
        var original = revisions.get(key);
        revisions.changed(key, true);
        var lightingJob = revisions.get(key);
        assertFalse(revisions.accepts(key, original));
        assertEquals(original.terrain(), lightingJob.terrain());
        revisions.changed(key, false);
        assertFalse(revisions.accepts(key, lightingJob));
        assertTrue(revisions.accepts(key, revisions.get(key)));
        revisions.changed(key, true);
        assertFalse(revisions.accepts(key, lightingJob));
    }

    @Test void aViewOfPairedTexturesFitsTheBudget() {
        long plane = TextureMemory.bytes(512, 6);
        assertEquals(1397760, plane);
        // A 4K screen at GUI scale 2 and the default zoom asks for 80 tiles (ViewBudgetTest): with
        // both lighting planes and an overlay they still fit a view's share of the budget.
        assertTrue(MapViewport.VIEW_BUDGET / (2 * plane + TextureMemory.bytes(512, 1)) >= 80);
        assertTrue(TextureMemory.BUDGET <= 512L * 1024 * 1024);
    }
}
