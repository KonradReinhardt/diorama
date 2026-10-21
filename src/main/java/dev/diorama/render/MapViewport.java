package dev.diorama.render;

import dev.diorama.MapStyle;
import dev.diorama.Projection;
import dev.diorama.Settings;
import dev.diorama.MapSession;
import dev.diorama.preview.SeedPreview;
import dev.diorama.bake.TileBaker;
import dev.diorama.bake.TileKey;
import dev.diorama.render.gpu.MapCanvas;
import dev.diorama.render.gpu.MapTexture;
import dev.diorama.render.gpu.Sampling;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.jspecify.annotations.Nullable;

/**
 * Draws a rectangle of the map: baked tiles, then fog over everything the map has no data for.
 * Each on-screen map (minimap, fullscreen) owns one, because each keeps its own fog mask.
 */
public final class MapViewport {
    private static long maskBuilds;
    public static long maskBuilds() { return maskBuilds; }
	/** Mask texels across for clouds, and for the antique map, whose crisp outline needs finer ones. */
	private static final int MASK = 256;
	private static final int ANTIQUE_MASK = 512;
	private static final double MASK_MARGIN = 24;
	/** Zooming out past this (GUI pixels per block) switches to overview tiles. */
	public static final double DETAILED_ZOOM = 0.5;

	private @Nullable MapTexture maskTexture;
	/** Texels across the current mask; arrays below are sized for it. */
	private int size = MASK;
	private int[] maskPixels = new int[MASK * MASK];
    private FogReveal reveal = new FogReveal(MASK);
    private int[] columnTiles = new int[MASK], columnCells = new int[MASK];
    private double[] columnU = new double[MASK];
    /** Texels whose coverage was borrowed from another level of detail at the last build; see {@link #borrowedCoverage}. */
    private boolean[] lent = new boolean[MASK * MASK];
    /** The tile {@link #borrowedCoverage} last looked up at each level, so a texel row doesn't look it up again. */
    private final @Nullable MapTile[] lendTile = new MapTile[TileKey.MAX_LOD + 1];
    private final long[] lendKey = new long[TileKey.MAX_LOD + 1];
    private final boolean[] lendLooked = new boolean[TileKey.MAX_LOD + 1];
	private int[] notKnownCounts = new int[(MASK + 1) * (MASK + 1)];
	private int[] notUnknownCounts = new int[(MASK + 1) * (MASK + 1)];
	private float[] blurA = new float[MASK * MASK];
	private float[] blurB = new float[MASK * MASK];
	/** Antique: whether any coverage cell under each texel holds terrain, however little. */
	private boolean[] anyTerrain = new boolean[MASK * MASK];
    private boolean settleFlags;
    private static long maskNanos;
    public static long maskNanos() { return maskNanos; }
	/** What the mask texture was last built from; it only needs building again when this changes. */
	private @Nullable MaskInputs maskInputs;
	/** The map the mask was built from. Weak, so a minimap left over after leaving a world doesn't keep its map. */
	private WeakReference<MapLevel> maskLevel = new WeakReference<>(null);

	private record MaskInputs(long coverageVersion, int layer, int lod, double originU, double originV, double texel, boolean rounded, int scaleSteps, boolean fast, boolean antique, long previewRevision, dev.diorama.MapDirection direction) {
		MaskInputs withCoverage(long version) {
			return new MaskInputs(version, this.layer, this.lod, this.originU, this.originV, this.texel, this.rounded, this.scaleSteps, this.fast, this.antique, this.previewRevision, this.direction);
		}
	}

	/** Sizes the mask and its work arrays; a different size starts the mask and its history over. */
	private void resize(int size) {
		if (size == this.size) {
			return;
		}
		this.size = size;
		this.maskPixels = new int[size * size];
		this.reveal = new FogReveal(size);
		this.columnTiles = new int[size];
		this.columnCells = new int[size];
		this.columnU = new double[size];
		this.lent = new boolean[size * size];
		this.notKnownCounts = new int[(size + 1) * (size + 1)];
		this.notUnknownCounts = new int[(size + 1) * (size + 1)];
		this.blurA = new float[size * size];
		this.blurB = new float[size * size];
		this.anyTerrain = new boolean[size * size];
		if (this.maskTexture != null) {
			this.maskTexture.close();
			this.maskTexture = null;
		}
		this.maskInputs = null;
		this.cartoInputs = null;
	}

	/** Where and how big a map is drawn, and what part of the map it shows. */
	public record View(int x, int y, int width, int height, double centerU, double centerV, double zoom, int layer) {
		public double minU() {
			return this.centerU - this.width / 2.0 / this.zoom;
		}

		public double minV() {
			return this.centerV - this.height / 2.0 / this.zoom;
		}

		public float screenX(double u) {
			return (float) (this.x + this.width / 2.0 + (u - this.centerU) * this.zoom);
		}

		public float screenY(double v) {
			return (float) (this.y + this.height / 2.0 + (v - this.centerV) * this.zoom);
		}

		/**
		 * This view as it should be drawn, pixel perfect. Once a map pixel covers a screen pixel or
		 * more, the zoom rounds to a whole number of screen pixels per map pixel and the centre moves
		 * by less than a screen pixel, so every map pixel is the same square of screen pixels and the
		 * block grid lines up with the screen's. Smaller map pixels blend through mipmaps, so the
		 * view stays as it is.
		 */
		public View pixelPerfect() {
			return this.pixelPerfect(Minecraft.getInstance().getWindow().getGuiScale());
		}

		/** Same, for a GUI scale of {@code gui} screen pixels per GUI pixel. */
		public View pixelPerfect(double gui) {
			double perMapPixel = this.zoom * gui / Projection.PIXELS_PER_UNIT;
			if (perMapPixel < 1) {
				return this;
			}
			double zoom = Math.round(perMapPixel) * (double) Projection.PIXELS_PER_UNIT / gui;
			double screenPerUnit = zoom * gui;
			double u = align(this.centerU, gui * (this.x + this.width / 2.0), screenPerUnit);
			double v = align(this.centerV, gui * (this.y + this.height / 2.0), screenPerUnit);
			return new View(this.x, this.y, this.width, this.height, u, v, zoom, this.layer);
		}

		/**
		 * The centre nearest {@code centre} that puts map unit 0, and so every map pixel edge, on a
		 * whole screen pixel, given the screen pixel the centre is drawn at.
		 */
		private static double align(double centre, double screenCentre, double screenPerUnit) {
			double origin = screenCentre - centre * screenPerUnit;
			return centre - (Math.rint(origin) - origin) / screenPerUnit;
		}
	}

	/** Below this zoom (GUI pixels per block) overlay lines and dots would blur into noise. */
	private static final double OVERLAY_ZOOM = 1.0;

	public void draw(GuiGraphicsExtractor graphics, MapLevel level, View view) {
		this.draw(graphics, level, view, 0);
	}

	/** Same, with these {@link dev.diorama.bake.Overlays} bits drawn over the tiles. */
	public void draw(GuiGraphicsExtractor graphics, MapLevel level, View view, int overlays) {
		graphics.enableScissor(view.x(), view.y(), view.x() + view.width(), view.y() + view.height());
		boolean cave = view.layer() != TileKey.SURFACE;
		// Through the map canvas rather than a GUI fill, so a clipped view cuts its ground too.
		float bx0 = view.x(), by0 = view.y(), bx1 = view.x() + view.width(), by1 = view.y() + view.height();
		MapCanvas.of(graphics).solid(new float[] {bx0, by0, bx0, by1, bx1, by1, bx1, by0},
			Settings.get().antiqueFog() ? 0xFFE5CB96 : cave ? 0xFF0C0E12 : 0xFF8C99AB);

		var lighting = dev.diorama.EnvironmentLighting.current(cave || level.roofless());
        FogPass fog = this.prepareFog(level, view, lighting);
        // Unknown ground drawn opaque under the tiles first: wherever terrain is missing or only
        // partly known (the explored edge, tiles still loading) it shows instead of a flat colour.
        this.drawFog(graphics, view, fog, true);
        this.drawTiles(graphics, level, view, view.zoom() >= OVERLAY_ZOOM ? overlays : 0, lighting);
        this.drawFog(graphics, view, fog, false);
		graphics.disableScissor();
	}

	/**
	 * The overview level to draw: the coarsest one that still has at least one texture pixel per
	 * screen pixel. Overviews only exist for the saved surface map, and are only used below
	 * {@link #DETAILED_ZOOM}: above it the live tiles' own mipmaps look the same and never lag
	 * behind recent changes.
	 */
	public static int lodFor(MapLevel level, View view) {
		if (view.layer() != TileKey.SURFACE || !level.hasOverviews() || view.zoom() >= DETAILED_ZOOM) {
			return 0;
		}
		double screenPixelsPerUnit = view.zoom() * Minecraft.getInstance().getWindow().getGuiScale();
		int lod = 0;
		while (lod < TileKey.MAX_LOD && Projection.PIXELS_PER_UNIT / (double) (2 << lod) >= screenPixelsPerUnit) {
			lod++;
		}
		return lod;
	}

	/** Whether every tile this view shows has baked at least once. Asks for the ones that haven't. */
	public static boolean ready(MapLevel level, View view) {
		boolean ready = true;
		for (TileKey key : tilesIn(level, view)) {
			ready &= level.want(key, distance(key, view)) != null;
		}
		return ready;
	}

	/** Whether any tile this view shows is baked and has something on it. Asks for nothing. */
	public static boolean showsAnything(MapLevel level, View view) {
		for (TileKey key : tilesIn(level, view)) {
			MapTile tile = level.peek(key);
			if (tile != null && !tile.isEmpty()) {
				return true;
			}
		}
		return false;
	}

	/** The tiles this view shows, at the level it draws them. */
	public static List<TileKey> tilesIn(MapLevel level, View view) {
		int lod = lodFor(level, view);
		double units = (double) Projection.TILE_UNITS * (1 << lod);
		double u0 = view.minU();
		double v0 = view.minV();
		int tu1 = (int) Math.floor((u0 + view.width() / view.zoom()) / units);
		int tv1 = (int) Math.floor((v0 + view.height() / view.zoom()) / units);
		List<TileKey> keys = new ArrayList<>();
		for (int tu = (int) Math.floor(u0 / units); tu <= tu1; tu++) {
			for (int tv = (int) Math.floor(v0 / units); tv <= tv1; tv++) {
				keys.add(new TileKey(view.layer(), tu, tv, lod));
			}
		}
		return keys;
	}

	private static double distance(TileKey key, View view) {
		double units = (double) Projection.TILE_UNITS * (1 << key.lod());
		double du = (key.tileU() + 0.5) * units - view.centerU();
		double dv = (key.tileV() + 0.5) * units - view.centerV();
		return Math.sqrt(du * du + dv * dv);
	}

	/** Whether a texture pixel of this level covers at least one screen pixel, so it is drawn crisp, not blurred. */
	static boolean magnified(View view, int lod) {
		double screenPixelsPerUnit = view.zoom() * Minecraft.getInstance().getWindow().getGuiScale();
		return screenPixelsPerUnit * (1 << lod) >= Projection.PIXELS_PER_UNIT;
	}

	private void drawTiles(GuiGraphicsExtractor graphics, MapLevel level, View view, int overlays, dev.diorama.EnvironmentLighting lighting) {
		int lod = lodFor(level, view);
		double units = (double) Projection.TILE_UNITS * (1 << lod);
		double u0 = view.minU();
		double v0 = view.minV();
		double u1 = u0 + view.width() / view.zoom();
		double v1 = v0 + view.height() / view.zoom();
		int tu0 = (int) Math.floor(u0 / units);
		int tv0 = (int) Math.floor(v0 / units);
		int tu1 = (int) Math.floor(u1 / units);
		int tv1 = (int) Math.floor(v1 / units);
		// Minified tiles blend across mips; magnified ones stay crisp so blocks keep their edges.
		// Magnified means on the real screen: the GUI scale enlarges every texture pixel too.
		Sampling sampling = magnified(view, lod) ? Sampling.TILE_CRISP : Sampling.TILE_SMOOTH;
		MapCanvas canvas = MapCanvas.of(graphics);
		float size = (float) (units * view.zoom());
		boolean lends = view.layer() == TileKey.SURFACE && level.hasOverviews();
		for (int tu = tu0 - 1; tu <= tu1 + 1; tu++) {
			for (int tv = tv0 - 1; tv <= tv1 + 1; tv++) {
				TileKey key = new TileKey(view.layer(), tu, tv, lod);
				double du = (tu + 0.5) * units - view.centerU();
				double dv = (tv + 0.5) * units - view.centerV();
				boolean visible = tu >= tu0 && tu <= tu1 && tv >= tv0 && tv <= tv1;
				// Tiles just outside the view bake too, so panning finds them ready.
				MapTile tile = level.want(key, Math.sqrt(du * du + dv * dv) + (visible ? 0 : MapLevel.OFF_SCREEN));
				if (!visible) {
					continue;
				}
				float sx = view.screenX(tu * units);
				float sy = view.screenY(tv * units);
				if (tile == null || tile.texture() == null) {
					if (lends) {
						this.drawBorrowed(canvas, level, tu, tv, lod, sampling, sx, sy, size, lighting);
					}
					continue;
				}
				drawTile(canvas, tile, sampling, sx, sy, sx + size, sy + size, 0, 0, 1, 1, lighting);
				MapTexture overlay = overlays == 0 || lod > 0 ? null : tile.overlay(overlays);
				if (overlay != null) {
					canvas.rect(overlay, sampling, sx, sy, sx + size, sy + size, 0, 0, 1, 1, -1);
				}
			}
		}
	}

	private static void drawTile(MapCanvas canvas, MapTile tile, Sampling sampling, float x0, float y0, float x1, float y1,
		float u0, float v0, float u1, float v1, dev.diorama.EnvironmentLighting lighting) {
		if (Settings.get().antiqueTint() && !dev.diorama.EnvironmentLighting.supported()) {
			// Reuse the common fog pipeline as a sepia tile pass on older day-only backends.
			canvas.antiqueTile(tile.texture(), FogTextures.parchment(), sampling, x0, y0, x1, y1, u0, v0, u1, v1);
		} else canvas.lit(tile.texture(), tile.nightTexture(), sampling, x0, y0, x1, y1, u0, v0, u1, v1, lighting);
	}

	/**
	 * A surface tile of this level that isn't on the GPU yet, drawn from the other levels that are,
	 * as the fog mask borrows their coverage ({@link #borrowedCoverage}): its part of the nearest
	 * overview above, then the tiles of the level below over it. Switching levels then never shows a
	 * hole that fills in, nor fog that lifts again.
	 */
	private static void drawBorrowed(MapCanvas canvas, MapLevel level, int tu, int tv, int lod, Sampling sampling,
		float sx, float sy, float size, dev.diorama.EnvironmentLighting lighting) {
		for (int up = lod + 1; up <= TileKey.MAX_LOD; up++) {
			int shift = up - lod;
			MapTile parent = level.peek(new TileKey(TileKey.SURFACE, tu >> shift, tv >> shift, up));
			if (parent != null && parent.texture() != null) {
				float part = 1F / (1 << shift);
				float u0 = (tu - (tu >> shift << shift)) * part, v0 = (tv - (tv >> shift << shift)) * part;
				drawTile(canvas, parent, sampling, sx, sy, sx + size, sy + size, u0, v0, u0 + part, v0 + part, lighting);
				break;
			}
		}
		if (lod == 0) {
			return;
		}
		float half = size / 2;
		for (int dx = 0; dx < 2; dx++) {
			for (int dy = 0; dy < 2; dy++) {
				MapTile child = level.peek(new TileKey(TileKey.SURFACE, tu * 2 + dx, tv * 2 + dy, lod - 1));
				if (child != null && child.texture() != null) {
					float x = sx + dx * half, y = sy + dy * half;
					drawTile(canvas, child, sampling, x, y, x + half, y + half, 0, 0, 1, 1, lighting);
				}
			}
		}
	}

	/** What both fog passes of a frame draw with: the mask is built once, before the tiles. */
	private record FogPass(boolean antique, float u0, float v0, float u1, float v1, int params) {
	}

	/** Matches fog.fsh's UNDERLAY_SHIFT: the pass under the tiles reads the mask shifted by this. */
	private static final float UNDERLAY_SHIFT = 16;

	private FogPass prepareFog(MapLevel level, View view, dev.diorama.EnvironmentLighting lighting) {
		boolean antique = Settings.get().antiqueFog();
		this.resize(antique ? ANTIQUE_MASK : MASK);
		int size = this.size;
		double viewW = view.width() / view.zoom();
		double viewH = view.height() / view.zoom();
		// Zoomed out past the detailed zoom, the fog's noise grows with the view so it keeps its
		// size on screen instead of repeating every few pixels. The scale is 2^(steps + fraction);
		// the shader blends the noise of two whole steps by the fraction.
		double scaleLog = Math.min(7.99, Math.log(Math.max(1, DETAILED_ZOOM / view.zoom())) / Math.log(2));
		int steps = (int) Math.floor(scaleLog);
		// The noise offset is in eighths of the coarser noise's period, so the origin snaps to that.
		double snap = 64.0 * (1 << (steps + 1));
		double need = Math.max(viewW, viewH) + MASK_MARGIN * 2 + snap;
		int scaleCode = 0;
		double texel = 2;
		while (size * texel < need) {
			texel *= 2;
			scaleCode++;
		}
		double originU = Math.floor((view.minU() - MASK_MARGIN) / snap) * snap;
		double originV = Math.floor((view.minV() - MASK_MARGIN) / snap) * snap;
		boolean fast = TileBaker.fast();
		// Both styles are pixel art: an art pixel is one GUI pixel on a grid aligned to the
		// framebuffer (or a block, zoomed in further). Its phase follows where the world's origin
		// lands, so the grid moves with the map in whole pixels. See fog.fsh.
		var window = Minecraft.getInstance().getWindow();
		double gui = window.getGuiScale();
		int artPixels = (int) Math.max(1, Math.min(4, Math.round(gui)));
		double originX = (view.x() + view.width() / 2.0 - view.centerU() * view.zoom()) * gui;
		// The shader counts y up the framebuffer, from its bottom.
		double originY = window.getHeight() - (view.y() + view.height() / 2.0 - view.centerV() * view.zoom()) * gui;
		int pixelCode = artPixels - 1 | (int) Math.floorMod(-Math.round(originX), (long) artPixels) << 2
			| (int) Math.floorMod(Math.round(originY), (long) artPixels) << 4;
		// Map units per art pixel: never less than a block.
		double art = Math.max(1, artPixels / (view.zoom() * gui));
        SeedPreview preview = null;
        MapSession session = MapSession.get();
        if (antique && Settings.get().seedPreview && !level.roofless() && view.layer() == TileKey.SURFACE && session != null && session.map() == level) {
            preview = session.seedPreview();
            if (preview != null) preview.request(previewBounds(view));
        }
		this.buildMask(level, view, originU, originV, texel, steps, fast, antique, preview, art);

		double span = size * texel;
		float mu0 = (float) ((view.minU() - originU) / span);
		float mv0 = (float) ((view.minV() - originV) / span);
		float mu1 = (float) ((view.minU() + viewW - originU) / span);
		float mv1 = (float) ((view.minV() + viewH - originV) / span);
		int noiseU = Math.floorMod((long) (originU / snap), 8);
		int noiseV = Math.floorMod((long) (originV / snap), 8);
		boolean cave = view.layer() != TileKey.SURFACE;
		// The shader blends the noise scale's fraction itself, from the art pixel; daylight takes its bits.
		int fogCode = (cave ? 128 : 0) | steps << 4 | Math.round(lighting.daylight() * 15);
		// The mask scale code takes the alpha byte's high nibble; 8 marks the antique map, 4 its colours.
		int quality = (antique ? 8 : 0) | (Settings.get().fogStyle == Settings.FogStyle.ANCIENT_MAP_COLOR ? 4 : 0);
        int progress = Math.round(this.reveal.progress(System.nanoTime()) * 63);
        // Six bits each for the art pixel and reveal easing; no per-frame mask uploads.
        int params = (scaleCode * 16 | quality | progress >> 4) << 24 | (noiseU * 32 | pixelCode & 31) << 16
            | (noiseV * 32 | pixelCode >> 5 | (progress & 15) << 1) << 8 | fogCode;
		return new FogPass(antique, mu0, mv0, mu1, mv1, params);
	}

	private void drawFog(GuiGraphicsExtractor graphics, View view, FogPass fog, boolean underlay) {
		float shift = underlay ? UNDERLAY_SHIFT : 0;
		MapCanvas.of(graphics).fog(
			this.maskTexture, fog.antique() ? FogTextures.parchment() : FogTextures.noise(),
			view.x(), view.y(), view.width(), view.height(),
			fog.u0() + shift, fog.v0(), fog.u1() + shift, fog.v1(),
			fog.params()
		);
	}

    private static SeedPreview.Bounds previewBounds(View view) {
        double depth0 = Projection.zAt(view.minV(), 64), depth1 = Projection.zAt(view.minV() + view.height() / view.zoom(), 64);
        double u0 = view.minU(), u1 = u0 + view.width() / view.zoom();
        var d = Projection.direction();
        double[] xs = {d.worldX(u0, depth0), d.worldX(u0, depth1), d.worldX(u1, depth0), d.worldX(u1, depth1)};
        double[] zs = {d.worldZ(u0, depth0), d.worldZ(u0, depth1), d.worldZ(u1, depth0), d.worldZ(u1, depth1)};
        return new SeedPreview.Bounds((int)Math.floor(java.util.Arrays.stream(xs).min().orElse(0)),
            (int)Math.floor(java.util.Arrays.stream(zs).min().orElse(0)), (int)Math.ceil(java.util.Arrays.stream(xs).max().orElse(0)),
            (int)Math.ceil(java.util.Arrays.stream(zs).max().orElse(0)), view.zoom() >= .15, view.zoom() >= .45);
    }
	private void buildMask(MapLevel level, View view, double originU, double originV, double texel, int scaleSteps, boolean fast, boolean antique, @Nullable SeedPreview preview, double art) {
		int size = this.size;
		if (this.maskTexture == null) {
			this.maskTexture = MapTexture.create("diorama fog mask", size, 1);
		}
		int lod = lodFor(level, view);
		boolean surface = view.layer() == TileKey.SURFACE;
		// Caves keep the sharp mask: rounding would erode narrow explored tunnels away. For clouds,
		// once a texel spans a chunk there are no chunk steps left to smooth, and rounding would
		// only eat small explored areas. The antique map always rounds, so its outline keeps one
		// shape at every zoom.
		boolean rounded = MapStyle.get().roundedFog() && surface && (antique || texel < 16);
		MaskInputs inputs = new MaskInputs(level.coverageVersion(), view.layer(), lod, originU, originV, texel, rounded, scaleSteps, fast, antique, preview == null ? -1 : preview.revision(), Projection.direction());
		long now = System.nanoTime();
		if (this.maskLevel.get() == level && inputs.equals(this.maskInputs)) {
            if (this.settleFlags && this.reveal.progress(now) == 1) {
                this.settleFlags = false;
                for (int i = 0; i < size * size; i++) this.maskPixels[i] = (this.maskPixels[i] & 255) * 0x01000001;
                this.markReach(fogReach(texel, !surface), 0xFF00, 0xFF0000, null);
                this.maskTexture.write(0, 0, 0, size, this.maskPixels);
            }
			return;
		}
		// While tiles stream in, coverage changes almost every frame. Rebuilding for each would cost
		// more than it shows: wait a little, unless the view itself moved.
		if (this.maskLevel.get() == level && this.maskInputs != null && inputs.withCoverage(this.maskInputs.coverageVersion()).equals(this.maskInputs)
			&& now - this.lastMaskBuild < MASK_REBUILD_NANOS) {
			return;
		}
		this.lastMaskBuild = now;
        maskBuilds++;
		boolean reset = this.maskLevel.get() != level || this.maskInputs == null || this.maskInputs.layer() != view.layer() || this.maskInputs.direction() != Projection.direction()
			// A finer or coarser mask draws known ground in more or less detail; that isn't a
			// discovery, and easing it in made every zoom step flicker along the frontier. Nor is
			// another level of detail, whose coverage cells are coarser or finer: the two change at
			// different zooms, and easing the switch revealed the frontier again on every zoom.
			|| this.maskInputs.texel() != texel || this.maskInputs.lod() != lod;
		this.maskInputs = inputs;
		this.maskLevel = new WeakReference<>(level);
		double units = (double) Projection.TILE_UNITS * (1 << lod);
		double cellUnits = units / Projection.COVERAGE_CELLS;
		// The antique outline is drawn crisp, so a texel counts only as far as its least known
		// coverage cell: then no hole in the terrain ever falls inside the line. Clouds read the
		// cell under the texel's centre and soften the rest.
		int cellsAcross = antique ? (int) Math.max(1, Math.round(texel / cellUnits)) : 1;
		int cellStep = Math.max(1, cellsAcross / 8);
        java.util.Arrays.fill(this.lendLooked, false);
        boolean lends = surface && level.hasOverviews();
        for (int i = 0; i < size; i++) {
            double u = originU + (antique ? i * texel + Math.min(texel, cellUnits) / 2 : (i + .5) * texel);
            this.columnU[i] = u;
            int tu = (int)Math.floor(u / units);
            this.columnTiles[i] = tu;
            this.columnCells[i] = Math.min(Projection.COVERAGE_CELLS - 1, (int)((u - tu * units) / cellUnits));
        }
		int lastTu = Integer.MIN_VALUE;
		int lastTv = Integer.MIN_VALUE;
		MapTile tile = null;
		for (int j = 0; j < size; j++) {
			double v = originV + (antique ? j * texel + Math.min(texel, cellUnits) / 2 : (j + 0.5) * texel);
			int tv = (int) Math.floor(v / units);
			int cellY = Math.min(Projection.COVERAGE_CELLS - 1, (int) ((v - tv * units) / cellUnits));
			for (int i = 0; i < size; i++) {
				int tu = this.columnTiles[i];
				if (tu != lastTu || tv != lastTv) {
					tile = level.peek(new TileKey(view.layer(), tu, tv, lod));
					lastTu = tu;
					lastTv = tv;
				}
				float known = 0;
				float any = 0;
				// A tile of this level still loading shows what the other levels' tiles know there.
				float borrowed = tile == null && lends ? this.borrowedCoverage(level, lod, this.columnU[i], v) : -1;
				this.lent[j * size + i] = borrowed >= 0;
				if (borrowed >= 0) {
					known = borrowed;
					any = borrowed;
				} else if (tile != null && tile.hasCoverage()) {
					int cellX = this.columnCells[i];
					known = tile.coverage(cellX, cellY);
					any = known;
					for (int dy = 0; dy < cellsAcross; dy += cellStep) {
						for (int dx = 0; dx < cellsAcross; dx += cellStep) {
							float cell = tile.coverage(Math.min(Projection.COVERAGE_CELLS - 1, cellX + dx), Math.min(Projection.COVERAGE_CELLS - 1, cellY + dy));
							known = Math.min(known, cell);
							any = Math.max(any, cell);
						}
					}
				}
				this.maskPixels[j * size + i] = 0xFF000000 | Math.round(known * 255);
				// Any terrain at all in the texel: the fog pass must not leave it uncovered.
				if (antique) this.anyTerrain[j * size + i] = any > 0;
			}
		}
		if (rounded) {
			// A radius in map units, so the rounded shape is the same at every zoom.
			this.roundOff(Math.max(1, (int) Math.round((antique ? 12 : 10) / texel)));
		}
		if (antique) {
			// Explored or not: the outline is drawn where the shader's smooth field crosses between.
			for (int i = 0; i < size * size; i++) this.maskPixels[i] = 0xFF000000 | ((this.maskPixels[i] & 0xFF) >= 230 ? 0xFF : 0);
		}
        // Zoomed out, coverage only changes as overviews are rebuilt from tiles saved seconds
        // before, already shown up close: easing it would reveal the same ground twice.
        this.reveal.update(this.maskPixels, this.lent, originU, originV, texel, reset || lod > 0, now);
        this.settleFlags = !antique && this.reveal.progress(now) < 1;
        if (antique) {
            int[] carto = this.cartography(originU, originV, texel, preview);
            for (int i = 0; i < size * size; i++) this.maskPixels[i] |= carto[i];
            // The shader frays the frontier by reading max(22 blocks, 7 art pixels) diagonally around
            // it, plus three art pixels of rings and the bilinear texel. The art pixel changes with
            // zoom between rebuilds: allow for it doubling.
            int reach = (int) Math.ceil(Math.max(40, 26 * art) / texel) + 2;
            this.markReach(new int[] {reach, reach, reach, reach}, ALL_EXPLORED, NONE_EXPLORED, this.anyTerrain);
        } else this.markReach(fogReach(texel, !surface), 0xFF00, 0xFF0000, null);
		this.maskTexture.write(0, 0, 0, size, this.maskPixels);
        maskNanos += System.nanoTime() - now;
	}

	/** Antique flags in green's low bits: every texel within reach explored, or none. */
	private static final int ALL_EXPLORED = 8 << 8, NONE_EXPLORED = 16 << 8;
	/** Coverage-only changes rebuild the mask at most this often. */
	private static final long MASK_REBUILD_NANOS = 40_000_000L;

	/**
	 * The surface coverage at a map point from the tiles of other levels of detail on the GPU, for a
	 * tile of level {@code lod} that isn't: the level below first, which draws it in more detail,
	 * then the overviews above. -1 when none of them is there. Only tiles the map holds count, so
	 * coverage the map drops (fog of war, another store) is never borrowed back.
	 */
	private float borrowedCoverage(MapLevel level, int lod, double u, double v) {
		for (int other = lod - 1; other <= TileKey.MAX_LOD; other = other == lod - 1 ? lod + 1 : other + 1) {
			if (other < 0) {
				continue;
			}
			double units = (double) Projection.TILE_UNITS * (1 << other);
			int tu = (int) Math.floor(u / units);
			int tv = (int) Math.floor(v / units);
			long key = (long) tu << 32 | (tv & 0xFFFFFFFFL);
			if (!this.lendLooked[other] || this.lendKey[other] != key) {
				this.lendTile[other] = level.peek(new TileKey(TileKey.SURFACE, tu, tv, other));
				this.lendKey[other] = key;
				this.lendLooked[other] = true;
			}
			MapTile tile = this.lendTile[other];
			if (tile == null) {
				continue;
			}
			if (!tile.hasCoverage()) {
				return 0;
			}
			double cell = units / Projection.COVERAGE_CELLS;
			int cx = Math.min(Projection.COVERAGE_CELLS - 1, (int) ((u - tu * units) / cell));
			int cy = Math.min(Projection.COVERAGE_CELLS - 1, (int) ((v - tv * units) / cell));
			return tile.coverage(cx, cy);
		}
		return -1;
	}
	private long lastMaskBuild;

	/**
	 * How far, in mask texels, the cloud shader reads known share around a point: {west, east,
	 * north, south}. Mirrors fog.fsh's cloudAt, which reads the flags of the texel under each point
	 * it looks at, so only its own blur counts: the taps, 1.9x on the diagonals and never under a
	 * texel, plus one texel for linear filtering and half for where in its texel the point sits.
	 */
	private static int[] fogReach(double texel, boolean cave) {
		double blur = 1.9 * Math.max((cave ? 2.5 : 7) / texel, 1);
		int reach = (int) Math.ceil(blur + 1.5);
		return new int[] {reach, reach, reach, reach};
	}

	/**
	 * Sets {@code knownBits} where every texel within reach is fully known, and {@code unknownBits}
	 * where every one is fully unknown (or, given {@code terrain}, has no terrain at all). The shader
	 * skips work there, because it knows what its reads would return. Texels past the mask's edge
	 * read as the edge texel, so the reach just stops at the edge.
	 */
	private void markReach(int[] reach, int knownBits, int unknownBits, boolean @Nullable [] terrain) {
		int size = this.size;
		int row = size + 1;
		// counts[(j + 1) * row + i + 1]: texels short of fully known (or with any known share) in the
		// rectangle from (0, 0) to (i, j).
		int[] notKnown = this.notKnownCounts;
		int[] notUnknown = this.notUnknownCounts;
		for (int j = 0; j < size; j++) {
			int lineNotKnown = 0;
			int lineNotUnknown = 0;
			for (int i = 0; i < size; i++) {
				int pixel = this.maskPixels[j * size + i];
                int known = pixel & 0xFF;
				lineNotKnown += known == 0xFF && pixel >>> 24 == 255 ? 0 : 1;
				lineNotUnknown += known == 0 && (terrain == null || !terrain[j * size + i]) ? 0 : 1;
				notKnown[(j + 1) * row + i + 1] = notKnown[j * row + i + 1] + lineNotKnown;
				notUnknown[(j + 1) * row + i + 1] = notUnknown[j * row + i + 1] + lineNotUnknown;
			}
		}
		for (int j = 0; j < size; j++) {
			int top = Math.max(0, j - reach[2]);
			int bottom = Math.min(size - 1, j + reach[3]) + 1;
			for (int i = 0; i < size; i++) {
				int left = Math.max(0, i - reach[0]);
				int right = Math.min(size - 1, i + reach[1]) + 1;
				int a = bottom * row + right;
				int b = top * row + right;
				int c = bottom * row + left;
				int d = top * row + left;
				if (notKnown[a] - notKnown[b] - notKnown[c] + notKnown[d] == 0) {
					this.maskPixels[j * size + i] |= knownBits;
				} else if (notUnknown[a] - notUnknown[b] - notUnknown[c] + notUnknown[d] == 0) {
					this.maskPixels[j * size + i] |= unknownBits;
				}
			}
		}
	}

	/** What the cartography plane was built from; the plane doesn't depend on exploration. */
	private record CartoInputs(int size, double originU, double originV, double texel, long revision, dev.diorama.MapDirection direction) {
	}

	private @Nullable CartoInputs cartoInputs;
	/** Biome class in green bits 5-7 and the water share in blue, per texel. */
	private int[] cartoPlane = new int[0];
	/** The domain warp is smooth, so it is worked out every WARP_STEP texels and interpolated. */
	private static final int WARP_STEP = 8;
	private float[] warpX = new float[0];
	private float[] warpZ = new float[0];
	/** How far, in blocks, biome borders are pushed around so they follow natural curves, not the sample grid. */
	private static final double WARP_BLOCKS = 38;

	/**
	 * The predicted biome classes under the mask, and their water share, for the shader to draw
	 * borders and coasts as curves. Each texel weighs the four preview samples around it
	 * bilinearly, at a world position bent by smooth world-anchored noise, so borders between the
	 * preview's square samples become organic shapes that stay put while panning and zooming.
	 * Only cached predictions are read; nothing here samples biomes.
	 */
	private int[] cartography(double originU, double originV, double texel, @Nullable SeedPreview preview) {
		int size = this.size;
		CartoInputs inputs = new CartoInputs(size, originU, originV, texel, preview == null ? -1 : preview.revision(), Projection.direction());
		if (inputs.equals(this.cartoInputs)) {
			return this.cartoPlane;
		}
		this.cartoInputs = inputs;
		if (this.cartoPlane.length != size * size) {
			this.cartoPlane = new int[size * size];
		}
		if (preview == null) {
			java.util.Arrays.fill(this.cartoPlane, 0);
			return this.cartoPlane;
		}
		int warp = size / WARP_STEP + 1;
		if (this.warpX.length != warp * warp) {
			this.warpX = new float[warp * warp];
			this.warpZ = new float[warp * warp];
		}
		dev.diorama.MapDirection direction = Projection.direction();
		for (int gj = 0; gj < warp; gj++) {
			double depth = Projection.zAt(originV + (gj * WARP_STEP + .5) * texel, 64);
			for (int gi = 0; gi < warp; gi++) {
				double u = originU + (gi * WARP_STEP + .5) * texel;
				double x = direction.worldX(u, depth), z = direction.worldZ(u, depth);
				this.warpX[gj * warp + gi] = (float) (WARP_BLOCKS * (worldNoise(x, z, 160, 1) + .45 * worldNoise(x, z, 61, 3)));
				this.warpZ[gj * warp + gi] = (float) (WARP_BLOCKS * (worldNoise(x, z, 160, 2) + .45 * worldNoise(x, z, 61, 4)));
			}
		}
		SeedPreview.Cursor cursor = preview.cursor();
		float[] weights = new float[8];
		for (int j = 0; j < size; j++) {
			double depth = Projection.zAt(originV + (j + .5) * texel, 64);
			int gj = j / WARP_STEP;
			float fj = (j % WARP_STEP) / (float) WARP_STEP;
			for (int i = 0; i < size; i++) {
				double u = originU + (i + .5) * texel;
				int gi = i / WARP_STEP;
				float fi = (i % WARP_STEP) / (float) WARP_STEP;
				int g = gj * warp + gi;
				double x = direction.worldX(u, depth) + lerp(lerp(this.warpX[g], this.warpX[g + 1], fi), lerp(this.warpX[g + warp], this.warpX[g + warp + 1], fi), fj);
				double z = direction.worldZ(u, depth) + lerp(lerp(this.warpZ[g], this.warpZ[g + 1], fi), lerp(this.warpZ[g + warp], this.warpZ[g + warp + 1], fi), fj);
				int step = cursor.step((int) Math.floor(x), (int) Math.floor(z));
				if (step == 0) {
					this.cartoPlane[j * size + i] = 0;
					continue;
				}
				// Bilinear weights of the four samples around the point, per class.
				int x0 = Math.floorDiv((int) Math.floor(x), step) * step, z0 = Math.floorDiv((int) Math.floor(z), step) * step;
				float fx = (float) ((x - x0) / step), fz = (float) ((z - z0) / step);
				java.util.Arrays.fill(weights, 0);
				weights[cursor.sample(x0, z0) & 7] += (1 - fx) * (1 - fz);
				weights[cursor.sample(x0 + step, z0) & 7] += fx * (1 - fz);
				weights[cursor.sample(x0, z0 + step) & 7] += (1 - fx) * fz;
				weights[cursor.sample(x0 + step, z0 + step) & 7] += fx * fz;
				int kind = 0;
				if (weights[0] < .5F) {
					kind = 1;
					float best = -1;
					for (int k = 2; k < 8; k++) if (weights[k] > best) { best = weights[k]; kind = k; }
					if (weights[1] >= .5F) kind = 1;
				}
				this.cartoPlane[j * size + i] = kind << 13 | Math.round(Math.min(1, weights[1]) * 255) << 16;
			}
		}
		return this.cartoPlane;
	}

	private static double lerp(double a, double b, double t) {
		return a + (b - a) * t;
	}

	/** Smooth value noise in [-1, 1] on a lattice {@code cell} blocks wide, anchored to the world. */
	private static double worldNoise(double x, double z, double cell, int seed) {
		double fx = x / cell, fz = z / cell;
		long ix = (long) Math.floor(fx), iz = (long) Math.floor(fz);
		double tx = fx - ix, tz = fz - iz;
		tx = tx * tx * (3 - 2 * tx);
		tz = tz * tz * (3 - 2 * tz);
		double a = lattice(ix, iz, seed), b = lattice(ix + 1, iz, seed);
		double c = lattice(ix, iz + 1, seed), d = lattice(ix + 1, iz + 1, seed);
		return a + (b - a) * tx + (c - a) * tz + (a - b - c + d) * tx * tz;
	}

	private static double lattice(long x, long z, int seed) {
		long h = x * 0x9E3779B97F4A7C15L ^ z * 0xC2B2AE3D27D4EB4FL ^ seed * 0x165667B19E3779F9L;
		h ^= h >>> 29;
		h *= 0xBF58476D1CE4E5B9L;
		h ^= h >>> 32;
		return (h & 0xFFFFFF) / (double) 0x7FFFFF - 1;
	}

	/**
	 * Rounds the known area off so the fog edge follows a smooth line instead of chunk steps.
	 * Blurs the mask, keeps only what stays solidly known, and takes the smaller of that and the
	 * original, so the fog only ever grows over the edge and never uncovers ground with no data.
	 */
	private void roundOff(int radius) {
		int size = this.size;
		for (int i = 0; i < size * size; i++) {
			this.blurA[i] = (this.maskPixels[i] & 0xFF) / 255.0F;
		}
		boxBlur(this.blurA, this.blurB, radius, true, size);
		boxBlur(this.blurB, this.blurA, radius, false, size);
		boxBlur(this.blurA, this.blurB, radius, true, size);
		boxBlur(this.blurB, this.blurA, radius, false, size);
		for (int i = 0; i < size * size; i++) {
			float t = Math.max(0, Math.min(1, (this.blurA[i] - 0.5F) / 0.35F));
			float rounded = t * t * (3 - 2 * t);
			int known = Math.min(this.maskPixels[i] & 0xFF, Math.round(rounded * 255));
			this.maskPixels[i] = 0xFF000000 | known;
		}
	}

	private static void boxBlur(float[] from, float[] to, int radius, boolean horizontal, int size) {
		float scale = 1.0F / (2 * radius + 1);
		for (int line = 0; line < size; line++) {
			float sum = 0;
			for (int k = -radius; k <= radius; k++) {
				sum += from[index(line, Math.max(0, Math.min(size - 1, k)), horizontal, size)];
			}
			for (int k = 0; k < size; k++) {
				to[index(line, k, horizontal, size)] = sum * scale;
				int add = Math.min(size - 1, k + radius + 1);
				int drop = Math.max(0, k - radius);
				sum += from[index(line, add, horizontal, size)] - from[index(line, drop, horizontal, size)];
			}
		}
	}

	private static int index(int line, int k, boolean horizontal, int size) {
		return horizontal ? line * size + k : k * size + line;
	}

	/** Whether the fog is easing away from newly discovered ground now. For tests. */
	public boolean easingFog() {
		return this.reveal.progress(System.nanoTime()) < 1;
	}

	public void close() {
		if (this.maskTexture != null) {
			this.maskTexture.close();
			this.maskTexture = null;
		}
		this.maskInputs = null;
		this.cartoInputs = null;
        this.reveal.reset();
	}
}
