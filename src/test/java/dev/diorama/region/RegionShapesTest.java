package dev.diorama.region;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class RegionShapesTest {
	private static Ring square(float x0, float z0, float x1, float z1) {
		return RegionGeometryTest.square(x0, z0, x1, z1, 64);
	}

	@Test void addingOverlappingSquaresMakesOneOutline() {
		List<Ring> out = RegionShapes.combine(List.of(square(0, 0, 10, 10)), List.of(square(5, 5, 15, 15)), false);
		assertEquals(1, out.size());
		assertEquals(175, RegionGeometry.area(out), 1e-6);
		assertEquals(8, out.get(0).size(), "an L of two squares has eight corners");
	}

	@Test void cuttingABiteLeavesTheRest() {
		List<Ring> out = RegionShapes.combine(List.of(square(0, 0, 10, 10)), List.of(square(5, -5, 15, 5)), true);
		assertEquals(75, RegionGeometry.area(out), 1e-6);
		assertFalse(RegionGeometry.contains(out, 7, 2));
		assertTrue(RegionGeometry.contains(out, 2, 2));
	}

	@Test void cuttingTheMiddleLeavesAHole() {
		List<Ring> out = RegionShapes.combine(List.of(square(0, 0, 10, 10)), List.of(square(3, 3, 7, 7)), true);
		assertEquals(2, out.size());
		assertEquals(84, RegionGeometry.area(out), 1e-6);
		assertFalse(RegionGeometry.contains(out, 5, 5));
	}

	@Test void squaresTouchingAtACornerStayApart() {
		List<Ring> out = RegionShapes.combine(List.of(square(0, 0, 4, 4)), List.of(square(4, 4, 8, 8)), false);
		assertEquals(2, out.size());
		assertEquals(32, RegionGeometry.area(out), 1e-6);
	}

	@Test void neighbouringChunksBecomeOneSquare() {
		int[] y = {70, 70, 70, 70};
		List<Ring> chunks = List.of(RegionShapes.square(0, 0, 16, 16, y), RegionShapes.square(16, 0, 32, 16, y),
			RegionShapes.square(0, 16, 16, 32, y), RegionShapes.square(16, 16, 32, 32, y));
		List<Ring> out = RegionShapes.combine(List.of(), chunks, false);
		assertEquals(1, out.size());
		assertEquals(4, out.get(0).size());
		assertEquals(1024, RegionGeometry.area(out), 1e-6);
		assertEquals(70, out.get(0).y(0), "corners keep the heights they came with");
	}

	@Test void cuttingEverythingLeavesNothing() {
		assertTrue(RegionShapes.combine(List.of(square(2, 2, 4, 4)), List.of(square(0, 0, 10, 10)), true).isEmpty());
	}
}
