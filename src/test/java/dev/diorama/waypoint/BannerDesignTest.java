package dev.diorama.waypoint;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.util.List;
import org.junit.jupiter.api.Test;

class BannerDesignTest {
	private static final BannerDesign CREEPER = new BannerDesign(BannerDesign.Dye.LIME, List.of(
		new BannerDesign.Layer("minecraft:creeper", BannerDesign.Dye.BLACK),
		new BannerDesign.Layer("somemod:wyvern", BannerDesign.Dye.LIGHT_BLUE)
	));

	@Test void bannerSurvivesASaveAndLoad() {
		Waypoint waypoint = new Waypoint("Base", 1, 64, -2, "minecraft:overworld", Waypoint.PALETTE[3]);
		waypoint.banner = CREEPER;
		String json = WaypointJson.write(List.of(waypoint));
		assertTrue(json.contains("\"pattern\": \"somemod:wyvern\""), json);
		assertTrue(json.contains("\"color\": \"light_blue\""), json);
		Waypoint read = WaypointJson.read(JsonParser.parseString(json).getAsJsonArray().get(0));
		assertEquals(CREEPER, read.banner);
		assertEquals(CREEPER, read.design());
	}

	@Test void oldFilesLoadAsPlainBannersAndStayUnchanged() {
		String old = "{\"id\":\"7b0c5d1e-3c39-4bd4-9f7b-0d4c2b0e7f11\",\"name\":\"Home\",\"initials\":\"H\",\"x\":3,\"y\":70,\"z\":4,"
			+ "\"dimension\":\"minecraft:overworld\",\"color\":-1750963,\"visible\":true,\"kind\":\"MANUAL\",\"created\":1}";
		Waypoint read = WaypointJson.read(JsonParser.parseString(old));
		assertNull(read.banner);
		assertEquals(BannerDesign.plain(BannerDesign.Dye.RED), read.design());
		// Nothing new is written for it, so an older version reads the file exactly as before.
		assertFalse(WaypointJson.write(List.of(read)).contains("banner"));
	}

	@Test void oldDeathMarkersGetTheDeathBanner() {
		Waypoint death = new Waypoint("Death", 0, 0, 0, "minecraft:overworld", 0xFF60646C);
		death.kind = Waypoint.Kind.DEATH;
		assertEquals(BannerDesign.death(), death.design());
	}

	@Test void unknownPatternsAndDyesAreKeptOrForgiven() {
		String json = "{\"name\":\"X\",\"dimension\":\"minecraft:overworld\",\"banner\":{\"base\":\"chartreuse\",\"layers\":["
			+ "{\"pattern\":\"gone:pattern\",\"color\":\"red\"},{\"color\":\"red\"},{\"pattern\":\"minecraft:cross\",\"color\":7}],\"future\":1}}";
		Waypoint read = WaypointJson.read(JsonParser.parseString(json));
		assertEquals(new BannerDesign(BannerDesign.Dye.WHITE, List.of(
			new BannerDesign.Layer("gone:pattern", BannerDesign.Dye.RED),
			new BannerDesign.Layer("minecraft:cross", BannerDesign.Dye.WHITE)
		)), read.banner);
	}

	@Test void paletteColoursMapToTheirDyes() {
		assertEquals(BannerDesign.Dye.WHITE, BannerDesign.Dye.nearest(0xFFFFFFFF));
		assertEquals(BannerDesign.Dye.BLUE, BannerDesign.Dye.nearest(0xFF3E63DD));
		assertEquals(BannerDesign.Dye.YELLOW, BannerDesign.Dye.nearest(0xFFFFC53D));
		for (BannerDesign.Dye dye : BannerDesign.Dye.values()) {
			assertEquals(dye, BannerDesign.Dye.nearest(dye.argb()));
		}
	}

	@Test void compactTextRoundTrips() {
		assertEquals(CREEPER, BannerDesign.decode(CREEPER.encode()));
		assertEquals(BannerDesign.death(), BannerDesign.decode(BannerDesign.death().encode()));
		assertNull(BannerDesign.decode("not base64!"));
		assertNull(BannerDesign.decode(java.util.Base64.getUrlEncoder().encodeToString("f;BAD ID,1".getBytes())));
	}

	@Test void layersAreCappedAtTheModLimit() {
		List<BannerDesign.Layer> many = java.util.Collections.nCopies(40, new BannerDesign.Layer("minecraft:cross", BannerDesign.Dye.RED));
		BannerDesign design = new BannerDesign(BannerDesign.Dye.WHITE, many);
		assertEquals(BannerDesign.MAX_LAYERS, design.layers().size());
		assertFalse(design.fitsVanilla());
	}
}
