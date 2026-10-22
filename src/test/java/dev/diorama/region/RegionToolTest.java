package dev.diorama.region;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class RegionToolTest {
	@Test void theLassoTakesAPointEveryFewPixels() {
		RegionTool tool = new RegionTool();
		tool.begin(0, 0);
		// At 2 GUI pixels per unit, a point every unit; smaller moves wait.
		tool.extend(0.4, 0, 2);
		assertEquals(1, tool.count());
		tool.extend(1, 0, 2);
		tool.extend(1, 1, 2);
		tool.extend(0, 1, 2);
		double[][] outline = tool.finish();
		assertNotNull(outline);
		assertArrayEquals(new double[] {0, 1, 1, 0}, outline[0]);
		assertFalse(tool.drawing());
	}

	@Test void aShortLassoIsNoOutline() {
		RegionTool tool = new RegionTool();
		tool.begin(0, 0);
		tool.extend(5, 0, 1);
		assertNull(tool.finish());
	}

	@Test void clickingTheFirstCornerClosesThePolygon() {
		RegionTool tool = new RegionTool();
		long t = 0;
		assertNull(tool.click(0, 0, 1, t += 1_000_000_000L));
		assertNull(tool.click(20, 0, 1, t += 1_000_000_000L));
		assertNull(tool.click(20, 20, 1, t += 1_000_000_000L));
		// Within the reach of the first corner, at 1 GUI pixel per unit.
		double[][] outline = tool.click(3, 2, 1, t += 1_000_000_000L);
		assertNotNull(outline);
		assertEquals(3, outline[0].length);
	}

	@Test void aDoubleClickClosesThePolygon() {
		RegionTool tool = new RegionTool();
		tool.click(0, 0, 1, 0);
		tool.click(20, 0, 1, 1_000_000_000L);
		assertNull(tool.click(20, 20, 1, 2_000_000_000L));
		double[][] outline = tool.click(20, 20, 1, 2_100_000_000L);
		assertNotNull(outline);
		assertEquals(3, outline[0].length, "the second click of a double click adds no corner");
	}

	@Test void backspaceAndEnter() {
		RegionTool tool = new RegionTool();
		tool.click(0, 0, 1, 0);
		tool.click(20, 0, 1, 1_000_000_000L);
		tool.click(30, 30, 1, 2_000_000_000L);
		tool.removeLast();
		assertEquals(2, tool.count());
		assertNull(tool.close(), "two corners make no outline");
		assertFalse(tool.drawing());
	}
}
