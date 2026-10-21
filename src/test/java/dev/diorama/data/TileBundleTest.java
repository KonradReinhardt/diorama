package dev.diorama.data;

import dev.diorama.Projection;
import dev.diorama.bake.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.DeflaterOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class TileBundleTest {
	@TempDir Path folder;
	final TileKey key = new TileKey(TileKey.SURFACE, -1, -2);
	int[] plane(int color) {
		int[] pixels = new int[Projection.TILE_PIXELS * Projection.TILE_PIXELS];
		Arrays.fill(pixels, color);
		return pixels;
	}

	@Test void roundTripsPairedDataAndRejectsDamageAndWrongIdentity() throws Exception {
		Path path = folder.resolve("tile.omt2");
		TilePixels pair = new TilePixels(plane(0xffcccccc), plane(0xff124578));
		TileBundle.write(path, key, pair);
		assertTrue(pair.samePixels(TileBundle.read(path, key)));
		assertThrows(IOException.class, () -> TileBundle.read(path, key.parent()));
		Path other = folder.resolve("different-camera").resolve("tile.omt2");
		Files.createDirectories(other.getParent());
		Files.copy(path, other);
		assertThrows(IOException.class, () -> TileBundle.read(other, key));
		byte[] bytes = Files.readAllBytes(path);
		Files.write(path, Arrays.copyOf(bytes, bytes.length - 4));
		assertThrows(IOException.class, () -> TileBundle.read(path, key));
	}

	@Test void corruptBundleFallsBackToUntouchedLegacyAndUpgradesOnlyRevisitedPixels() throws Exception {
		Path legacy = folder.resolve("-1_-2.omt");
		var raw = ByteBuffer.allocate(Projection.TILE_PIXELS * Projection.TILE_PIXELS * 4).order(ByteOrder.LITTLE_ENDIAN);
		raw.asIntBuffer().put(plane(0xffabcdef));
		try (var data = new DataOutputStream(Files.newOutputStream(legacy))) {
			data.writeInt(0x4f4d5431); data.writeInt(Projection.TILE_PIXELS);
			try (var zipped = new DeflaterOutputStream(data)) { zipped.write(raw.array()); }
		}
		byte[] original = Files.readAllBytes(legacy);
		Files.write(folder.resolve("-1_-2.omt2"), new byte[]{1, 2, 3});
		TileStore store = new TileStore(folder);
		assertNull(store.readPixels(key).night());
		int[] day = new int[1024], night = new int[1024];
		Arrays.fill(day, 0xffeeeeee); Arrays.fill(night, 0xff334455);
		store.baked(TileImage.partial(key, List.of(new TileImage.Patch(0, 0, new int[][]{day}, new byte[0], null, new int[][]{night}))));
		store.close();
		assertArrayEquals(original, Files.readAllBytes(legacy));
		TileStore reopened = new TileStore(folder);
		TilePixels pair = reopened.readPixels(key);
		assertEquals(0xffeeeeee, pair.day()[0]);
		assertEquals(0xff334455, pair.night()[0]);
		assertEquals(0xffabcdef, pair.day()[100]);
		assertEquals(0, pair.night()[100]);
		TilePixels overview = reopened.readPixels(key.parent());
		assertNotNull(overview.night());
		reopened.close();
	}

	@Test void linearMipsKeepCoverageIndependentOfDarkness() {
		int midpoint = Mipmaps.average(0xffffffff, 0xffffffff, 0xff000000, 0xff000000);
		assertEquals(255, midpoint >>> 24);
		assertTrue((midpoint & 255) >= 187 && (midpoint & 255) <= 188);
		assertEquals(0xff000000, Mipmaps.average(0xff000000, 0xff000000, 0xff000000, 0xff000000));
		assertEquals(0x3fffffff, Mipmaps.average(0xffffffff, 0, 0, 0));
	}
}
