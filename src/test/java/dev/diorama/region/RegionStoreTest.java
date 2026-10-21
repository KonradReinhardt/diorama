package dev.diorama.region;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import dev.diorama.waypoint.BannerDesign;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RegionStoreTest {
	@TempDir Path folder;

	private static MapRegion region(String name, String dimension) {
		MapRegion region = new MapRegion();
		region.name = name;
		region.dimension = dimension;
		region.dye = BannerDesign.Dye.LIGHT_BLUE;
		region.fill = MapRegion.Fill.STRONG;
		region.rings.add(RegionGeometryTest.square(0, 0, 10.5F, 10, 64));
		return region;
	}

	@Test void regionsSurviveASaveAndLoad() {
		Path file = this.folder.resolve("regions.json");
		RegionStore store = RegionStore.at(file);
		MapRegion home = region("Home", "minecraft:overworld");
		home.banner = BannerDesign.plain(BannerDesign.Dye.BLUE);
		assertTrue(store.commit(null, home));
		MapRegion read = RegionStore.at(file).find(home.id);
		assertNotNull(read);
		assertEquals("Home", read.name);
		assertEquals(BannerDesign.Dye.LIGHT_BLUE, read.dye);
		assertEquals(MapRegion.Fill.STRONG, read.fill);
		assertEquals(home.banner, read.banner);
		assertEquals(105, read.area(), 1e-9);
		assertEquals(10.5F, read.rings.get(0).x(1));
		assertEquals(64, read.rings.get(0).y(2));
	}

	@Test void undoAndRedoWalkTheHistory() {
		RegionStore store = RegionStore.at(this.folder.resolve("regions.json"));
		String world = "minecraft:overworld";
		MapRegion a = region("A", world);
		store.commit(null, a);
		MapRegion renamed = store.find(a.id).copy();
		renamed.name = "B";
		store.commit(store.find(a.id).copy(), renamed);
		assertEquals("B", store.find(a.id).name);
		assertTrue(store.undo(world));
		assertEquals("A", store.find(a.id).name);
		assertTrue(store.undo(world));
		assertNull(store.find(a.id));
		assertFalse(store.undo(world));
		assertTrue(store.redo(world));
		assertTrue(store.redo(world));
		assertEquals("B", store.find(a.id).name);
		// History stays with its dimension.
		assertFalse(store.undo("minecraft:the_nether"));
		long before = store.revision();
		store.commit(null, region("Nether", "minecraft:the_nether"));
		assertTrue(store.revision() > before);
		assertFalse(store.canUndo(world), "a change in another dimension starts the history over");
	}

	@Test void aBadEntryCostsOnlyItself() throws IOException {
		Path file = this.folder.resolve("regions.json");
		Files.writeString(file, "{\"version\":1,\"regions\":["
			+ "{\"name\":\"Good\",\"dimension\":\"minecraft:overworld\",\"dye\":\"lime\",\"rings\":[[0,0,64,8,0,64,8,8,64]]},"
			+ "{\"name\":\"No rings\",\"dimension\":\"minecraft:overworld\"},"
			+ "{\"name\":\"Bad ring\",\"dimension\":\"minecraft:overworld\",\"rings\":[[0,0,64,8]]},"
			+ "{\"name\":\"Odd dye\",\"dimension\":\"minecraft:overworld\",\"dye\":\"octarine\",\"fill\":\"SPARKLY\",\"rings\":[[0,0,64,8,0,64,8,8,64]]}"
			+ "]}", StandardCharsets.UTF_8);
		RegionStore store = RegionStore.at(file);
		List<String> names = store.all().stream().map(r -> r.name).toList();
		assertEquals(List.of("Good", "Odd dye"), names);
		assertEquals(BannerDesign.Dye.RED, store.all().get(1).dye);
		assertEquals(MapRegion.Fill.NORMAL, store.all().get(1).fill);
		try (var files = Files.list(this.folder)) {
			assertTrue(files.anyMatch(p -> p.getFileName().toString().startsWith("regions.json.unreadable-")), "a copy is kept aside");
		}
	}

	@Test void aNewerFileIsShownButNeverWrittenOver() throws IOException {
		Path file = this.folder.resolve("regions.json");
		String newer = "{\"version\":2,\"regions\":[{\"name\":\"Future\",\"dimension\":\"minecraft:overworld\",\"rings\":[[0,0,64,8,0,64,8,8,64]],\"newField\":1}]}";
		Files.writeString(file, newer, StandardCharsets.UTF_8);
		RegionStore store = RegionStore.at(file);
		assertTrue(store.readOnly());
		assertEquals(1, store.all().size());
		assertFalse(store.commit(null, region("Mine", "minecraft:overworld")));
		assertEquals(newer, Files.readString(file, StandardCharsets.UTF_8));
	}

	@Test void ringsAreWrittenCompactly() {
		String json = RegionJson.write(List.of(region("Home", "minecraft:overworld")));
		var ring = JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("regions").get(0).getAsJsonObject().getAsJsonArray("rings").get(0);
		assertEquals("[0,0,64,10.5,0,64,10.5,10,64,0,10,64]", ring.toString());
		assertTrue(json.contains("\"dye\":\"light_blue\""), json);
	}
}
