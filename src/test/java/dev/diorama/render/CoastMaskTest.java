package dev.diorama.render;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CoastMaskTest {
	private static final byte LAND = 1, WATER = 2, UNKNOWN = 0;

	@Test void measuresAcrossAndDownTheWatersPlane() {
		int size = 9;
		byte[] kind = new byte[size * size];
		java.util.Arrays.fill(kind, WATER);
		kind[4 * size + 4] = LAND;
		float[] out = new float[size * size];
		// A texel a block across and, three quarters of a unit down, a block deep.
		CoastMask.distances(kind, out, size, 1, 4 / 3F);
		assertEquals(0, out[4 * size + 4]);
		assertEquals(3, out[4 * size + 7], 1e-5);
		assertEquals(4, out[7 * size + 4], 1e-5);
		assertEquals(5, out[7 * size + 7], 1e-5);
	}

	@Test void unknownGroundIsNoShore() {
		int size = 4;
		byte[] kind = {WATER, WATER, UNKNOWN, UNKNOWN, WATER, WATER, UNKNOWN, UNKNOWN, WATER, WATER, WATER, WATER, WATER, WATER, WATER, LAND};
		float[] out = new float[size * size];
		CoastMask.distances(kind, out, size, 1, 1);
		assertTrue(out[0] > 3, "the nearest land is the far corner, not the unknown beside it");
		assertEquals(1, out[3 * size + 2], 1e-5);
	}
}
