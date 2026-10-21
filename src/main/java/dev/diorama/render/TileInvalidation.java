package dev.diorama.render;
import dev.diorama.Projection;
import dev.diorama.bake.TileBaker;
import dev.diorama.bake.TileKey;
import java.util.*;

/** Pure world-to-camera dirty-region geometry, separate from queues and GPU state. */
final class TileInvalidation {
	record Region(int layer, double minU, double minV, double maxU, double maxV) {}
	private static final int LIGHT_REACH = 15;
	static List<Region> regions(int x0, int y0, int caveY0, int z0, int x1, int y1, int z1,
		boolean visibleFromSky, boolean roofless, int roof, Set<Integer> layers, dev.diorama.MapDirection direction) {
		return regions(x0, y0, caveY0, z0, x1, y1, z1, visibleFromSky, roofless, roof, layers, direction, false);
	}
	static List<Region> regions(int x0, int y0, int caveY0, int z0, int x1, int y1, int z1,
		boolean visibleFromSky, boolean roofless, int roof, Set<Integer> layers, dev.diorama.MapDirection direction, boolean lightOnly) {
		List<Region> regions = new ArrayList<>();
		int au = direction.blockU(x0, z0), av = direction.blockDepth(x0, z0);
		int bu = direction.blockU(x1, z1), bv = direction.blockDepth(x1, z1);
		x0 = Math.min(au, bu); x1 = Math.max(au, bu);
		z0 = Math.min(av, bv); z1 = Math.max(av, bv);

		for (int layer : layers) {
			// Under a roof the sky view is lit like a cave, so light reaches it the same way.
			boolean cave = layer != TileKey.SURFACE || roofless;
			if (!cave && !visibleFromSky) {
				continue;
			}
			boolean light = cave || lightOnly;
			int reachX = light ? LIGHT_REACH : TileBaker.SHADOW_REACH_X;
			int reachZ = light ? LIGHT_REACH : TileBaker.SHADOW_REACH_Z;
			int reachY = light ? LIGHT_REACH : TileBaker.SHADOW_REACH_Y;
			double top = y1 + 2 + (light ? LIGHT_REACH : 0);
			double bottom = (cave ? caveY0 : y0) - 1 - reachY;
			if (layer != TileKey.SURFACE) {
				top = Math.min(top, layer);
				bottom = Math.max(bottom, layer - TileBaker.CAVE_DEPTH - 1);
				if (top < bottom) {
					continue;
				}
			} else if (cave) {
				top = Math.min(top, roof);
			}
			// The sky view's sky occlusion looks this far round every block, so a change reaches back.
			int around = light ? LIGHT_REACH : TileBaker.SKY_REACH;
			if (!cave) {
				bottom = Math.min(bottom, (cave ? caveY0 : y0) - 1 - TileBaker.SKY_REACH);
			}
			double u0 = x0 - 1 - around;
			double u1 = x1 + 2 + Math.max(reachX, cave ? 0 : TileBaker.SKY_REACH);
			double v0 = Projection.v(top, z0 - 1 - Math.max(reachZ, cave ? 0 : TileBaker.SKY_REACH));
			double v1 = Projection.v(bottom, z1 + 2 + around);
			regions.add(new Region(layer, u0, v0, u1, v1));
		}
		return regions;
	}
}
