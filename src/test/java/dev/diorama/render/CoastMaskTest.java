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

	@Test void exactAlongAnyDirection() {
		// A chamfer distance would be off by several percent along these; the transform isn't.
		int size = 64;
		byte[] kind = new byte[size * size];
		kind[0] = LAND;
		float[] out = new float[size * size];
		CoastMask.distances(kind, out, size, 0.5F, 0.75F);
		for (int y = 0; y < size; y += 7) {
			for (int x = 0; x < size; x += 5) {
				assertEquals(Math.hypot(x * 0.5, y * 0.75), out[y * size + x], 1e-3, x + "," + y);
			}
		}
	}

	@Test void unknownGroundIsNoShore() {
		int size = 4;
		byte[] kind = {WATER, WATER, UNKNOWN, UNKNOWN, WATER, WATER, UNKNOWN, UNKNOWN, WATER, WATER, WATER, WATER, WATER, WATER, WATER, LAND};
		float[] out = new float[size * size];
		CoastMask.distances(kind, out, size, 1, 1);
		assertTrue(out[0] > 3, "the nearest land is the far corner, not the unknown beside it");
		assertEquals(1, out[3 * size + 2], 1e-5);
	}

	@Test void openSeaByTheOceanNotInARiver() {
		// By an ocean, open water lies OPEN blocks out, so the two distances add up to about that.
		assertEquals(1, CoastMask.openSea(2, CoastMask.OPEN - 2), 1e-9);
		assertEquals(1, CoastMask.openSea(0, CoastMask.OPEN), 1e-9);
		// A river ten blocks wide has no open water anywhere near.
		assertEquals(0, CoastMask.openSea(3, 60), 1e-9);
		double lake = CoastMask.openSea(3, CoastMask.OPEN + 4);
		assertTrue(lake > 0 && lake < 1, "a lake with a small open middle gets some waves");
	}

	@Test void distancesAreFinestNearTheShore() {
		for (double texel : new double[] {0.25, 1, 8, 64}) {
			double reach = CoastMask.reach(texel);
			for (double d : new double[] {0, 0.5, 1, 3, 16, reach / 2, reach}) {
				double red = CoastMask.encode(d, texel) / 255.0;
				double back = red * red * reach;
				// Within one step of the byte, which near the shore is a sliver of a block.
				double step = 2 * Math.sqrt(d / reach) * reach / 255 + reach / (255.0 * 255.0);
				assertEquals(d, back, step + 1e-9, texel + " " + d);
			}
			assertTrue(CoastMask.reach(texel) >= 32, "waves start 16 blocks out");
		}
	}
}
