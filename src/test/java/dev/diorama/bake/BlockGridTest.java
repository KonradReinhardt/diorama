package dev.diorama.bake;

import static org.junit.jupiter.api.Assertions.*;

import dev.diorama.MapStyle;
import dev.diorama.Projection;
import org.junit.jupiter.api.Test;

/** The map's projection puts every block edge on a pixel boundary, so each face is 4 pixels wide and 3 tall. */
class BlockGridTest {
    private static final double PITCH = MapStyle.DEFAULT.pitchDegrees();
    private static final double A = Projection.zScale(PITCH);
    private static final double B = Projection.yScale(PITCH);

    private static double v(double y, double z) {
        return z * A - y * B;
    }

    @Test void everyFaceIsFourByThreePixels() {
        for (int y = -64; y < 320; y += 37) {
            for (int z = -3000; z < 3000; z += 113) {
                // A top face spans z..z+1 at height y+1; a south face spans y..y+1 at z+1.
                double topRows = (v(y + 1, z + 1) - v(y + 1, z)) * Projection.PIXELS_PER_UNIT;
                double sideRows = (v(y, z + 1) - v(y + 1, z + 1)) * Projection.PIXELS_PER_UNIT;
                assertEquals(3, topRows, 1e-12);
                assertEquals(3, sideRows, 1e-12);
                // And every edge lands on a whole pixel.
                double edge = v(y, z) * Projection.PIXELS_PER_UNIT;
                assertEquals(Math.rint(edge), edge, 1e-6);
            }
        }
    }

    @Test void magnifiedViewsDrawEveryMapPixelAsTheSameSquareOfScreenPixels() {
        java.util.Random random = new java.util.Random(5);
        for (int gui = 1; gui <= 4; gui++) {
            for (int k = 0; k < 200; k++) {
                double zoom = 0.2 + random.nextDouble() * 12;
                var view = new dev.diorama.render.MapViewport.View(random.nextInt(50), random.nextInt(50), 100 + random.nextInt(300),
                    100 + random.nextInt(300), (random.nextDouble() - 0.5) * 6e6, (random.nextDouble() - 0.5) * 6e6, zoom, TileKey.SURFACE);
                var drawn = view.pixelPerfect(gui);
                double perMapPixel = zoom * gui / Projection.PIXELS_PER_UNIT;
                if (perMapPixel < 1) {
                    assertSame(view, drawn);
                    continue;
                }
                double screenPerPixel = drawn.zoom() * gui / Projection.PIXELS_PER_UNIT;
                assertEquals(Math.rint(screenPerPixel), screenPerPixel, 1e-9);
                assertTrue(Math.abs(screenPerPixel - perMapPixel) <= 0.5 + 1e-9);
                // A map pixel edge near the centre lands on a whole screen pixel, and the centre barely moved.
                double edge = Math.floor(drawn.centerU() * Projection.PIXELS_PER_UNIT) / Projection.PIXELS_PER_UNIT;
                double screen = gui * (drawn.x() + drawn.width() / 2.0 + (edge - drawn.centerU()) * drawn.zoom());
                assertEquals(Math.rint(screen), screen, 1e-4);
                assertTrue(Math.abs(drawn.centerU() - view.centerU()) * drawn.zoom() * gui <= 0.5 + 1e-6);
            }
        }
    }

    @Test void facesThatMeetAtAnEdgeHaveNoOutline() {
        // The top of block (y 10, z 5) and its own south face meet at map row v(11, 6).
        double v = v(11, 6);
        assertEquals(TileBaker.faceDepth(TileBaker.TOP_FACE, 11, v, A, B), TileBaker.faceDepth(TileBaker.SOUTH_FACE, 6, v, A, B), 1e-9);
        // Ground (top at 10) running into the foot of that wall from the south meets it at v(10, 6).
        double foot = v(10, 6);
        assertEquals(TileBaker.faceDepth(TileBaker.TOP_FACE, 10, foot, A, B), TileBaker.faceDepth(TileBaker.SOUTH_FACE, 6, foot, A, B), 1e-9);
    }

    @Test void aHigherTopHidesTheGroundBehindIt() {
        // Two blocks of step: the high top's north edge borders lower ground farther north.
        double v = v(13, 5);
        double high = TileBaker.faceDepth(TileBaker.TOP_FACE, 13, v, A, B);
        double low = TileBaker.faceDepth(TileBaker.TOP_FACE, 11, v, A, B);
        assertTrue(low > high, "the lower ground is farther");
        assertEquals(2 * Math.sqrt(2), (low - high) / Math.sqrt(A * A + B * B), 1e-9);
    }
}
