package dev.diorama.render;

import dev.diorama.MapDirection;
import dev.diorama.Projection;
import dev.diorama.bake.TileKey;
import dev.diorama.region.MapRegion;
import dev.diorama.region.RegionGeometry;
import dev.diorama.region.RegionStyle;
import dev.diorama.region.Ring;
import dev.diorama.render.gpu.MapEffect;
import dev.diorama.render.gpu.MapTexture;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;

/**
 * The regions over a view, for the regions effect (regions.fsh): a map-space texture, built like
 * the coast's ({@link CoastMask}), whose texels say which region covers them and how far its edge
 * is. Each region's rings are projected at the heights they were drawn on and filled by
 * scanlines; smaller regions are filled last, so one inside another stays visible. Per texel:
 * <ul>
 *   <li>red: the region's dye (bits 0-3), its fill (4-5), whether it is highlighted (6) and whether
 *   the texel's middle is inside it (7);</li>
 *   <li>green: the signed distance to its edge, in quarters of a texel around 128, positive inside;
 *   measured exactly to the projected outline, so the shader, reading it smoothed between texels,
 *   finds the edge between texels too, and lines stay one art pixel and smooth at any zoom;</li>
 *   <li>blue: a tag telling regions apart, so a line runs where two regions meet as well;</li>
 *   <li>alpha: the regions' style (low bits; see {@link RegionStyle}).</li>
 * </ul>
 * Texels just outside a region carry its dye and tag and a negative distance. Before 1.21.6 there
 * are no effect passes: a plain texture of fills and edges is built instead, drawn as a quad.
 *
 * <p>The mask is built on a worker thread of its own, from the outlines projected on the render
 * thread, and the map keeps drawing the last mask built until the next one is ready: a build
 * takes a few milliseconds, too long for a frame. Distances are measured only as far as the
 * bands drawn along edges reach at the view's zoom. Render thread only, but for the build.
 */
public final class RegionMask {
	/** Texels across. */
	static final int SIZE = 512;
	/** Finest texel, in map units: a map pixel. */
	static final double FINEST = 0.25;
	/** The origin snaps to this many texels, so panning rebuilds it now and then, not every frame. */
	private static final int SNAP_TEXELS = 64;
	/** Map units around the view the mask covers too, so an edge just past the view still draws. */
	private static final double MARGIN = 16;
	/** Texels from an edge at most whose distance is measured: as far as the green byte holds. */
	static final int BAND = 31;
	/** Art pixels from an edge the widest band drawn along it reaches (the wide border band, 8). */
	private static final int BAND_ART_PIXELS = 9;
	/** Distance steps per texel in the green byte. */
	static final int STEPS = 4;
	/** How much of a dye each fill lays down, for the plain texture before 1.21.6 (regions.fsh has its own). */
	private static final float[] FILL_ALPHA = {0, 0.16F, 0.30F, 0.48F};

	private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(task -> {
		Thread thread = new Thread(task, "Diorama region mask");
		thread.setDaemon(true);
		return thread;
	});
	private static final AtomicLong BUILDS = new AtomicLong();
	private static final AtomicLong BUILD_NANOS = new AtomicLong();

	public static long builds() { return BUILDS.get(); }
	public static long buildNanos() { return BUILD_NANOS.get(); }

	private @Nullable MapTexture texture;
	/** The mask on the GPU, as it was built; null before the first. */
	private @Nullable Built shown;
	/** The build under way, and what it was asked for. */
	private @Nullable CompletableFuture<Built> job;
	private @Nullable Inputs asked;
	/** Two sets of texels and owners: the worker fills the one not shown. */
	private final int[][] pixels = {new int[SIZE * SIZE], new int[SIZE * SIZE]};
	private final short[][] owners = {new short[SIZE * SIZE], new short[SIZE * SIZE]};
	private int spare;
	/** The worker's own scratch: distances inside and outside, and the region nearest outside. */
	private final float[] inside = new float[SIZE * SIZE];
	private final float[] outside = new float[SIZE * SIZE];
	private final short[] near = new short[SIZE * SIZE];

	private record Inputs(long revision, long preview, List<UUID> shown, double originU, double originV, double texel,
		MapDirection direction, int layer, @Nullable UUID highlight, RegionStyle style, int band) {
	}

	/** A region as the worker draws it: its rings in texels, and what its texels say. */
	private record Item(MapRegion region, float[][] xs, float[][] ys, int dye, int fill, int rgb, boolean lit) {
	}

	/** A mask built: what for, its texels and owners, and the regions they index. */
	private record Built(Inputs inputs, int[] pixels, short[] owner, List<MapRegion> drawn) {
	}

	/** What the region passes of a frame draw with: the texture and the view's part of it. */
	record Pass(MapTexture texture, float u0, float v0, float u1, float v1, double originU, double originV, double texel) {
	}

	/**
	 * Asks for the mask for a view if anything it shows changed, and returns what to draw with now:
	 * the last mask built, or null while there is none or no region is near the view.
	 * {@code revision} goes up whenever a region changes, and {@code preview} whenever a region
	 * being edited, not yet kept, changes; on a cave slice the outlines lie at the slice's height.
	 * They are drawn in {@code style}.
	 */
	@Nullable Pass prepare(MapViewport.View view, List<MapRegion> regions, long revision, long preview, @Nullable UUID highlight, RegionStyle style, double gui) {
		CompletableFuture<Built> job = this.job;
		if (job != null && job.isDone()) {
			this.job = null;
			Built built = job.join();
			if (this.texture == null) {
				this.texture = MapTexture.create("diorama region mask", SIZE, 1);
			}
			this.texture.write(0, 0, 0, SIZE, built.pixels());
			this.spare = built.pixels() == this.pixels[0] ? 1 : 0;
			this.shown = built;
		}
		double viewW = view.width() / view.zoom(), viewH = view.height() / view.zoom();
		if (!regions.isEmpty()) {
			double texel = FINEST;
			while (SIZE * texel < Math.max(viewW, viewH) + 2 * margin(texel) + SNAP_TEXELS * texel) {
				texel *= 2;
			}
			double snap = SNAP_TEXELS * texel;
			double originU = Math.floor((view.minU() - margin(texel)) / snap) * snap;
			double originV = Math.floor((view.minV() - margin(texel)) / snap) * snap;
			// Map units an art pixel spans, as regions.fsh measures it: a GUI pixel, never under a block.
			double art = Math.max(1, Math.max(1, Math.round(gui)) / (view.zoom() * gui));
			int band = (int) Math.min(BAND, Math.ceil(BAND_ART_PIXELS * art / texel) + 2);
			List<UUID> ids = new ArrayList<>(regions.size());
			for (MapRegion region : regions) {
				ids.add(region.id);
			}
			Inputs inputs = new Inputs(revision, preview, ids, originU, originV, texel, Projection.direction(), view.layer(), highlight, style, band);
			Built shown = this.shown;
			if (this.job == null && (shown == null || !inputs.equals(shown.inputs())) && !inputs.equals(this.asked)) {
				this.start(inputs, regions);
			}
		}
		Built shown = this.shown;
		if (regions.isEmpty() || shown == null || shown.drawn().isEmpty() || this.texture == null) {
			return null;
		}
		Inputs in = shown.inputs();
		double span = SIZE * in.texel();
		return new Pass(this.texture,
			(float) ((view.minU() - in.originU()) / span), (float) ((view.minV() - in.originV()) / span),
			(float) ((view.minU() + viewW - in.originU()) / span), (float) ((view.minV() + viewH - in.originV()) / span),
			in.originU(), in.originV(), in.texel());
	}

	private static double margin(double texel) {
		return Math.max(MARGIN, 4 * texel);
	}

	/** The region drawn at a map point in the mask shown, or null. */
	@Nullable MapRegion at(double u, double v) {
		Built shown = this.shown;
		if (shown == null || shown.drawn().isEmpty()) {
			return null;
		}
		Inputs in = shown.inputs();
		int i = (int) Math.floor((u - in.originU()) / in.texel()), j = (int) Math.floor((v - in.originV()) / in.texel());
		if (i < 0 || j < 0 || i >= SIZE || j >= SIZE) {
			return null;
		}
		int o = shown.owner()[j * SIZE + i];
		return o == 0 ? null : shown.drawn().get(o - 1);
	}

	/**
	 * Starts a build. What it needs from the regions is taken here, on the render thread (their
	 * outlines projected, their dyes): the worker never reads a region, which the tools change.
	 */
	private void start(Inputs inputs, List<MapRegion> regions) {
		boolean cave = inputs.layer() != TileKey.SURFACE;
		List<MapRegion> bySize = new ArrayList<>(regions);
		// Bigger first, so smaller ones are filled over them.
		bySize.sort(Comparator.comparingDouble(MapRegion::area).reversed());
		List<Item> items = new ArrayList<>();
		for (MapRegion region : bySize) {
			float[][][] outline = project(region, inputs.originU(), inputs.originV(), inputs.texel(), cave ? inputs.layer() : Integer.MIN_VALUE, inputs.band());
			if (outline != null && items.size() < Short.MAX_VALUE) {
				items.add(new Item(region, outline[0], outline[1], region.dye.ordinal(), region.fill.ordinal(), region.dye.rgb, region.id.equals(inputs.highlight())));
			}
		}
		int[] pixels = this.pixels[this.spare];
		short[] owner = this.owners[this.spare];
		boolean effects = MapEffect.supported();
		this.asked = inputs;
		this.job = CompletableFuture.supplyAsync(() -> this.build(inputs, items, pixels, owner, effects), WORKER);
	}

	/** Builds a mask into {@code pixels} and {@code owner}, on the worker. */
	private Built build(Inputs inputs, List<Item> items, int[] pixels, short[] owner, boolean effects) {
		long start = System.nanoTime();
		int band = inputs.band();
		Arrays.fill(owner, (short) 0);
		Arrays.fill(this.near, (short) 0);
		Arrays.fill(this.inside, band);
		Arrays.fill(this.outside, band);
		for (int k = 0; k < items.size(); k++) {
			short index = (short) (k + 1);
			RegionGeometry.scan(items.get(k).xs(), items.get(k).ys(), SIZE, SIZE, (row, from, to) -> Arrays.fill(owner, row * SIZE + from, row * SIZE + to, index));
		}
		for (int k = 0; k < items.size(); k++) {
			float[][] xs = items.get(k).xs(), ys = items.get(k).ys();
			for (int r = 0; r < xs.length; r++) {
				float[] x = xs[r], y = ys[r];
				for (int i = 0, j = x.length - 1; i < x.length; j = i++) {
					this.edge(owner, band, k + 1, x[j], y[j], x[i], y[i]);
				}
			}
		}
		int alpha = 0xF0 | inputs.style().shaderIndex();
		for (int t = 0; t < SIZE * SIZE; t++) {
			int o = owner[t];
			int tagged = o != 0 ? o : this.near[t];
			if (tagged == 0) {
				pixels[t] = effects ? 0xFF000000 | distanceByte(-band) << 8 : 0;
				continue;
			}
			Item item = items.get(tagged - 1);
			if (effects) {
				int red = item.dye() | item.fill() << 4 | (item.lit() ? 64 : 0) | (o != 0 ? 128 : 0);
				int green = distanceByte(o != 0 ? this.inside[t] : -this.outside[t]);
				int blue = (tagged - 1) % 255 + 1;
				pixels[t] = alpha << 24 | blue << 16 | green << 8 | red;
			} else {
				pixels[t] = o == 0 ? 0 : plain(owner, t, o, item);
			}
		}
		List<MapRegion> drawn = new ArrayList<>(items.size());
		for (Item item : items) {
			drawn.add(item.region());
		}
		BUILDS.incrementAndGet();
		BUILD_NANOS.addAndGet(System.nanoTime() - start);
		return new Built(inputs, pixels, owner, drawn);
	}

	/** The distance to an edge as the green byte; see the class comment. */
	static int distanceByte(double texels) {
		return (int) Math.max(0, Math.min(255, 128 + Math.round(texels * STEPS)));
	}

	/**
	 * A region's rings in texels of the mask, {xs, ys}, each vertex projected at the height it was
	 * drawn on, or at {@code height} unless that is {@link Integer#MIN_VALUE}; null when the outline
	 * lies wholly more than {@code band} texels beyond the mask.
	 */
	static float @Nullable [][][] project(MapRegion region, double originU, double originV, double texel, int height, int band) {
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
		if (maxX < -band || maxY < -band || minX > SIZE + band || minY > SIZE + band) {
			return null;
		}
		return new float[][][] {xs, ys};
	}

	/**
	 * Measures the distance from texels within {@code band} of an edge of region {@code k} to it.
	 * It counts for texels of that region, and of regions under it (it is their edge where it covers
	 * them), and, outside every region, for the nearest. Long edges go in pieces no longer than the
	 * band, so only texels near the edge are looked at.
	 */
	private void edge(short[] owner, int band, int k, float ax, float ay, float bx, float by) {
		double length = Math.hypot(bx - ax, by - ay);
		int pieces = Math.max(1, (int) Math.ceil(length / band));
		for (int p = 0; p < pieces; p++) {
			double x0 = ax + (bx - ax) * p / (double) pieces, y0 = ay + (by - ay) * p / (double) pieces;
			double x1 = ax + (bx - ax) * (p + 1) / (double) pieces, y1 = ay + (by - ay) * (p + 1) / (double) pieces;
			int i0 = Math.max(0, (int) Math.floor(Math.min(x0, x1) - band)), i1 = Math.min(SIZE - 1, (int) Math.ceil(Math.max(x0, x1) + band));
			int j0 = Math.max(0, (int) Math.floor(Math.min(y0, y1) - band)), j1 = Math.min(SIZE - 1, (int) Math.ceil(Math.max(y0, y1) + band));
			for (int j = j0; j <= j1; j++) {
				for (int i = i0; i <= i1; i++) {
					double d = RegionGeometry.segmentDistance(i + 0.5, j + 0.5, x0, y0, x1, y1);
					if (d >= band) continue;
					int t = j * SIZE + i;
					int o = owner[t];
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
	private static int plain(short[] owner, int t, int o, Item item) {
		int i = t % SIZE, j = t / SIZE;
		boolean edge = i == 0 || j == 0 || i == SIZE - 1 || j == SIZE - 1
			|| owner[t - 1] != o || owner[t + 1] != o || owner[t - SIZE] != o || owner[t + SIZE] != o;
		float alpha = edge ? 0.9F : Math.min(1, FILL_ALPHA[item.fill()] + (item.lit() ? 0.12F : 0));
		int rgb = item.rgb();
		return Math.round(alpha * 255) << 24 | (rgb & 255) << 16 | (rgb & 0xFF00) | rgb >> 16 & 255;
	}

	void close() {
		if (this.texture != null) {
			this.texture.close();
			this.texture = null;
		}
		// A build under way finishes into arrays nothing reads any more.
		this.job = null;
		this.asked = null;
		this.shown = null;
	}
}
