package dev.diorama.render;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FogRevealTest {
    @Test void seedRefinementDoesNotRestartTheDiscoveryClock() {
        var reveal = new FogReveal(2);
        reveal.update(new int[4], 0, 0, 2, false, 0);
        reveal.update(new int[]{255, 0, 0, 0}, 0, 0, 2, false, 1);
        int[] metadata = {255, 0, 0, 0};
        reveal.update(metadata, 0, 0, 2, false, 300_000_001);
        assertEquals(0, metadata[0] >>> 24);
        assertEquals(1, reveal.progress(600_000_001));
    }

    @Test void revealsSmoothlyWithoutRevealingUnknownOrDelayingConcealment() {
        var reveal = new FogReveal(2);
        reveal.update(new int[]{255, 0, 0, 0}, 0, 0, 2, false, 0);
        int[] pixels = {255, 255, 0, 0};
        reveal.update(pixels, 0, 0, 2, false, 1);
        assertEquals(255, pixels[1]); // red target 255, alpha start 0
        // Ease-out: most of the fog is gone halfway through, so streamed tiles never stall it.
        assertEquals(.875, reveal.progress(1 + FogReveal.DURATION_NANOS / 2), 1e-6);
        reveal.update(new int[]{255, 255, 0, 0}, 0, 0, 2, false, 300_000_001);
        int[] concealed = {0, 0, 0, 0};
        reveal.update(concealed, 0, 0, 2, false, 300_000_002);
        assertArrayEquals(new int[4], concealed);
    }

    @Test void frequentRestartsStillRevealQuickly() {
        // While a map opens, tiles arrive every frame and each one restarts the clock from the
        // displayed state. The first discovery must keep receding instead of stalling.
        var reveal = new FogReveal(2);
        long frame = 16_000_000L;
        reveal.update(new int[4], 0, 0, 2, false, 0);
        for (int f = 1; f <= 15; f++) reveal.update(new int[]{255, f * 10, 0, 0}, 0, 0, 2, false, f * frame);
        int[] shown = {255, 150, 0, 0};
        reveal.update(shown, 0, 0, 2, false, 15 * frame);
        assertTrue((shown[0] >>> 24) > 190, "displayed " + (shown[0] >>> 24));
    }

    @Test void panningReprojectsTheDisplayedEndpointAndResetNeverLeaksAnotherLayer() {
        var reveal = new FogReveal(2);
        reveal.update(new int[]{0, 255, 0, 0}, 0, 0, 2, false, 0);
        int[] moved = {255, 255, 0, 0};
        reveal.update(moved, 2, 0, 2, false, 1);
        assertEquals(255, moved[0] >>> 24);
        assertEquals(255, moved[1] >>> 24); // entering viewport is not a new discovery
        int[] newLayer = {0, 0, 255, 255};
        reveal.update(newLayer, 2, 0, 2, true, 2);
        assertEquals(0, newLayer[0]);
        assertEquals(255, newLayer[2] >>> 24);
    }
}
