package dev.diorama.data;

import dev.diorama.Projection;
import dev.diorama.bake.Surface;
import dev.diorama.bake.TileImage;
import dev.diorama.bake.TileKey;
import dev.diorama.bake.TilePixels;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SurfacePlaneTest {
	@TempDir Path folder;
	private static final int LENGTH = Projection.TILE_PIXELS * Projection.TILE_PIXELS;

	private static TileImage image(TileKey key, int color, byte surface) {
		int[] pixels = new int[LENGTH];
		Arrays.fill(pixels, color);
		byte[] plane = new byte[LENGTH];
		Arrays.fill(plane, surface);
		return TileImage.full(key, new int[][] {pixels}, new byte[0], null, null, plane);
	}

	@Test void savedBesideTheBundleAndReadBack() {
		var key = new TileKey(TileKey.SURFACE, 3, -4);
		TileStore store = new TileStore(folder);
		store.baked(image(key, 0xFF336699, Surface.of(true)));
		store.close();
		assertTrue(Files.isRegularFile(folder.resolve("3_-4.omts")));
		TileStore reopened = new TileStore(folder);
		TilePixels pixels = reopened.readPixels(key);
		assertNotNull(pixels.surface());
		assertEquals(Surface.of(true), pixels.surface()[12345]);
		reopened.close();
	}

	@Test void patchesKeepTheRestOfThePlaneAndClearWhatTheyDontRecord() {
		var key = new TileKey(TileKey.SURFACE, 0, 0);
		TileStore store = new TileStore(folder);
		store.baked(image(key, 0xFF336699, Surface.of(false)));
		int edge = TileImage.BLOCK;
		int[] patch = new int[edge * edge];
		Arrays.fill(patch, 0xFF112233);
		byte[] water = new byte[edge * edge];
		Arrays.fill(water, Surface.of(true));
		store.baked(TileImage.partial(key, List.of(
			new TileImage.Patch(1, 0, new int[][] {patch}, new byte[0], null, null, water),
			new TileImage.Patch(2, 0, new int[][] {patch}, new byte[0], null))));
		store.close();
		TilePixels pixels = new TileStore(folder).readPixels(key);
		assertEquals(Surface.of(false), pixels.surface()[0]);
		assertEquals(Surface.of(true), pixels.surface()[edge]);
		assertEquals(0, pixels.surface()[2 * edge]);
	}

	@Test void ignoredOnceAnotherBuildRewroteTheBundle() throws Exception {
		var key = new TileKey(TileKey.SURFACE, 0, 0);
		TileStore store = new TileStore(folder);
		store.baked(image(key, 0xFF336699, Surface.of(true)));
		store.close();
		// A build that doesn't know surfaces writes other pixels and leaves the plane alone.
		int[] other = new int[LENGTH];
		Arrays.fill(other, 0xFF000000);
		TileBundle.write(folder.resolve("0_0.omt2"), key, new TilePixels(other, null));
		assertNull(new TileStore(folder).readPixels(key).surface());
	}
}
