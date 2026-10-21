package dev.diorama.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.diorama.Settings;
import dev.diorama.region.RegionStyle;
import dev.diorama.waypoint.BannerDesign;
import org.junit.jupiter.api.Test;

class RegionLookTest {
	@Test void leftAutomaticRegionsFollowTheRenderStyleUnlessTheFogIsAnOldMap() {
		Settings s = new Settings();
		s.fogStyle = Settings.FogStyle.CLOUDS;
		s.renderStyle = Settings.RenderStyle.PLAIN;
		assertEquals(RegionStyle.PLAIN, MapViewport.regionStyle(s));
		s.renderStyle = Settings.RenderStyle.OUTLINED;
		assertEquals(RegionStyle.OUTLINED, MapViewport.regionStyle(s));
		s.renderStyle = Settings.RenderStyle.PAINTED;
		assertEquals(RegionStyle.PAINTED, MapViewport.regionStyle(s));
		s.renderStyle = Settings.RenderStyle.PAINTED_NO_OUTLINES;
		assertEquals(RegionStyle.PAINTED_NO_INK, MapViewport.regionStyle(s));
		s.fogStyle = Settings.FogStyle.ANCIENT_MAP;
		assertEquals(RegionStyle.ANTIQUE, MapViewport.regionStyle(s));
		s.fogStyle = Settings.FogStyle.ANCIENT_MAP_COLOR;
		assertEquals(RegionStyle.ANTIQUE_COLOR, MapViewport.regionStyle(s));
	}

	@Test void aStyleChosenWinsOverTheRenderStyle() {
		Settings s = new Settings();
		s.renderStyle = Settings.RenderStyle.PAINTED;
		s.fogStyle = Settings.FogStyle.ANCIENT_MAP;
		s.regionStyle = RegionStyle.OUTLINED;
		assertEquals(RegionStyle.OUTLINED, MapViewport.regionStyle(s));
		// A region's own style wins over the map's.
		assertEquals(RegionStyle.PAINTED, RegionStyle.PAINTED.or(MapViewport.regionStyle(s)));
		assertEquals(RegionStyle.OUTLINED, RegionStyle.AUTO.or(MapViewport.regionStyle(s)));
		// regions.fsh reads the style from three bits of the mask.
		for (RegionStyle style : RegionStyle.values()) {
			if (style != RegionStyle.AUTO) assertTrue(style.shaderIndex() >= 0 && style.shaderIndex() < 8);
		}
	}

	@Test void darkDyesAreLightenedForNames() {
		int black = BannerDesign.Dye.BLACK.argb();
		int lightened = RegionLabels.readable(black);
		assertNotEquals(black, lightened);
		assertTrue((lightened & 0xFF) > (black & 0xFF));
		assertEquals(BannerDesign.Dye.YELLOW.argb(), RegionLabels.readable(BannerDesign.Dye.YELLOW.argb()), "bright dyes stay as they are");
	}
}
