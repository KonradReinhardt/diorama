package dev.diorama.render;

import dev.diorama.MapDirection;
import dev.diorama.Projection;
import dev.diorama.bake.TileKey;
import dev.diorama.region.MapRegion;
import dev.diorama.region.RegionGeometry;
import dev.diorama.region.Ring;
import dev.diorama.render.gpu.MapEffect;
import dev.diorama.render.gpu.MapTexture;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The regions over a view, for the regions effect (regions.fsh): a map-space texture, built like
 * the coast's ({@link CoastMask}), whose texels say which region covers them and how far its edge
 * is. Each region's rings are projected at the heights they were drawn on and filled by
 * scanlines; smaller regions are filled last, so one inside another stays visible. Per texel:
 * <ul>
 *   <li>red: the region's dye (bits 0-3), its fill (4-5), whether it is highlighted (6) and whether
 *   the texel's middle is inside it (7);</li>
 *   <li>green: the signed distance to its edge, in eighths of a texel around 128, positive inside;
 *   measured exactly to the projected outline, so the shader, reading it smoothed between texels,
 *   finds the edge between texels too, and lines stay one art pixel and smooth at any zoom;</li>
 *   <li>blue: a tag telling regions apart, so a line runs where two regions meet as well.</li>
 * </ul>
 * Texels just outside a region carry its dye and tag and a negative distance. Before 1.21.6 there
 * are no effect passes: a plain texture of fills and edges is built instead, drawn as a quad.
 * Render thread only.
 */
final class RegionMask {
	/** Texels across. */
	static final int SIZE = 512;
	/** Finest texel, in map units: a map pixel. */
	static final double FINEST = 0.25;
	/** The origin snaps to this many texels, so panning rebuilds it now and then, not every frame. */
	private static final int SNAP_TEXELS = 64;
	/** Map units around the view the mask covers too, so an edge just past the view still draws. */
	private static final double MARGIN = 16;
	/** Texels from an edge whose distance is measured; farther ones read as this far. */
	static final int BAND = 8;
	/** Distance steps per texel in the green byte. */
	static final int STEPS = 8;
	/** How much of a dye each fill lays down, for the plain texture before 1.21.6 (regions.fsh has its own). */
	private static final float[] FILL_ALPHA = {0, 0.16F, 0.30F, 0.48F};

	private @Nullable MapTexture texture;
	private final int[] pixels = new int[SIZE * SIZE];
	/** Per texel, the region covering its middle: an index into {@link #drawn} plus one, or 0. */
	private final short[] owner = new short[SIZE * SIZE];
	private final float[] inside = new float[SIZE * SIZE];
	private final float[] outside = new float[SIZE * SIZE];
	/** Per texel outside every region, the one whose edge is nearest within {@link #BAND}, as in {@link #owner}. */
	private final short[] near = new short[SIZE * SIZE];
	private List<MapRegion> drawn = List.of();
	private @Nullable Inputs inputs;
	private static long builds;
	private static long buildNanos;

	public static long builds() { return builds; }
	public static long buildNanos() { return buildNanos; }

	private record Inputs(long revision, List<UUID> shown, double originU, double originV, double texel,
		MapDirection direction, int layer, @Nullable UUID highlight) {
	}

	/** What the region passes of a frame draw with: the texture and the view's part of it. */
	record Pass(MapTexture texture, float u0, float v0, float u1, float v1, double originU, double originV, double texel) {
	}

	/**
	 * Builds the mask for a view if anything it shows changed, and returns what to draw it with, or
	 * null when no region is near the view. {@code revision} goes up whenever a region changes; on
	 * a cave slice the outlines lie at the slice's height.
	 */
	@Nullable Pass prepare(MapViewport.View view, List<MapRegion> regions, long revision, @Nullable UUID highlight) {
		if (regions.isEmpty()) {
			this.drawn = List.of();
			return null;
		}
		double viewW = view.width() / view.zoom(), viewH = view.height() / view.zoom();
		double texel = FINEST;
		while (SIZE * texel < Math.max(viewW, viewH) + 2 * margin(texel) + SNAP_TEXELS * texel) {
			texel *= 2;
		}
		double snap = SNAP_TEXELS * texel;
		double originU = Math.floor((view.minU() - margin(texel)) / snap) * snap;
		double originV = Math.floor((view.minV() - margin(texel)) / snap) * snap;
		List<UUID> shown = new ArrayList<>(regions.size());
		for (MapRegion region : regions) {
			shown.add(region.id);
		}
		Inputs inputs = new Inputs(revision, shown, originU, originV, texel, Projection.direction(), view.layer(), highlight);
		if (this.texture == null || !inputs.equals(this.inputs)) {
			this.build(regions, originU, originV, texel, view.layer(), highlight);
			this.inputs = inputs;
		}
		if (this.drawn.isEmpty()) {
			return null;
		}
		double span = SIZE * texel;
		return new Pass(this.texture,
			(float) ((view.minU() - originU) / span), (float) ((view.minV() - originV) / span),
			(float) ((view.minU() + viewW - originU) / span), (float) ((view.minV() + viewH - originV) / span),
			originU, originV, texel);
	}

	private static double margin(double texel) {
		return Math.max(MARGIN, 4 * texel);
	}

	/** The region drawn at a map point in the last mask built, or null. */
	@Nullable MapRegion at(double u, double v) {
		Inputs in = this.inputs;
		if (in == null || this.drawn.isEmpty()) {
			return null;
		}
		int i = (int) Math.floor((u - in.originU()) / in.texel()), j = (int) Math.floor((v - in.originV()) / in.texel());
		if (i < 0 || j < 0 || i >= SIZE || j >= SIZE) {
			return null;
		}
		int o = this.owner[j * SIZE + i];
		return o == 0 ? null : this.drawn.get(o - 1);
	}

	private void build(List<MapRegion> regions, double originU, double originV, double texel, int layer, @Nullable UUID highlight) {
		long start = System.nanoTime();
		if (this.texture == null) {
			this.texture = MapTexture.create("diorama region mask", SIZE, 1);
		}
		boolean cave = layer != TileKey.SURFACE;
		// Each region's rings in texels; those whose outline can't reach the mask are left out.
		List<MapRegion> drawn = new ArrayList<>();
		List<float[][][]> outlines = new ArrayList<>();
		List<MapRegion> bySize = new ArrayList<>(regions);
		// Bigger first, so smaller ones are filled over them.
		bySize.sort(Comparator.comparingDouble(MapRegion::area).reversed());
		for (MapRegion region : bySize) {
			float[][][] outline = project(region, originU, originV, texel, cave ? layer : Integer.MIN_VALUE);
			if (outline != null && drawn.size() < Short.MAX_VALUE) {
				drawn.add(region);
				outlines.add(outline);
			}
		}
		this.drawn = drawn;
		Arrays.fill(this.owner, (short) 0);
		Arrays.fill(this.near, (short) 0);
		Arrays.fill(this.inside, BAND);
		Arrays.fill(this.outside, BAND);
		for (int k = 0; k < drawn.size(); k++) {
			short index = (short) (k + 1);
			RegionGeometry.scan(outlines.get(k)[0], outlines.get(k)[1], SIZE, SIZE, (row, from, to) -> Arrays.fill(this.owner, row * SIZE + from, row * SIZE + to, index));
		}
		for (int k = 0; k < drawn.size(); k++) {
			float[][] xs = outlines.get(k)[0], ys = outlines.get(k)[1];
			for (int r = 0; r < xs.length; r++) {
				float[] x = xs[r], y = ys[r];
				for (int i = 0, j = x.length - 1; i < x.length; j = i++) {
					this.edge(k + 1, x[j], y[j], x[i], y[i]);
				}
			}
		}
		boolean effects = MapEffect.supported();
		for (int t = 0; t < SIZE * SIZE; t++) {
			int o = this.owner[t];
			int tagged = o != 0 ? o : this.near[t];
			if (tagged == 0) {
				this.pixels[t] = effects ? 0xFF000000 | distanceByte(-BAND) << 8 : 0;
				continue;
			}
			MapRegion region = drawn.get(tagged - 1);
			if (effects) {
				int red = region.dye.ordinal() | region.fill.ordinal() << 4 | (region.id.equals(highlight) ? 64 : 0) | (o != 0 ? 128 : 0);
				int green = distanceByte(o != 0 ? this.inside[t] : -this.outside[t]);
				int blue = (tagged - 1) % 255 + 1;
				this.pixels[t] = 0xFF000000 | blue << 16 | green << 8 | red;
			} else {
				this.pixels[t] = o == 0 ? 0 : this.plain(t, o, region, region.id.equals(highlight));
			}
		}
		this.texture.write(0, 0, 0, SIZE, this.pixels);
		builds++;
		buildNanos += System.nanoTime() - start;
	}

	/** The distance to an edge as the green byte; see the class comment. */
	static int distanceByte(double texels) {
		return (int) Math.max(0, Math.min(255, 128 + Math.round(texels * STEPS)));
	}

	/**
	 * A region's rings in texels of the mask, {xs, ys}, each vertex projected at the height it was
	 * drawn on, or at {@code height} unless that is {@link Integer#MIN_VALUE}; null when the outline
	 * lies wholly beyond the mask.
	 */
	static float @Nullable [][][] project(MapRegion region, double originU, double originV, double texel, int height) {
		int n = region.rings.size();
		float[][] xs = new float[n][], ys = new float[n][];
		float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
		for (int r = 0; r < n; r++) {
			Ring ring = region.rings.get(r);
			float[] x = xs[r] = new float[ring.size()], y = ys[r] = new float[ring.size()];
			for (int i = 0; i < ring.size(); i++) {
				int h = height == Integer.MIN_VALUE ? ring.y(i) : height;
				x[i] = (float) ((Projection.u(ring.x(i), ring.z(i)) - originU) / texel);
				y[i] = (float) ((Projection.worldV(ring.x(i), h, ring.z(i)) - originV) / texel);
				minX = Math.min(minX, x[i]);
				maxX = Math.max(maxX, x[i]);
				minY = Math.min(minY, y[i]);
				maxY = Math.max(maxY, y[i]);
			}
		}
		if (maxX < -BAND || maxY < -BAND || minX > SIZE + BAND || minY > SIZE + BAND) {
			return null;
		}
		return new float[][][] {xs, ys};
	}

	/**
	 * Measures the distance from texels within {@link #BAND} of an edge of region {@code k} to it.
	 * It counts for texels of that region, and of regions under it (it is their edge where it covers
	 * them), and, outside every region, for the nearest. Long edges go in pieces no longer than the
	 * band, so only texels near the edge are looked at.
	 */
	private void edge(int k, float ax, float ay, float bx, float by) {
		double length = Math.hypot(bx - ax, by - ay);
		int pieces = Math.max(1, (int) Math.ceil(length / BAND));
		for (int p = 0; p < pieces; p++) {
			double x0 = ax + (bx - ax) * p / (double) pieces, y0 = ay + (by - ay) * p / (double) pieces;
			double x1 = ax + (bx - ax) * (p + 1) / (double) pieces, y1 = ay + (by - ay) * (p + 1) / (double) pieces;
			int i0 = Math.max(0, (int) Math.floor(Math.min(x0, x1) - BAND)), i1 = Math.min(SIZE - 1, (int) Math.ceil(Math.max(x0, x1) + BAND));
			int j0 = Math.max(0, (int) Math.floor(Math.min(y0, y1) - BAND)), j1 = Math.min(SIZE - 1, (int) Math.ceil(Math.max(y0, y1) + BAND));
			for (int j = j0; j <= j1; j++) {
				for (int i = i0; i <= i1; i++) {
					double d = RegionGeometry.segmentDistance(i + 0.5, j + 0.5, x0, y0, x1, y1);
					if (d >= BAND) continue;
					int t = j * SIZE + i;
					int o = this.owner[t];
					if (o == 0) {
						if (d < this.outside[t]) {
							this.outside[t] = (float) d;
							this.near[t] = (short) k;
						}
					} else if (o <= k && d < this.inside[t]) {
						this.inside[t] = (float) d;
					}
				}
			}
		}
	}

	/** A texel of the plain texture before 1.21.6, as ABGR: the fill, or the dye nearly solid along an edge. */
	private int plain(int t, int o, MapRegion region, boolean lit) {
		int i = t % SIZE, j = t / SIZE;
		boolean edge = i == 0 || j == 0 || i == SIZE - 1 || j == SIZE - 1
			|| this.owner[t - 1] != o || this.owner[t + 1] != o || this.owner[t - SIZE] != o || this.owner[t + SIZE] != o;
		float alpha = edge ? 0.9F : Math.min(1, FILL_ALPHA[region.fill.ordinal()] + (lit ? 0.12F : 0));
		int rgb = region.dye.rgb;
		return Math.round(alpha * 255) << 24 | (rgb & 255) << 16 | (rgb & 0xFF00) | rgb >> 16 & 255;
	}

	void close() {
		if (this.texture != null) {
			this.texture.close();
			this.texture = null;
		}
		this.inputs = null;
		this.drawn = List.of();
	}
}
