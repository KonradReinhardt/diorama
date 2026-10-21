package dev.diorama;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class MapDirectionTest {
	@Test void cardinalDirectionsHaveCorrectScreenAxes() {
		assertEquals(-1, MapDirection.NORTH.depth(0, -1));
		assertEquals(-1, MapDirection.EAST.depth(1, 0));
		assertEquals(-1, MapDirection.SOUTH.depth(0, 1));
		assertEquals(-1, MapDirection.WEST.depth(-1, 0));
		assertEquals(1, MapDirection.EAST.u(0, 1));
	}
	@Test void roundTripsCoordinatesAndBlockCentersAcrossNegativeChunkBoundaries() {
		for (var direction : MapDirection.values()) {
			for (int x : new int[] {-129, -128, -17, -16, -1, 0, 15, 16, 128}) {
				for (int z : new int[] {-33, -16, -1, 0, 16, 97}) {
					int u = direction.blockU(x, z), depth = direction.blockDepth(x, z);
					assertEquals(x, direction.worldBlockX(u, depth));
					assertEquals(z, direction.worldBlockZ(u, depth));
					double a = direction.u(x + .25, z + .75), b = direction.depth(x + .25, z + .75);
					assertEquals(x + .25, direction.worldX(a, b));
					assertEquals(z + .75, direction.worldZ(a, b));
				}
			}
		}
	}
}
