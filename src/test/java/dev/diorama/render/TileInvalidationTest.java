package dev.diorama.render;
import dev.diorama.MapDirection;
import dev.diorama.Projection;
import dev.diorama.bake.TileKey;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class TileInvalidationTest {
	@Test void rotatedRegionsContainChangedBlocksAcrossNegativeCoordinates() {
		for (var direction : MapDirection.values()) {
			var regions = TileInvalidation.regions(-17, 60, 60, -129, 16, 80, -96, true, false, 0, Set.of(TileKey.SURFACE), direction);
			assertEquals(1, regions.size());
			var r = regions.getFirst();
			for (int x : new int[] {-17, 16}) for (int z : new int[] {-129, -96}) {
				double u = direction.u(x + .5, z + .5), v = Projection.v(70, direction.depth(x + .5, z + .5));
				assertTrue(u >= r.minU() && u <= r.maxU());
				assertTrue(v >= r.minV() && v <= r.maxV());
			}
		}
	}
	@Test void buriedChangesSkipTheSurfaceAndDistantCaveSlices() {
		var regions = TileInvalidation.regions(0, -50, -50, 0, 0, -50, 0, false, false, 0, Set.of(TileKey.SURFACE, -40, 100), MapDirection.NORTH);
		assertEquals(1, regions.size());
		assertEquals(-40, regions.getFirst().layer());
	}
}
