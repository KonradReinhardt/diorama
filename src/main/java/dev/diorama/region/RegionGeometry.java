package dev.diorama.region;

import dev.diorama.MapDirection;
import dev.diorama.Projection;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;
import org.jspecify.annotations.Nullable;

/**
 * The geometry regions need, on plain numbers so tests can use it: area and bounds, the even-odd
 * inside test, simplifying a hand-drawn outline, where a label fits, filling outlines into a grid
 * by scanlines, and turning a stroke drawn on the map into an outline in the world.
 */
public final class RegionGeometry {
	/** Most vertices a region keeps; a longer outline is simplified harder until it fits. */
	public static final int MAX_VERTICES = 4096;
	/** Outlines are kept to half a block. */
	public static final float GRID = 0.5F;
	/** How far, in blocks, simplifying may move an outline: half a block. */
	public static final double TOLERANCE = 0.5;
	/** Smaller outlines are a slip of the mouse, not a region. */
	public static final double MIN_AREA = 4;

	/** A height not known: the ground there isn't on the map, or isn't explored. */
	public static final int UNKNOWN = Integer.MIN_VALUE;

	private RegionGeometry() {
	}

	/** The ground's height shown at a map point, or {@link #UNKNOWN}. */
	@FunctionalInterface
	public interface Heights {
		int at(double u, double v);
	}

	/** A run of grid cells {@code [from, to)} in a row inside an outline. */
	@FunctionalInterface
	public interface Span {
		void fill(int row, int from, int to);
	}

	/** {minX, minZ, maxX, maxZ}; zeros for no vertices. */
	public static double[] bounds(List<Ring> rings) {
		double minX = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
		double maxX = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
		for (Ring ring : rings) {
			for (int i = 0; i < ring.size(); i++) {
				minX = Math.min(minX, ring.x(i));
				maxX = Math.max(maxX, ring.x(i));
				minZ = Math.min(minZ, ring.z(i));
				maxZ = Math.max(maxZ, ring.z(i));
			}
		}
		return minX > maxX ? new double[4] : new double[] {minX, minZ, maxX, maxZ};
	}

	/** The area a ring encloses, signed by its winding (shoelace). */
	public static double signedArea(Ring ring) {
		double sum = 0;
		for (int i = 0, j = ring.size() - 1; i < ring.size(); j = i++) {
			sum += (double) ring.x(j) * ring.z(i) - (double) ring.x(i) * ring.z(j);
		}
		return sum / 2;
	}

	/**
	 * The area inside by the even-odd rule, for rings that don't cross: a ring inside an odd number
	 * of others is a hole.
	 */
	public static double area(List<Ring> rings) {
		double total = 0;
		for (Ring ring : rings) {
			if (ring.size() < 3) continue;
			int depth = 0;
			for (Ring other : rings) {
				if (other != ring && inside(other, ring.x(0), ring.z(0))) depth++;
			}
			total += (depth % 2 == 0 ? 1 : -1) * Math.abs(signedArea(ring));
		}
		return Math.max(0, total);
	}

	/** Whether a point is inside, by the even-odd rule over every ring. */
	public static boolean contains(List<Ring> rings, double x, double z) {
		boolean in = false;
		for (Ring ring : rings) {
			if (inside(ring, x, z)) in = !in;
		}
		return in;
	}

	private static boolean inside(Ring ring, double x, double z) {
		boolean in = false;
		for (int i = 0, j = ring.size() - 1; i < ring.size(); j = i++) {
			double zi = ring.z(i), zj = ring.z(j);
			if ((zi > z) != (zj > z) && x < (ring.x(j) - ring.x(i)) * (z - zi) / (zj - zi) + ring.x(i)) {
				in = !in;
			}
		}
		return in;
	}

	/** Distance from (px, py) to the segment from (ax, ay) to (bx, by). */
	public static double segmentDistance(double px, double py, double ax, double ay, double bx, double by) {
		double dx = bx - ax, dy = by - ay;
		double length = dx * dx + dy * dy;
		double t = length == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / length));
		double ex = ax + t * dx - px, ey = ay + t * dy - py;
		return Math.sqrt(ex * ex + ey * ey);
	}

	/** How far a point is from a region, in blocks: 0 inside it. */
	public static double distance(List<Ring> rings, double x, double z) {
		return Math.max(0, -signedDistance(rings, x, z));
	}

	/** Distance from a point to the nearest edge of any ring, positive inside and negative outside. */
	static double signedDistance(List<Ring> rings, double x, double z) {
		double best = Double.POSITIVE_INFINITY;
		for (Ring ring : rings) {
			for (int i = 0, j = ring.size() - 1; i < ring.size(); j = i++) {
				best = Math.min(best, segmentDistance(x, z, ring.x(j), ring.z(j), ring.x(i), ring.z(i)));
			}
		}
		return contains(rings, x, z) ? best : -best;
	}

	/**
	 * Where a label sits best: the point inside farthest from every edge (the pole of
	 * inaccessibility, found as Mapbox's polylabel does), to within {@code precision} blocks. So
	 * a name lands inside a C-shaped region rather than in its mouth. {x, z}; the bounds' middle
	 * for an empty region.
	 */
	public static double[] label(List<Ring> rings, double precision) {
		double[] b = bounds(rings);
		double w = b[2] - b[0], h = b[3] - b[1];
		double size = Math.min(w, h);
		if (size <= 0) {
			return new double[] {(b[0] + b[2]) / 2, (b[1] + b[3]) / 2};
		}
		record Cell(double x, double z, double half, double d, double max) {
		}
		PriorityQueue<Cell> queue = new PriorityQueue<>((p, q) -> Double.compare(q.max(), p.max()));
		double half = size / 2;
		for (double x = b[0]; x < b[2]; x += size) {
			for (double z = b[1]; z < b[3]; z += size) {
				double d = signedDistance(rings, x + half, z + half);
				queue.add(new Cell(x + half, z + half, half, d, d + half * Math.sqrt(2)));
			}
		}
		double cx = (b[0] + b[2]) / 2, cz = (b[1] + b[3]) / 2;
		Cell best = new Cell(cx, cz, 0, signedDistance(rings, cx, cz), 0);
		while (!queue.isEmpty()) {
			Cell cell = queue.poll();
			if (cell.d() > best.d()) best = cell;
			if (cell.max() - best.d() <= precision) continue;
			double q = cell.half() / 2;
			for (int i = 0; i < 4; i++) {
				double x = cell.x() + (i % 2 == 0 ? -q : q), z = cell.z() + (i < 2 ? -q : q);
				double d = signedDistance(rings, x, z);
				queue.add(new Cell(x, z, q, d, d + q * Math.sqrt(2)));
			}
		}
		return new double[] {best.x(), best.z()};
	}

	/**
	 * Fills outlines into a grid of {@code width} by {@code height} cells by the even-odd rule:
	 * a cell is in when its middle is. Coordinates are in cells, cell (i, j) spanning i to i + 1;
	 * {@code xs[k]} and {@code ys[k]} are the vertices of ring k.
	 */
	public static void scan(float[][] xs, float[][] ys, int width, int height, Span span) {
		float[][] rows = new float[height][];
		int[] counts = new int[height];
		for (int k = 0; k < xs.length; k++) {
			float[] x = xs[k], y = ys[k];
			for (int i = 0, j = x.length - 1; i < x.length; j = i++) {
				float y0 = y[j], y1 = y[i];
				if (y0 == y1) continue;
				float lo = Math.min(y0, y1), hi = Math.max(y0, y1);
				int first = Math.max(0, (int) Math.ceil(lo - 0.5));
				int last = Math.min(height - 1, (int) Math.ceil(hi - 0.5) - 1);
				for (int row = first; row <= last; row++) {
					float at = row + 0.5F;
					float cross = x[j] + (at - y0) * (x[i] - x[j]) / (y1 - y0);
					float[] list = rows[row];
					if (list == null) {
						list = rows[row] = new float[8];
					} else if (counts[row] == list.length) {
						list = rows[row] = Arrays.copyOf(list, list.length * 2);
					}
					list[counts[row]++] = cross;
				}
			}
		}
		for (int row = 0; row < height; row++) {
			float[] list = rows[row];
			if (list == null) continue;
			Arrays.sort(list, 0, counts[row]);
			for (int c = 0; c + 1 < counts[row]; c += 2) {
				int from = Math.max(0, (int) Math.ceil(list[c] - 0.5));
				int to = Math.min(width, (int) Math.ceil(list[c + 1] - 0.5));
				if (from < to) span.fill(row, from, to);
			}
		}
	}

	/**
	 * Turns a closed stroke drawn on the map, at map points ({@code u}, {@code v}), into an outline
	 * in the world: each point lies on the ground the map shows there, at that ground's height.
	 * Where the height isn't known it takes the nearest known point's along the stroke, else
	 * {@code fallback}. Simplified to {@link #TOLERANCE} on the {@link #GRID}, or null when too few
	 * points are left or the outline is smaller than {@link #MIN_AREA}.
	 */
	public static @Nullable Ring fromMap(double[] u, double[] v, Heights heights, int fallback, MapDirection direction) {
		int n = u.length;
		if (n < 3) return null;
		int[] y = new int[n];
		for (int i = 0; i < n; i++) {
			y[i] = heights.at(u[i], v[i]);
		}
		fillUnknown(y, fallback);
		double[] x = new double[n], z = new double[n];
		for (int i = 0; i < n; i++) {
			double depth = Projection.zAt(v[i], y[i]);
			x[i] = direction.worldX(u[i], depth);
			z[i] = direction.worldZ(u[i], depth);
		}
		Ring ring = simplify(x, z, y, TOLERANCE);
		return ring == null || Math.abs(signedArea(ring)) < MIN_AREA ? null : ring;
	}

	/** Gives each unknown height the nearest known one along the closed stroke; all unknown take {@code fallback}. */
	static void fillUnknown(int[] y, int fallback) {
		int n = y.length;
		int[] known = y.clone();
		for (int i = 0; i < n; i++) {
			if (known[i] != UNKNOWN) continue;
			int found = UNKNOWN;
			for (int step = 1; step <= n / 2 && found == UNKNOWN; step++) {
				int ahead = known[(i + step) % n], behind = known[Math.floorMod(i - step, n)];
				found = ahead != UNKNOWN ? ahead : behind;
			}
			y[i] = found == UNKNOWN ? fallback : found;
		}
	}

	/**
	 * A closed outline simplified (Douglas-Peucker on x and z) to within {@code tolerance},
	 * harder until it has at most {@link #MAX_VERTICES} vertices, then put on the
	 * {@link #GRID} without repeated vertices. Null when fewer than three are left.
	 */
	public static @Nullable Ring simplify(double[] x, double[] z, int[] y, double tolerance) {
		int n = x.length;
		if (n < 3) return null;
		boolean[] keep;
		double t = tolerance;
		while (true) {
			keep = douglasPeucker(x, z, t);
			int kept = 0;
			for (boolean k : keep) if (k) kept++;
			if (kept <= MAX_VERTICES) break;
			t *= 2;
		}
		float[] ox = new float[n], oz = new float[n];
		short[] oy = new short[n];
		int m = 0;
		for (int i = 0; i < n; i++) {
			if (!keep[i]) continue;
			float sx = Math.round(x[i] / GRID) * GRID, sz = Math.round(z[i] / GRID) * GRID;
			if (m > 0 && ox[m - 1] == sx && oz[m - 1] == sz) continue;
			ox[m] = sx;
			oz[m] = sz;
			oy[m] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, y[i]));
			m++;
		}
		while (m > 1 && ox[m - 1] == ox[0] && oz[m - 1] == oz[0]) m--;
		if (m < 3) return null;
		return new Ring(Arrays.copyOf(ox, m), Arrays.copyOf(oz, m), Arrays.copyOf(oy, m));
	}

	/** Which vertices of a closed outline Douglas-Peucker keeps, split at the first vertex and the one farthest from it. */
	private static boolean[] douglasPeucker(double[] x, double[] z, double tolerance) {
		int n = x.length;
		boolean[] keep = new boolean[n];
		int far = 0;
		double farthest = -1;
		for (int i = 1; i < n; i++) {
			double d = Math.hypot(x[i] - x[0], z[i] - z[0]);
			if (d > farthest) {
				farthest = d;
				far = i;
			}
		}
		keep[0] = true;
		keep[far] = true;
		// The second half closes back to the first vertex, as index n.
		int[] stack = {0, far, far, n};
		int top = 4;
		while (top > 0) {
			int to = stack[--top], from = stack[--top];
			double best = -1;
			int index = -1;
			for (int i = from + 1; i < to; i++) {
				double d = segmentDistance(x[i], z[i], x[from], z[from], x[to % n], z[to % n]);
				if (d > best) {
					best = d;
					index = i;
				}
			}
			if (index >= 0 && best > tolerance) {
				keep[index] = true;
				if (top + 4 > stack.length) stack = Arrays.copyOf(stack, stack.length * 2);
				stack[top++] = from;
				stack[top++] = index;
				stack[top++] = index;
				stack[top++] = to;
			}
		}
		return keep;
	}
}
