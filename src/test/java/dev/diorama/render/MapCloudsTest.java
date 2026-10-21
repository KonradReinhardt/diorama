package dev.diorama.render;

import dev.diorama.MapDirection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MapCloudsTest {
	/** The fields as clouds.fsh reads them back. */
	private static int[] decode(int params) {
		int a = params >>> 24, r = params >> 16 & 255, g = params >> 8 & 255, b = params & 255;
		return new int[] {a >> 4, a & 15, r & 63, r >> 6, g & 63, g >> 6, b & 63, b >> 6 & 1};
	}

	@Test void packsEveryFieldApart() {
		int[] f = decode(MapClouds.params(11, 128 * 5, 128 * 47, 0b101101, 1, 1, MapDirection.WEST, true));
		assertArrayEquals(new int[] {11, 15, 5, 3, 47, 3, 0b101101, 1}, f);
		f = decode(MapClouds.params(0, 0, 0, 0, 0, 0, MapDirection.NORTH, false));
		assertArrayEquals(new int[] {0, 0, 0, 0, 0, 0, 0, 0}, f);
	}

	@Test void originWrapsWithTheWeathersPeriod() {
		// A whole period further, in either direction, is the same origin to the shader.
		for (double u : new double[] {0, 128, 4096, -128, -8192 * 3 + 256}) {
			int here = MapClouds.params(3, u, -u * 0.75, 0, 1, 0, MapDirection.NORTH, false);
			int moved = MapClouds.params(3, u + MapClouds.PERIOD_U * 2, -u * 0.75 - MapClouds.PERIOD_V, 0, 1, 0, MapDirection.NORTH, false);
			assertEquals(here, moved);
		}
		// Down the map the period is three quarters as many units: the same depth of world.
		assertEquals(MapClouds.PERIOD_U * 3 / 4, MapClouds.PERIOD_V);
		assertEquals(0, MapClouds.PERIOD_V % MapClouds.ORIGIN_STEP);
		assertTrue(MapClouds.PERIOD_V / MapClouds.ORIGIN_STEP <= 64);
	}

	@Test void coarseOriginsStillDecode() {
		// Zoomed far out the origin snaps to more than a period: across that is a whole number of
		// periods, so zero; down it still lands on whole steps of the shorter period.
		int[] f = decode(MapClouds.params(9, 16384 * 3, 16384 * -2, 0, 1, 0, MapDirection.NORTH, false));
		assertEquals(0, f[2]);
		assertEquals(Math.floorMod(16384 * -2, MapClouds.PERIOD_V) / MapClouds.ORIGIN_STEP, f[4]);
		assertEquals(0, Math.floorMod(16384 * -2, MapClouds.PERIOD_V) % MapClouds.ORIGIN_STEP);
	}
}
