package dev.diorama.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.diorama.Settings;
import dev.diorama.region.MapRegion;
import dev.diorama.region.RegionStyle;
import dev.diorama.waypoint.BannerDesign;
import org.junit.jupiter.api.Test;

class RegionLookTest {
	@Test void theRegionStyleIsItsOwnChoiceNotTheRenderStyles() {
		Settings s = new Settings();
		assertEquals(RegionStyle.WATERCOLOR, s.regionStyle);
		s.renderStyle = Settings.RenderStyle.PLAIN;
		s.fogStyle = Settings.FogStyle.ANCIENT_MAP;
		assertEquals(RegionStyle.WATERCOLOR, s.regionStyle, "the render and fog styles leave it alone");
		// regions.fsh reads the style from three bits of the mask.
		for (RegionStyle style : RegionStyle.values()) {
			assertTrue(style.shaderIndex() >= 0 && style.shaderIndex() < 8);
		}
		// Named apart from the render styles, so the two lists never read alike.
		for (RegionStyle style : RegionStyle.values()) {
			for (Settings.RenderStyle render : Settings.RenderStyle.values()) {
				assertNotEquals(render.name(), style.name());
			}
		}
	}

	@Test void aRegionWithoutABannerShowsOneInItsDye() {
		MapRegion region = new MapRegion();
		region.dye = BannerDesign.Dye.CYAN;
		assertEquals(BannerDesign.plain(BannerDesign.Dye.CYAN), region.design());
		BannerDesign own = BannerDesign.plain(BannerDesign.Dye.BLACK);
		region.banner = own;
		assertEquals(own, region.design());
	}

	@Test void darkDyesAreLightenedForNames() {
		int black = BannerDesign.Dye.BLACK.argb();
		int lightened = RegionLabels.readable(black);
		assertNotEquals(black, lightened);
		assertTrue((lightened & 0xFF) > (black & 0xFF));
		assertEquals(BannerDesign.Dye.YELLOW.argb(), RegionLabels.readable(BannerDesign.Dye.YELLOW.argb()), "bright dyes stay as they are");
	}
}
