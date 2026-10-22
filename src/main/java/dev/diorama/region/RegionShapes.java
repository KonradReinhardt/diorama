package dev.diorama.region;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Adding one outline to another and cutting one out of another, as the region tools do with Shift
 * and Alt and as the chunk brush does. Both are filled into a grid (half a block a cell, coarser
 * for very large regions), combined cell by cell, and the result's edges are traced back into
 * rings, simplified on the half-block grid. No geometry library: outlines the player draws by hand
 * need nothing finer. A traced corner takes the height of the nearest corner of either outline.
 * Pure, for tests.
 */
public final class RegionShapes {
	/** Finest cell, in blocks. */
	static final double CELL = 0.5;
	/** Most cells the grid may have; larger regions get coarser cells. */
	static final long MAX_CELLS = 16L << 20;

	private RegionShapes() {
	}

	/** {@code a} with {@code b} added to it, or cut out of it; empty when nothing is left. */
	public static List<Ring> combine(List<Ring> a, List<Ring> b, boolean subtract) {
		double[] ba = RegionGeometry.bounds(a), bb = RegionGeometry.bounds(b);
		double minX = subtract ? ba[0] : Math.min(ba[0], bb[0]), minZ = subtract ? ba[1] : Math.min(ba[1], bb[1]);
		double maxX = subtract ? ba[2] : Math.max(ba[2], bb[2]), maxZ = subtract ? ba[3] : Math.max(ba[3], bb[3]);
		double cell = CELL;
		while ((long) Math.ceil((maxX - minX) / cell + 2) * (long) Math.ceil((maxZ - minZ) / cell + 2) > MAX_CELLS) {
			cell *= 2;
		}
		double ox = Math.floor(minX / cell) * cell - cell, oz = Math.floor(minZ / cell) * cell - cell;
		int w = (int) Math.ceil((maxX - ox) / cell) + 2, h = (int) Math.ceil((maxZ - oz) / cell) + 2;
		boolean[] in = fill(a, ox, oz, cell, w, h);
		boolean[] other = fill(b, ox, oz, cell, w, h);
		for (int i = 0; i < in.length; i++) {
			in[i] = subtract ? in[i] && !other[i] : in[i] || other[i];
		}
		List<Ring> sources = new ArrayList<>(a);
		sources.addAll(b);
		List<Ring> out = new ArrayList<>();
		for (int[][] loop : trace(in, w, h)) {
			int n = loop[0].length;
			double[] x = new double[n], z = new double[n];
			int[] y = new int[n];
			for (int i = 0; i < n; i++) {
				x[i] = ox + loop[0][i] * cell;
				z[i] = oz + loop[1][i] * cell;
				y[i] = nearestHeight(sources, x[i], z[i]);
			}
			// Steps of a cell are the grid's, not the player's: smoothed away.
			Ring ring = RegionGeometry.simplify(x, z, y, Math.max(RegionGeometry.TOLERANCE, cell * 0.75));
			if (ring != null && Math.abs(RegionGeometry.signedArea(ring)) >= RegionGeometry.MIN_AREA) {
				out.add(ring);
			}
		}
		return out;
	}

	/** A square ring from (x0, z0) to (x1, z1), its corners at {@code y}: a chunk for the brush. */
	public static Ring square(double x0, double z0, double x1, double z1, int[] heights) {
		return new Ring(new float[] {(float) x0, (float) x1, (float) x1, (float) x0}, new float[] {(float) z0, (float) z0, (float) z1, (float) z1},
			new short[] {(short) heights[0], (short) heights[1], (short) heights[2], (short) heights[3]});
	}

	/** Which cells of a {@code w} by {@code h} grid at (ox, oz) the rings cover, by the even-odd rule. */
	static boolean[] fill(List<Ring> rings, double ox, double oz, double cell, int w, int h) {
		boolean[] in = new boolean[w * h];
		float[][] xs = new float[rings.size()][], zs = new float[rings.size()][];
		for (int r = 0; r < rings.size(); r++) {
			Ring ring = rings.get(r);
			xs[r] = new float[ring.size()];
			zs[r] = new float[ring.size()];
			for (int i = 0; i < ring.size(); i++) {
				xs[r][i] = (float) ((ring.x(i) - ox) / cell);
				zs[r][i] = (float) ((ring.z(i) - oz) / cell);
			}
		}
		RegionGeometry.scan(xs, zs, w, h, (row, from, to) -> {
			for (int i = from; i < to; i++) in[row * w + i] = true;
		});
		return in;
	}

	/**
	 * The outlines of the cells marked in a grid, as loops of grid corners {xs, ys}, the cells
	 * inside on each edge's right. Where two cells touch only at a corner the loop turns right, so
	 * every loop is simple. Corners where the outline runs straight on are left out.
	 */
	static List<int[][]> trace(boolean[] in, int w, int h) {
		// Each edge between a cell inside and one outside, from corner to corner.
		Map<Long, List<long[]>> from = new HashMap<>();
		int edges = 0;
		for (int j = 0; j < h; j++) {
			for (int i = 0; i < w; i++) {
				if (!in[j * w + i]) continue;
				if (j == 0 || !in[(j - 1) * w + i]) edges += add(from, i, j, i + 1, j);
				if (i == w - 1 || !in[j * w + i + 1]) edges += add(from, i + 1, j, i + 1, j + 1);
				if (j == h - 1 || !in[(j + 1) * w + i]) edges += add(from, i + 1, j + 1, i, j + 1);
				if (i == 0 || !in[j * w + i - 1]) edges += add(from, i, j + 1, i, j);
			}
		}
		List<int[][]> loops = new ArrayList<>();
		while (edges > 0) {
			long[] first = null;
			for (List<long[]> list : from.values()) {
				if (!list.isEmpty()) {
					first = list.get(0);
					break;
				}
			}
			List<int[]> points = new ArrayList<>();
			long[] edge = first;
			while (edge != null) {
				from.get(edge[0]).remove(edge);
				edges--;
				int[] a = point(edge[0]), b = point(edge[1]);
				points.add(a);
				List<long[]> next = from.get(edge[1]);
				long[] chosen = null;
				if (next != null && !next.isEmpty()) {
					// Turn right where two ways go on: the cells inside touch only at this corner.
					int dx = b[0] - a[0], dy = b[1] - a[1];
					for (long[] candidate : next) {
						int[] c = point(candidate[1]);
						int ex = c[0] - b[0], ey = c[1] - b[1];
						if (chosen == null || dx * ey - dy * ex > 0) chosen = candidate;
					}
				}
				edge = chosen;
			}
			int[][] loop = straighten(points);
			if (loop[0].length >= 3) loops.add(loop);
		}
		return loops;
	}

	private static int add(Map<Long, List<long[]>> from, int x0, int y0, int x1, int y1) {
		from.computeIfAbsent(key(x0, y0), k -> new ArrayList<>(2)).add(new long[] {key(x0, y0), key(x1, y1)});
		return 1;
	}

	private static long key(int x, int y) {
		return (long) x << 32 | (y & 0xFFFFFFFFL);
	}

	private static int[] point(long key) {
		return new int[] {(int) (key >> 32), (int) key};
	}

	/** A loop of corners without those where it runs straight on, as {xs, ys}. */
	private static int[][] straighten(List<int[]> points) {
		int n = points.size();
		List<int[]> kept = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			int[] p = points.get((i + n - 1) % n), c = points.get(i), q = points.get((i + 1) % n);
			if ((c[0] - p[0]) * (q[1] - c[1]) - (c[1] - p[1]) * (q[0] - c[0]) != 0) kept.add(c);
		}
		int[][] out = new int[2][kept.size()];
		for (int i = 0; i < kept.size(); i++) {
			out[0][i] = kept.get(i)[0];
			out[1][i] = kept.get(i)[1];
		}
		return out;
	}

	/** The height of the corner of any of the rings nearest (x, z). */
	private static int nearestHeight(List<Ring> rings, double x, double z) {
		double best = Double.POSITIVE_INFINITY;
		int y = 64;
		for (Ring ring : rings) {
			for (int i = 0; i < ring.size(); i++) {
				double dx = ring.x(i) - x, dz = ring.z(i) - z, d = dx * dx + dz * dz;
				if (d < best) {
					best = d;
					y = ring.y(i);
				}
			}
		}
		return y;
	}
}
