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
 * whose red holds how far the nearest shore is (see {@link #encode}), green how much the water
 * there is open sea, and blue and alpha which of the texel's four by four parts are water: a texel
 * can be several blocks across when zoomed out, and the coast drawn over the whole of one that
 * holds the shore would cover land too. Built from the surface bits of the detailed tiles on
 * the GPU (see {@link MapTile#surface}). Only land that meets the water is shore: land standing
 * high over water it hides (a tower) is not, nor is ground whose surface was never recorded, and
 * neither draws a coast.
 *
 * <p>Distances are exact Euclidean ones, measured on the water's own plane: across the map in u
 * and down it in depth (a block of depth is three quarters of a map unit), so lines a set distance
 * from the shore follow the map's perspective, and keep their shape whatever the texel. Water is
 * open sea as far as it lies within reach of water more than {@link #OPEN} blocks from any shore:
 * oceans and wide lakes, where waves roll in, rather than rivers and ponds.
 *
 * <p>Zoomed out, the map draws overview tiles, and so does this: their surface planes are shrunk
 * from the tiles' (see {@code TileStore}), so the coast keeps showing however far out. Render
 * thread only.
 */
final class CoastMask {
	/** Texels across. */
	static final int SIZE = 512;
	/**
	 * Farthest distance from the shore the texture tells apart, in blocks: 32, or 32 texels once a
	 * texel is more than a block, so ripples spaced for a zoomed out view still fit.
	 */
	static double reach(double texel) {
		return 32 * Math.max(1, texel);
	}

	/**
	 * A distance as the red byte: the square root of its share of {@link #reach}, so it is finest
	 * near the shore, where foam and breaking waves need it. coast.fsh squares it back.
	 */
	static int encode(double distance, double texel) {
		double reach = reach(texel);
		return (int) Math.round(Math.sqrt(Math.min(reach, distance) / reach) * 255);
	}
	/** Water farther than this from any shore, in blocks, is open; see the class comment. */
	static final double OPEN = 10;
	/** Map units around the view the mask covers too, so lines near the edge see the shore beyond. */
	private static final double MARGIN = 16;
	/** The origin snaps to this many texels, so panning rebuilds it now and then, not every frame. */
	private static final int SNAP_TEXELS = 64;
	/** Finest texel, in map units: a map pixel. */
	static final double FINEST = 0.25;
	/** Coverage-only changes rebuild it at most this often. */
	private static final long REBUILD_NANOS = 100_000_000L;
	private static final byte UNKNOWN = 0, LAND = 1, WATER = 2;

	private @Nullable MapTexture texture;
	private final int[] pixels = new int[SIZE * SIZE];
	/** Per texel: water under its middle; shore anywhere in it. */
	private final byte[] kind = new byte[SIZE * SIZE];
	private final byte[] shore = new byte[SIZE * SIZE];
	/** Per texel, which of its four by four parts are open water: bit 4y + x. */
	private final short[] parts = new short[SIZE * SIZE];
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

	private record Inputs(long version, int lod, double originU, double originV, double texel, MapDirection direction) {
	}

	/** What the coast pass of a frame draws with: the texture and the view's part of it. */
	record Pass(MapTexture texture, float u0, float v0, float u1, float v1, double originU, double originV, double texel) {
	}

	/**
	 * Builds the mask for a view of the surface if it changed, and returns what to draw it with, or
	 * null off the surface. Texels are a power of two times the pixel of the level of detail the
	 * view draws, never finer, and at most {@link MapTile#SHORE_LEVELS} levels coarser.
	 */
	@Nullable Pass prepare(MapLevel level, MapViewport.View view) {
		if (view.layer() != TileKey.SURFACE) {
			return null;
		}
		int lod = MapViewport.lodFor(level, view);
		double viewW = view.width() / view.zoom(), viewH = view.height() / view.zoom();
		double pixel = FINEST * (1 << lod);
		double texel = pixel;
		while (SIZE * texel < Math.max(viewW, viewH) + 2 * margin(texel) + SNAP_TEXELS * texel) {
			texel *= 2;
		}
		if (texel > pixel * (1 << MapTile.SHORE_LEVELS)) {
			return null;
		}
		double snap = SNAP_TEXELS * texel;
		double originU = Math.floor((view.minU() - margin(texel)) / snap) * snap;
		double originV = Math.floor((view.minV() - margin(texel)) / snap) * snap;
		Inputs inputs = new Inputs(level.coverageVersion(), lod, originU, originV, texel, Projection.direction());
		long now = System.nanoTime();
		boolean same = this.level.get() == level;
		boolean moved = !same || this.inputs == null || this.inputs.originU() != originU || this.inputs.originV() != originV
			|| this.inputs.texel() != texel || this.inputs.lod() != lod || this.inputs.direction() != inputs.direction();
		if (this.texture == null || moved || this.inputs.version() != inputs.version() && now - this.lastBuild >= REBUILD_NANOS) {
			this.build(level, lod, originU, originV, texel);
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

	/** Map units around the view the mask covers too, so lines near the edge see the shore beyond. */
	private static double margin(double texel) {
		return Math.max(MARGIN, 8 * texel);
	}

	private void build(MapLevel level, int lod, double originU, double originV, double texel) {
		long start = System.nanoTime();
		if (this.texture == null) {
			this.texture = MapTexture.create("diorama coast mask", SIZE, 1);
		}
		this.sample(level, lod, originU, originV, texel);
		float across = (float) texel, down = (float) (texel / Projection.zScale());
		distances(this.shore, this.distance, SIZE, across, down);
		// The open water, then how far each texel is from it.
		for (int i = 0; i < SIZE * SIZE; i++) {
			this.seeds[i] = this.kind[i] == WATER && this.distance[i] >= OPEN ? LAND : UNKNOWN;
		}
		distances(this.seeds, this.open, SIZE, across, down);
		for (int i = 0; i < SIZE * SIZE; i++) {
			int far = encode(this.distance[i], texel);
			boolean water = this.kind[i] == WATER;
			int sea = water ? (int) Math.round(openSea(this.distance[i], this.open[i]) * 255) : 0;
			int parts = this.parts[i] & 0xFFFF;
			this.pixels[i] = parts << 16 | sea << 8 | far;
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
	 * A texel without shore is all water or none; one with shore is looked at in four by four parts,
	 * each water only if its middle is and no shore lies in it, so no part of the land is.
	 */
	private void sample(MapLevel level, int lod, double originU, double originV, double texel) {
		double units = Projection.TILE_UNITS * (1 << lod);
		double pixelsPerUnit = Projection.PIXELS_PER_UNIT / (double) (1 << lod);
		int squares = Integer.numberOfTrailingZeros((int) Math.round(texel / (FINEST * (1 << lod))));
		int lastTu = Integer.MIN_VALUE, lastTv = Integer.MIN_VALUE;
		MapTile tile = null;
		for (int j = 0; j < SIZE; j++) {
			double v = originV + (j + 0.5) * texel;
			int tv = (int) Math.floor(v / units);
			int py = Math.min(Projection.TILE_PIXELS - 1, (int) ((v - tv * units) * pixelsPerUnit));
			for (int i = 0; i < SIZE; i++) {
				double u = originU + (i + 0.5) * texel;
				int tu = (int) Math.floor(u / units);
				if (tu != lastTu || tv != lastTv) {
					tile = level.peek(new TileKey(TileKey.SURFACE, tu, tv, lod));
					lastTu = tu;
					lastTv = tv;
				}
				boolean water = false, shore = false;
				int parts = 0;
				if (tile != null && tile.hasSurface()) {
					int px = Math.min(Projection.TILE_PIXELS - 1, (int) ((u - tu * units) * pixelsPerUnit));
					water = (tile.surface(px, py) & Surface.WATER) != 0;
					shore = tile.shoreWithin(px, py, squares);
					parts = shore ? parts(tile, px, py, squares) : water ? 0xFFFF : 0;
				}
				this.kind[j * SIZE + i] = water ? WATER : UNKNOWN;
				this.shore[j * SIZE + i] = shore ? LAND : UNKNOWN;
				this.parts[j * SIZE + i] = (short) parts;
			}
		}
	}

	/** Which of the four by four parts of the texel holding pixel (px, py), 2^squares pixels across, are open water. */
	private static int parts(MapTile tile, int px, int py, int squares) {
		int edge = 1 << squares, x0 = px & -edge, y0 = py & -edge;
		int level = Math.max(0, squares - 2);
		int parts = 0;
		for (int sy = 0; sy < 4; sy++) {
			int y = y0 + (2 * sy + 1) * edge / 8;
			for (int sx = 0; sx < 4; sx++) {
				int x = x0 + (2 * sx + 1) * edge / 8;
				if ((tile.surface(x, y) & Surface.WATER) != 0 && !tile.shoreWithin(x, y, level)) {
					parts |= 1 << (sy * 4 + sx);
				}
			}
		}
		return parts;
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
		Arrays.fill(this.parts, (short) 0);
		Arrays.fill(this.seeds, UNKNOWN);
	}
}
