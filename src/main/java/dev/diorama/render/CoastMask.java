package dev.diorama.render;

import dev.diorama.MapDirection;
import dev.diorama.Projection;
import dev.diorama.bake.Surface;
import dev.diorama.bake.TileKey;
import dev.diorama.render.gpu.MapTexture;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/**
 * Where a view's water meets the land, for the coast effect (coast.fsh): a texture over the view
 * whose red holds how far the nearest shore is, in eighths of a block, green how much the water
 * there is open sea, and blue marks water. Built from the surface bits of the detailed tiles on
 * the GPU (see {@link MapTile#surface}). Only land that meets the water is shore: land standing
 * high over water it hides (a tower) is not, nor is ground whose surface was never recorded, and
 * neither draws a coast.
 *
 * <p>Distances are exact Euclidean ones, measured on the water's own plane: across the map in u
 * and down it in depth (a block of depth is three quarters of a map unit), so lines a set distance
 * from the shore follow the map's perspective, and keep their shape whatever the texel. Water is
 * open sea as far as it lies within reach of water more than {@link #OPEN} blocks from any shore:
 * oceans and wide lakes, where waves roll in, rather than rivers and ponds. Render thread only.
 */
final class CoastMask {
	/** Texels across. */
	static final int SIZE = 512;
	/** Farthest distance from the shore the texture tells apart, in blocks: 255 eighths. */
	static final float FAR = 255 / 8F;
	/** Water farther than this from any shore, in blocks, is open; see the class comment. */
	static final double OPEN = 10;
	/** Map units around the view the mask covers too, so lines near the edge see the shore beyond. */
	private static final double MARGIN = 16;
	/** The origin snaps to this many texels, so panning rebuilds it now and then, not every frame. */
	private static final int SNAP_TEXELS = 64;
	/** Finest texel, in map units: a map pixel. Coarser than COARSEST and the coast isn't drawn. */
	static final double FINEST = 0.25;
	private static final double COARSEST = 8;
	/** Coverage-only changes rebuild it at most this often. */
	private static final long REBUILD_NANOS = 100_000_000L;
	private static final byte UNKNOWN = 0, LAND = 1, WATER = 2;

	private @Nullable MapTexture texture;
	private final int[] pixels = new int[SIZE * SIZE];
	/** Per texel: water under its middle; shore anywhere in it. */
	private final byte[] kind = new byte[SIZE * SIZE];
	private final byte[] shore = new byte[SIZE * SIZE];
	private final float[] distance = new float[SIZE * SIZE];
	private final float[] open = new float[SIZE * SIZE];
	private final byte[] seeds = new byte[SIZE * SIZE];
	private @Nullable Inputs inputs;
	private WeakReference<MapLevel> level = new WeakReference<>(null);
	private long lastBuild;
	private static long builds;
	private static long buildNanos;

	public static long builds() { return builds; }
	public static long buildNanos() { return buildNanos; }

	private record Inputs(long version, double originU, double originV, double texel, MapDirection direction) {
	}

	/** What the coast pass of a frame draws with: the texture and the view's part of it. */
	record Pass(MapTexture texture, float u0, float v0, float u1, float v1, double originU, double originV, double texel) {
	}

	/**
	 * Builds the mask for a view of the surface if it changed, and returns what to draw it with, or
	 * null when the view is too far out for a coast to show or shows overviews, which record none.
	 */
	@Nullable Pass prepare(MapLevel level, MapViewport.View view) {
		if (view.layer() != TileKey.SURFACE || MapViewport.lodFor(level, view) > 0) {
			return null;
		}
		double viewW = view.width() / view.zoom(), viewH = view.height() / view.zoom();
		double texel = FINEST;
		while (SIZE * texel < Math.max(viewW, viewH) + 2 * MARGIN + SNAP_TEXELS * texel) {
			texel *= 2;
		}
		if (texel > COARSEST) {
			return null;
		}
		double snap = SNAP_TEXELS * texel;
		double originU = Math.floor((view.minU() - MARGIN) / snap) * snap;
		double originV = Math.floor((view.minV() - MARGIN) / snap) * snap;
		Inputs inputs = new Inputs(level.coverageVersion(), originU, originV, texel, Projection.direction());
		long now = System.nanoTime();
		boolean same = this.level.get() == level;
		boolean moved = !same || this.inputs == null || this.inputs.originU() != originU || this.inputs.originV() != originV
			|| this.inputs.texel() != texel || this.inputs.direction() != inputs.direction();
		if (this.texture == null || moved || this.inputs.version() != inputs.version() && now - this.lastBuild >= REBUILD_NANOS) {
			this.build(level, originU, originV, texel);
			this.inputs = inputs;
			this.level = new WeakReference<>(level);
			this.lastBuild = now;
		}
		double span = SIZE * texel;
		return new Pass(this.texture,
			(float) ((view.minU() - originU) / span), (float) ((view.minV() - originV) / span),
			(float) ((view.minU() + viewW - originU) / span), (float) ((view.minV() + viewH - originV) / span),
			originU, originV, texel);
	}

	private void build(MapLevel level, double originU, double originV, double texel) {
		long start = System.nanoTime();
		if (this.texture == null) {
			this.texture = MapTexture.create("diorama coast mask", SIZE, 1);
		}
		this.sample(level, originU, originV, texel);
		float across = (float) texel, down = (float) (texel / Projection.zScale());
		distances(this.shore, this.distance, SIZE, across, down);
		// The open water, then how far each texel is from it.
		for (int i = 0; i < SIZE * SIZE; i++) {
			this.seeds[i] = this.kind[i] == WATER && this.distance[i] >= OPEN ? LAND : UNKNOWN;
		}
		distances(this.seeds, this.open, SIZE, across, down);
		for (int i = 0; i < SIZE * SIZE; i++) {
			int far = Math.round(Math.min(FAR, this.distance[i]) * 8);
			boolean water = this.kind[i] == WATER;
			int sea = water ? (int) Math.round(openSea(this.distance[i], this.open[i]) * 255) : 0;
			this.pixels[i] = 0xFF000000 | (water ? 0xFF0000 : 0) | sea << 8 | far;
		}
		this.texture.write(0, 0, 0, SIZE, this.pixels);
		builds++;
		buildNanos += System.nanoTime() - start;
	}

	/**
	 * Whether there is water under each texel's middle, and shore anywhere in it, from the detailed
	 * tiles on the GPU. Shores are a pixel thin: a texel of several pixels asks the tile about all of
	 * them ({@link MapTile#shoreWithin}), so none is missed at any zoom. Texels line up with the
	 * tile's squares of pixels: the origin is a multiple of the texel, and the texel divides a tile.
	 */
	private void sample(MapLevel level, double originU, double originV, double texel) {
		double units = Projection.TILE_UNITS;
		int squares = Integer.numberOfTrailingZeros((int) Math.round(texel / FINEST));
		int lastTu = Integer.MIN_VALUE, lastTv = Integer.MIN_VALUE;
		MapTile tile = null;
		for (int j = 0; j < SIZE; j++) {
			double v = originV + (j + 0.5) * texel;
			int tv = (int) Math.floor(v / units);
			int py = Math.min(Projection.TILE_PIXELS - 1, (int) ((v - tv * units) * Projection.PIXELS_PER_UNIT));
			for (int i = 0; i < SIZE; i++) {
				double u = originU + (i + 0.5) * texel;
				int tu = (int) Math.floor(u / units);
				if (tu != lastTu || tv != lastTv) {
					tile = level.peek(new TileKey(TileKey.SURFACE, tu, tv, 0));
					lastTu = tu;
					lastTv = tv;
				}
				boolean water = false, shore = false;
				if (tile != null && tile.hasSurface()) {
					int px = Math.min(Projection.TILE_PIXELS - 1, (int) ((u - tu * units) * Projection.PIXELS_PER_UNIT));
					water = (tile.surface(px, py) & Surface.WATER) != 0;
					shore = tile.shoreWithin(px, py, squares);
				}
				this.kind[j * SIZE + i] = water ? WATER : UNKNOWN;
				this.shore[j * SIZE + i] = shore ? LAND : UNKNOWN;
			}
		}
	}

	/**
	 * How much water {@code shore} blocks from the shore and {@code open} blocks from open water is
	 * open sea, 0..1. By an ocean the two add up to about {@link #OPEN}; in a lake with only a small
	 * open middle, to more; in a river, which has none, to far more.
	 */
	static double openSea(double shore, double open) {
		double t = Math.max(0, Math.min(1, (shore + open - OPEN - 2) / 14));
		return 1 - t * t * (3 - 2 * t);
	}

	/**
	 * How far each texel is from the nearest texel marked {@code LAND} (1) in {@code kind}, in
	 * blocks: the exact Euclidean distance, a texel being {@code across} blocks wide and
	 * {@code down} blocks deep. Other texels never count as land; with none at all, every distance
	 * is huge. Rows, then columns, each the lower envelope of parabolas (Felzenszwalb and
	 * Huttenlocher), so linear in the texels. Pure, for tests.
	 */
	static void distances(byte[] kind, float[] out, int size, float across, float down) {
		double[] grid = new double[size * size];
		for (int i = 0; i < size * size; i++) {
			grid[i] = kind[i] == LAND ? 0 : NONE;
		}
		double[] line = new double[size], result = new double[size], bounds = new double[size + 1];
		int[] roots = new int[size];
		for (int y = 0; y < size; y++) {
			System.arraycopy(grid, y * size, line, 0, size);
			envelope(line, result, size, across, roots, bounds);
			System.arraycopy(result, 0, grid, y * size, size);
		}
		for (int x = 0; x < size; x++) {
			for (int y = 0; y < size; y++) line[y] = grid[y * size + x];
			envelope(line, result, size, down, roots, bounds);
			for (int y = 0; y < size; y++) out[y * size + x] = (float) Math.sqrt(result[y]);
		}
	}

	/** Squared distance standing for no land at all: far beyond any view, small enough to add to. */
	private static final double NONE = 1e20;

	/** {@code out[q] = min over p of (weight * (q - p))^2 + f[p]}, over n values. */
	private static void envelope(double[] f, double[] out, int n, double weight, int[] roots, double[] bounds) {
		double w2 = weight * weight;
		int k = 0;
		roots[0] = 0;
		bounds[0] = Double.NEGATIVE_INFINITY;
		bounds[1] = Double.POSITIVE_INFINITY;
		for (int q = 1; q < n; q++) {
			// Where this parabola drops below the last one kept; those it hides from the left go.
			// bounds[0] is minus infinity, so the first one always stays.
			double s = cross(f, w2, roots[k], q);
			while (s <= bounds[k]) {
				k--;
				s = cross(f, w2, roots[k], q);
			}
			k++;
			roots[k] = q;
			bounds[k] = s;
			bounds[k + 1] = Double.POSITIVE_INFINITY;
		}
		k = 0;
		for (int q = 0; q < n; q++) {
			while (bounds[k + 1] < q) k++;
			int r = roots[k];
			out[q] = w2 * (q - r) * (q - r) + f[r];
		}
	}

	/** Where the parabolas rooted at r and q, r before q, cross. */
	private static double cross(double[] f, double w2, int r, int q) {
		return ((f[q] + w2 * q * q) - (f[r] + w2 * r * r)) / (2 * w2 * (q - r));
	}

	void close() {
		if (this.texture != null) {
			this.texture.close();
			this.texture = null;
		}
		this.inputs = null;
		Arrays.fill(this.kind, UNKNOWN);
		Arrays.fill(this.shore, UNKNOWN);
		Arrays.fill(this.seeds, UNKNOWN);
	}
}
