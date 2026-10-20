package dev.diorama;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SmoothZoomTest {
    @Test void wheelAccumulatesAndPreservesCursorAnchorThroughoutAnimation() {
        var zoom = new SmoothZoom(2);
        zoom.scroll(1, 120, -80, .04, 16);
        zoom.scroll(1, 120, -80, .04, 16);
        assertEquals(2, zoom.value());
        double u = 300, v = -150, previous = zoom.value();
        for (int i = 0; i < 90; i++) {
            var step = zoom.advance(1.0 / 60, u, v, .04);
            assertEquals(360, step.centerU() + 120 / step.zoom(), 1e-9);
            assertEquals(-190, step.centerV() - 80 / step.zoom(), 1e-9);
            assertTrue(step.zoom() >= previous && step.zoom() <= zoom.target());
            u = step.centerU(); v = step.centerV(); previous = step.zoom();
        }
        assertEquals(3.125, zoom.value(), 1e-9);
    }

    @Test void easingHasTheSameDurationAtDifferentFrameRatesAndClampsCaves() {
        var a = new SmoothZoom(2); var b = new SmoothZoom(2);
        a.scroll(-12, 0, 0, .04, 16); b.scroll(-12, 0, 0, .04, 16);
        for (int i = 0; i < 12; i++) a.advance(1.0 / 30, 0, 0, .04);
        for (int i = 0; i < 48; i++) b.advance(1.0 / 120, 0, 0, .04);
        assertEquals(a.value(), b.value(), 1e-10);
        assertEquals(.5, a.advance(0, 0, 0, .5).zoom());
        assertEquals(.5, a.target());
        a.scroll(Double.NaN, 0, 0, .5, 16);
        assertEquals(.5, a.target());
    }
}
