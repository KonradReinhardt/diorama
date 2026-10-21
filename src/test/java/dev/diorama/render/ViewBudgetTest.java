package dev.diorama.render;

import dev.diorama.Projection;
import dev.diorama.bake.TileKey;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ViewBudgetTest {
	/** A 1920x1080 GUI (a 4K screen at scale 2) centred at the origin. */
	private static MapViewport.View view(double zoom) {
		return new MapViewport.View(0, 0, 1920, 1080, 0, 0, zoom, TileKey.SURFACE);
	}

	@Test void countsTheTilesInViewAndTheRingAroundThem() {
		// 960 units across, -480..480: tiles -4..3, and -5..4 with the ring, 10; 540 down, -270..270: -3..2, and 8 with the ring.
		assertEquals(10 * 8, MapViewport.tilesAround(view(2), 0));
		assertTrue(MapViewport.tilesAround(view(2), 1) < MapViewport.tilesAround(view(2), 0));
	}

	@Test void aViewThatFitsKeepsItsDetail() {
		assertEquals(0, MapViewport.fitting(view(2), 0, 200));
		assertEquals(2, MapViewport.fitting(view(0.2), 2, 10_000));
	}

	@Test void aViewTooBigForTheBudgetMovesToCoarserTilesUntilItFits() {
		MapViewport.View far = view(0.5);
		long budget = 120;
		int lod = MapViewport.fitting(far, 0, budget);
		assertTrue(lod > 0, "stayed on detailed tiles");
		assertTrue(MapViewport.tilesAround(far, lod) <= budget);
		assertTrue(MapViewport.tilesAround(far, lod - 1) > budget, "went coarser than needed");
	}

	@Test void neverPastTheCoarsestLevel() {
		assertEquals(TileKey.MAX_LOD, MapViewport.fitting(view(0.0001), 0, 1));
		assertTrue(Projection.TILE_UNITS > 0);
	}
}
