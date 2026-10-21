package dev.diorama.bake;
import dev.diorama.MapDirection;
import dev.diorama.MapStyle;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class BakeContextTest {
    @Test void qualityAndAppearancePreserveExplorationGeometry() {
        var before = new BakeContext(MapStyle.DEFAULT, MapDirection.NORTH, false);
        assertTrue(before.sameProjection(new BakeContext(MapStyle.DEFAULT.withOutline(0), MapDirection.NORTH, false)));
        assertTrue(before.sameProjection(new BakeContext(MapStyle.DEFAULT, MapDirection.NORTH, true)));
        assertTrue(before.sameProjection(new BakeContext(MapStyle.DEFAULT.withLight(.1F, .2F), MapDirection.NORTH, false)));
    }
    @Test void shadingToggleKeepsTheSameCamera() {
        var settings = new dev.diorama.Settings();
        double pitch = settings.style().pitchDegrees();
        settings.depthShading = false;
        assertEquals(pitch, settings.style().pitchDegrees());
        assertNotEquals(MapStyle.DEFAULT.skyOcclusion(), settings.style().skyOcclusion());
    }
    @Test void cameraDirectionAndPitchRequireNewGeometry() {
        var before = new BakeContext(MapStyle.DEFAULT, MapDirection.NORTH, false);
        assertFalse(before.sameProjection(new BakeContext(MapStyle.DEFAULT, MapDirection.EAST, false)));
        assertFalse(before.sameProjection(new BakeContext(MapStyle.FLAT, MapDirection.NORTH, false)));
    }
}
