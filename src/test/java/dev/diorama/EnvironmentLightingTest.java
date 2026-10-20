package dev.diorama;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EnvironmentLightingTest {
    @Test void fixedTimeSkyLitEndAndCavesKeepTheirExistingLight() {
        assertTrue(EnvironmentLighting.cycles(false, false, true, false));
        assertFalse(EnvironmentLighting.cycles(false, false, true, true));
        assertFalse(EnvironmentLighting.cycles(false, true, true, false));
        assertFalse(EnvironmentLighting.cycles(false, false, false, false));
        assertFalse(EnvironmentLighting.cycles(true, false, true, false));
    }
    @Test void skyFactorUsesEndpointsAndNeverExtrapolatesAStoppedClock() {
        assertEquals(0, EnvironmentLighting.fromSkyFactor(.24F, 0, 0).daylight());
        assertEquals(1, EnvironmentLighting.fromSkyFactor(1, 0, 0).daylight());
        assertEquals(.5F, EnvironmentLighting.fromSkyFactor(.62F, 0, 0).daylight(), .0001F);
        var frozen = EnvironmentLighting.fromSkyFactor(.62F, .3F, .7F);
        assertEquals(frozen, EnvironmentLighting.fromSkyFactor(.62F, .3F, .7F));
        assertEquals(.7F, frozen.rain());
        assertEquals(0xff8000ff, new EnvironmentLighting(.5F, 0).params(true));
        assertEquals(0xffff0000, EnvironmentLighting.DAY.params(false));
    }
}
