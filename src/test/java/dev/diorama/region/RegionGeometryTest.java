package dev.diorama.region;

import static org.junit.jupiter.api.Assertions.*;

import dev.diorama.MapDirection;
import dev.diorama.Projection;
import java.util.List;
import org.junit.jupiter.api.Test;

class RegionGeometryTest {
	static Ring square(float x0, float z0, float x1, float z1, int y) {
		return new Ring(new float[] {x0, x1, x1, x0}, new float[] {z0, z0, z1, z1}, new short[] {(short) y, (short) y, (short) y, (short) y});
	}

	@Test void aHoleIsTakenOutOfTheArea() {
		List<Ring> rings = List.of(square(0, 0, 10, 10, 64), square(2, 2, 4, 4, 64));
		assertEquals(96, RegionGeometry.area(rings), 1e-9);
		assertTrue(RegionGeometry.contains(rings, 1, 1));
		assertFalse(RegionGeometry.contains(rings, 3, 3), "inside the hole");
		assertFalse(RegionGeometry.contains(rings, 11, 1));
		assertArrayEquals(new double[] {0, 0, 10, 10}, RegionGeometry.bounds(rings));
	}

	@Test void separatePartsAddUp() {
		List<Ring> rings = List.of(square(0, 0, 2, 2, 64), square(10, 10, 13, 13, 64));
		assertEquals(13, RegionGeometry.area(rings), 1e-9);
	}

	@Test void theLabelSitsInsideACShape() {
		// A C opening to the east: its bounds' middle is in the mouth, outside.
		float[] x = {0, 30, 30, 10, 10, 30, 30, 0};
		float[] z = {0, 0, 10, 10, 20, 20, 30, 30};
		List<Ring> c = List.of(new Ring(x, z, new short[8]));
		assertFalse(RegionGeometry.contains(c, 15, 15));
		double[] label = RegionGeometry.label(c, 0.5);
		assertTrue(RegionGeometry.contains(c, label[0], label[1]), "label at " + label[0] + ", " + label[1]);
	}

	@Test void simplifyingKeepsCornersAndDropsTheWobble() {
		// A square traced with a point every half block, slightly shaky.
		int perSide = 40;
		double[] x = new double[perSide * 4], z = new double[perSide * 4];
		int[] y = new int[perSide * 4];
		for (int i = 0; i < perSide; i++) {
			double t = i * 0.5, shake = (i % 2 == 0 ? 0.1 : -0.1);
			x[i] = t; z[i] = shake;
			x[perSide + i] = 20 + shake; z[perSide + i] = t;
			x[2 * perSide + i] = 20 - t; z[2 * perSide + i] = 20 + shake;
			x[3 * perSide + i] = shake; z[3 * perSide + i] = 20 - t;
		}
		Ring ring = RegionGeometry.simplify(x, z, y, RegionGeometry.TOLERANCE);
		assertNotNull(ring);
		assertEquals(4, ring.size());
		assertEquals(400, Math.abs(RegionGeometry.signedArea(ring)), 1e-9);
		for (int i = 0; i < ring.size(); i++) {
			assertEquals(0, ring.x(i) % RegionGeometry.GRID, 1e-9, "on the half-block grid");
		}
	}

	@Test void scanFillsCellsWhoseMiddleIsInside() {
		// A square from 1 to 4 holds the middles of cells 1, 2 and 3; a hole from 2 to 3 takes cell 2 out.
		boolean[][] in = new boolean[6][6];
		RegionGeometry.scan(new float[][] {{1, 4, 4, 1}, {2, 3, 3, 2}}, new float[][] {{1, 1, 4, 4}, {2, 2, 3, 3}}, 6, 6,
			(row, from, to) -> { for (int i = from; i < to; i++) in[row][i] = true; });
		for (int j = 0; j < 6; j++) {
			for (int i = 0; i < 6; i++) {
				boolean expected = i >= 1 && i <= 3 && j >= 1 && j <= 3 && !(i == 2 && j == 2);
				assertEquals(expected, in[j][i], "cell " + i + ", " + j);
			}
		}
	}

	@Test void aStrokeLandsOnTheGroundItWasDrawnOn() {
		// Drawn over ground at height 70 facing north: undoing the projection at that height gives the square back.
		int y = 70;
		double[] u = {0, 16, 16, 0}, v = new double[4];
		double[] zs = {0, 0, 16, 16};
		for (int i = 0; i < 4; i++) v[i] = Projection.v(y, zs[i]);
		Ring ring = RegionGeometry.fromMap(u, v, (a, b) -> y, 0, MapDirection.NORTH);
		assertNotNull(ring);
		assertEquals(256, Math.abs(RegionGeometry.signedArea(ring)), 1e-6);
		for (int i = 0; i < 4; i++) {
			assertEquals(u[i], ring.x(i), 1e-6);
			assertEquals(zs[i], ring.z(i), 1e-6);
			assertEquals(y, ring.y(i));
		}
	}

	@Test void unknownHeightsTakeTheNearestKnownOne() {
		int u = RegionGeometry.UNKNOWN;
		int[] y = {u, 70, u, u, 80, u};
		RegionGeometry.fillUnknown(y, 64);
		assertArrayEquals(new int[] {70, 70, 70, 80, 80, 80}, y);
		int[] none = {u, u, u};
		RegionGeometry.fillUnknown(none, 64);
		assertArrayEquals(new int[] {64, 64, 64}, none);
	}

	@Test void editingARingKeepsTheOthersVertices() {
		Ring ring = square(0, 0, 10, 10, 64);
		Ring moved = ring.with(2, 20, 10, 70);
		assertEquals(20, moved.x(2));
		assertEquals(70, moved.y(2));
		assertEquals(10, ring.x(2), "the ring itself never changes");
		Ring more = ring.inserting(1, 12, 5, 66);
		assertEquals(5, more.size());
		assertEquals(12, more.x(2));
		assertEquals(10, more.x(3));
		assertEquals(10, more.z(3), "the vertex after the new one follows it");
		Ring fewer = more.without(2);
		assertNotNull(fewer);
		assertEquals(100, Math.abs(RegionGeometry.signedArea(fewer)), 1e-9);
		assertNull(square(0, 0, 1, 1, 64).without(0).without(0), "a ring keeps three corners");
		Ring shifted = ring.moved(5, -2, i -> 80);
		assertEquals(5, shifted.x(0));
		assertEquals(-2, shifted.z(0));
		assertEquals(80, shifted.y(3));
	}

	@Test void distanceIsZeroInside() {
		List<Ring> rings = List.of(square(0, 0, 10, 10, 64));
		assertEquals(0, RegionGeometry.distance(rings, 5, 5));
		assertEquals(3, RegionGeometry.distance(rings, 13, 5), 1e-9);
	}

	@Test void slipsOfTheMouseAreNotRegions() {
		assertNull(RegionGeometry.fromMap(new double[] {0, 1, 1}, new double[] {0, 0, 1}, (a, b) -> 64, 64, MapDirection.NORTH));
	}
}
