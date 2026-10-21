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
 * whose red holds, for each water texel, how far the nearest shore is, in blocks, green marks
 * water and blue shore. Built from the surface bits of the detailed tiles on the GPU (see
 * {@link MapTile#surface}). Only land that meets the water is shore: land standing high over water
 * it hides (a tower) is not, nor is ground whose surface was never recorded, and neither draws
 * a coast. Distances are measured on the water's own plane, across the map in u and down it in
 * depth (a block of depth is three quarters of a map unit), so lines a set distance from the shore
 * follow the map's perspective. Render thread only.
 */
final class CoastMask {
	/** Texels across. */
	static final int SIZE = 512;
	/** Farthest distance from land the texture tells apart, in blocks: 255 sixteenths. */
	static final float FAR = 255 / 16F;
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
	private final byte[] kind = new byte[SIZE * SIZE];
	private final float[] distance = new float[SIZE * SIZE];
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
		distances(this.kind, this.distance, SIZE, (float) texel, (float) (texel / Projection.zScale()));
		for (int i = 0; i < SIZE * SIZE; i++) {
			int far = Math.round(Math.min(FAR, this.distance[i]) * 16);
			int k = this.kind[i];
			this.pixels[i] = 0xFF000000 | (k == LAND ? 0xFF0000 : 0) | (k == WATER ? 0xFF00 : 0) | far;
		}
		this.texture.write(0, 0, 0, SIZE, this.pixels);
		builds++;
		buildNanos += System.nanoTime() - start;
	}

	/** The surface under each texel's middle, from the detailed tiles on the GPU. */
	private void sample(MapLevel level, double originU, double originV, double texel) {
		double units = Projection.TILE_UNITS;
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
				byte k = UNKNOWN;
				if (tile != null && tile.hasSurface()) {
					int px = Math.min(Projection.TILE_PIXELS - 1, (int) ((u - tu * units) * Projection.PIXELS_PER_UNIT));
					int bits = tile.surface(px, py);
					k = (bits & Surface.WATER) != 0 ? WATER : (bits & Surface.SHORE) != 0 ? LAND : UNKNOWN;
				}
				this.kind[j * SIZE + i] = k;
			}
		}
	}

	/**
	 * How far each texel is from the nearest land texel, in blocks, at most {@link #FAR}: a chamfer
	 * distance in two passes, a texel being {@code across} blocks wide and {@code down} blocks deep.
	 * Unknown texels never count as land. Pure, for tests.
	 */
	static void distances(byte[] kind, float[] out, int size, float across, float down) {
		float diagonal = (float) Math.sqrt(across * across + down * down);
		float far = Float.MAX_VALUE / 4;
		for (int i = 0; i < size * size; i++) {
			out[i] = kind[i] == LAND ? 0 : far;
		}
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				int i = y * size + x;
				float d = out[i];
				if (x > 0) d = Math.min(d, out[i - 1] + across);
				if (y > 0) {
					d = Math.min(d, out[i - size] + down);
					if (x > 0) d = Math.min(d, out[i - size - 1] + diagonal);
					if (x < size - 1) d = Math.min(d, out[i - size + 1] + diagonal);
				}
				out[i] = d;
			}
		}
		for (int y = size - 1; y >= 0; y--) {
			for (int x = size - 1; x >= 0; x--) {
				int i = y * size + x;
				float d = out[i];
				if (x < size - 1) d = Math.min(d, out[i + 1] + across);
				if (y < size - 1) {
					d = Math.min(d, out[i + size] + down);
					if (x < size - 1) d = Math.min(d, out[i + size + 1] + diagonal);
					if (x > 0) d = Math.min(d, out[i + size - 1] + diagonal);
				}
				out[i] = d;
			}
		}
	}

	void close() {
		if (this.texture != null) {
			this.texture.close();
			this.texture = null;
		}
		this.inputs = null;
		Arrays.fill(this.kind, UNKNOWN);
	}
}
