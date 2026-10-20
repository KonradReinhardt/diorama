package dev.diorama.bake;

import dev.diorama.MapStyle;
import dev.diorama.Projection;
import dev.diorama.data.ChunkSnapshot;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Ray traces one map tile through the block data.
 *
 * <p>Every texture pixel fires four rays along the camera direction. Each ray walks the blocks in
 * its column (the camera faces due north, so a ray never changes x) until it hits something solid.
 * That hit is then lit: in the surface view by the sun, with a second ray toward it for shadows;
 * in a cave slice by the game's own block and sky light. Corner ambient occlusion and partial
 * colour from plants, glass and water come on top.
 */
public final class TileBaker {
	/** Rotated-grid sample offsets inside a pixel. */
	private static final double[][] SAMPLES = {{0.375, 0.125}, {0.875, 0.375}, {0.125, 0.625}, {0.625, 0.875}};
	/** One sample in the middle of the pixel: a quarter of the work, with jaggier block edges. */
	private static final double[][] FAST_SAMPLES = {{0.5, 0.5}};
	private static volatile boolean fast;

	// The sun is in the west, slightly south, so shadows fall to the east; how high is per style.
	private static final double SUN_AZIMUTH_X = -0.878;
	private static final double SUN_AZIMUTH_Z = 0.479;
	/** Small per-sample nudges of the sun direction, which soften shadow edges. */
	private static final double[][] SUN_JITTER = {{0.03, 0.0, -0.02}, {-0.02, 0.0, 0.03}, {0.02, 0.0, 0.02}, {-0.03, 0.0, -0.03}};


	/**
	 * Directions for sky occlusion: two rings round the sky and one straight up, weighted by how
	 * much light each part of the sky sends onto flat ground.
	 */
	private static final double[][] SKY_RAYS;
	private static final double[] SKY_WEIGHTS;
	/** How far sky occlusion looks for something overhead, in blocks. */
	public static final int SKY_REACH = 12;

	static {
		int ring = 6;
		SKY_RAYS = new double[ring * 2 + 1][];
		SKY_WEIGHTS = new double[ring * 2 + 1];
		double[] elevations = {Math.toRadians(25), Math.toRadians(55)};
		for (int r = 0; r < 2; r++) {
			for (int i = 0; i < ring; i++) {
				double azimuth = (i + r * 0.5) * 2 * Math.PI / ring;
				double e = elevations[r];
				SKY_RAYS[r * ring + i] = new double[] {Math.cos(e) * Math.cos(azimuth), Math.sin(e), Math.cos(e) * Math.sin(azimuth)};
				SKY_WEIGHTS[r * ring + i] = Math.sin(e);
			}
		}
		SKY_RAYS[ring * 2] = new double[] {0, 1, 0};
		SKY_WEIGHTS[ring * 2] = 1.5;
	}
	private static final float[] AO_LEVELS = {0.6F, 0.74F, 0.87F, 1.0F};
	/** How much nearer a neighbouring pixel has to be, in blocks, before an outline is drawn. */
	private static final float OUTLINE_DEPTH = 1.5F;
	/** How far below the cut a cave slice looks. */
	public static final int CAVE_DEPTH = 48;
	private static final int SHADOW_STEPS = 96;
	/** Longest shadow, in blocks along the sun direction. Bounds how far an edit can reach. */
	public static final double SHADOW_DISTANCE = 40;
	/**
	 * How far east, north and down from a block its shadow can land, for the lowest and highest
	 * sun a style can have.
	 */
	public static final int SHADOW_REACH_X = (int) Math.ceil(SHADOW_DISTANCE * Math.cos(Math.toRadians(dev.diorama.MapStyle.MIN_SUN_ELEVATION)) * -SUN_AZIMUTH_X) + 1;
	public static final int SHADOW_REACH_Z = (int) Math.ceil(SHADOW_DISTANCE * Math.cos(Math.toRadians(dev.diorama.MapStyle.MIN_SUN_ELEVATION)) * SUN_AZIMUTH_Z) + 1;
	public static final int SHADOW_REACH_Y = (int) Math.ceil(SHADOW_DISTANCE * Math.sin(Math.toRadians(dev.diorama.MapStyle.MAX_SUN_ELEVATION))) + 1;

	private static final float[] SRGB_TO_LINEAR = new float[256];
	private static final int[] LINEAR_TO_SRGB = new int[4096];

	static {
		for (int i = 0; i < 256; i++) {
			double v = i / 255.0;
			SRGB_TO_LINEAR[i] = (float) (v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4));
		}
		for (int i = 0; i < 4096; i++) {
			double v = i / 4095.0;
			double s = v <= 0.0031308 ? v * 12.92 : 1.055 * Math.pow(v, 1 / 2.4) - 0.055;
			LINEAR_TO_SRGB[i] = (int) Math.round(Math.max(0, Math.min(1, s)) * 255);
		}
	}

	private final TileKey key;
	private final BlockLooks looks;
	private final VoxelReader reader;
	private final boolean cave;
	/** The sky view of a dimension with a roof: it starts under the roof, skips rock and is lit like a cave. */
	private final boolean roofless;
	/** Lit by block light and the sky light that reaches in, not the sun: caves, and under a roof. */
	private final boolean lit;
	private final int startY;
	private final int limitY;
	/** One above the tallest block a camera ray of this tile can meet; camera rays start here. */
	private final int topY;
	/** The same for shadow and sky rays, which reach further north. */
	private final int rayTop;
	/** Cave slice that may only show blocks the player has seen. */
	private final boolean fogOfWar;
	/** What to record for the overlays, or null to record nothing. */
	private final @Nullable Overlays overlays;
	private final java.util.Map<Long, Boolean> slimeChunks = new java.util.HashMap<>();
	private final ChunkSource source;
	private final MapStyle style;
	private final boolean fastQuality;
	// The camera angle when this bake started; a change mid-bake makes the result stale anyway.
	private final double sin;
	private final double cos;
	// Direction toward the sun for this style.
	private final double sunX;
	private final double sunY;
	private final double sunZ;
	/** Sky visibility at block corners, 0..1, worked out once per bake. */
	private final it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap skyAtCorner = new it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap();

	// Result of the last trace, in linear light.
	private float outR;
	private float outG;
	private float outB;
    private float nightR, nightG, nightB;
	/** Camera depth of the last hit relative to {@link #depthOrigin}; larger is farther. */
	private float outDepth;
	/** Vertical depth in blocks of the water the last trace went through, or NaN if it met none. */
	private float outWater;
	/** Whether the last trace's surface was in direct sunlight. */
	private boolean outSun;
	// The block the last trace stopped on, when it was solid, for the overlays.
	private boolean hitSolid;
	private boolean hitTop;
	private int hitId;
	private int hitX;
	private int hitY;
	private int hitZ;
	/** Where across the block the ray landed, 0..1: east-west, and north-south on a top face. */
	private double hitFx;
	private double hitFz;
	/**
	 * Camera depth of this tile's top-left ray start. Depths are kept relative to it so they stay
	 * small enough for float precision anywhere in the world.
	 */
	private final double depthOrigin;

	private TileBaker(TileKey key, ChunkSource source, BlockLooks looks, boolean fogOfWar, @Nullable Overlays overlays, BakeContext context) {
		this.key = key;
		this.overlays = overlays;
		this.looks = looks;
		this.reader = new VoxelReader(source, looks.kind, context.direction());
		this.cave = key.isCave();
		this.roofless = !this.cave && source.roof() != ChunkSource.NO_ROOF;
		this.lit = this.cave || this.roofless;
		this.source = source;
		this.fogOfWar = this.lit && fogOfWar;
		this.style = context.style();
        this.fastQuality = context.fast();
		double elevation = Math.toRadians(this.style.sunElevation());
		this.sunX = Math.cos(elevation) * SUN_AZIMUTH_X;
		this.sunY = Math.sin(elevation);
		this.sunZ = Math.cos(elevation) * SUN_AZIMUTH_Z;
		this.sin = context.sin();
		this.cos = context.cos();
		this.topY = localTop(key, source, 0, context);
		this.rayTop = localTop(key, source, SKY_REACH, context);
		this.startY = this.cave ? Math.min(key.layer(), source.maxY()) : this.roofless ? Math.min(source.roof(), source.maxY()) : this.topY;
		double originZ = (key.minV() + this.startY * this.cos) / this.sin;
		this.depthOrigin = -(this.sin * this.startY + this.cos * originZ);
		this.limitY = this.cave ? Math.max(source.minY(), this.startY - CAVE_DEPTH) : source.minY();
	}

	/**
	 * One above the tallest block any ray or shadow ray of this tile can touch. Starting rays here
	 * instead of at the tallest block in the whole dimension skips a lot of empty sky.
	 */
	static int localTop(TileKey key, ChunkSource source, int moreNorth) {
        return localTop(key, source, moreNorth, BakeContext.capture());
    }

    private static int localTop(TileKey key, ChunkSource source, int moreNorth, BakeContext context) {
		int globalTop = Math.min(source.maxY(), source.topY());
		double minU = key.minU();
		double minV = key.minV();
		int x0 = Math.floorDiv((int) Math.floor(minU) - 1, 16);
		int x1 = Math.floorDiv((int) Math.ceil(minU + Projection.TILE_UNITS) + SHADOW_REACH_X + 1, 16);
		int z0 = Math.floorDiv((int) Math.floor(context.zAt(minV, source.minY())) - 1 - moreNorth, 16);
		int z1 = Math.floorDiv((int) Math.ceil(context.zAt(minV + Projection.TILE_UNITS, globalTop)) + SHADOW_REACH_Z + 1, 16);
		int top = Integer.MIN_VALUE;
		for (int cx = x0 - 2; cx <= x1; cx++) {
			for (int cz = z0; cz <= z1 + 1; cz++) {
				dev.diorama.MapDirection direction = context.direction();
                ChunkSnapshot chunk = source.chunk(direction.worldBlockX(cx, cz), direction.worldBlockZ(cx, cz));
				if (chunk != null && chunk.maxY != Integer.MIN_VALUE) {
					top = Math.max(top, chunk.maxY + 1);
				}
			}
		}
		return top == Integer.MIN_VALUE ? globalTop : Math.min(globalTop, top);
	}

	/**
	 * Bakes the whole tile, or only the {@link TileImage#BLOCK}-pixel blocks set in {@code blocks}.
	 * {@code fogOfWar} is the cave mode when the bake was scheduled, so it matches its generation.
	 */
	/** Whether bakes take one sample per pixel instead of four. Set from the Quality setting. */
	public static boolean fast() {
		return fast;
	}

	public static void setFast(boolean value) {
		fast = value;
	}

	public static TileImage bake(TileKey key, ChunkSource source, BlockLooks looks, @Nullable BitSet blocks, boolean fogOfWar) {
		return bake(key, source, looks, blocks, fogOfWar, null);
	}

	/**
	 * Same, merging in {@code saved}, the tile as last saved to disk: wherever this bake has no
	 * data for a pixel, the saved pixel stays. That shows places whose chunks aren't loaded, and
	 * keeps a bake at the edge of loaded chunks from erasing what was saved.
	 */
	public static TileImage bake(TileKey key, ChunkSource source, BlockLooks looks, @Nullable BitSet blocks, boolean fogOfWar, int @Nullable [] saved) {
		return bake(key, source, looks, blocks, fogOfWar, saved, null);
	}

	/** Same, also recording the {@link Overlays} bits of every pixel when {@code overlays} isn't null. */
	public static TileImage bake(
		TileKey key, ChunkSource source, BlockLooks looks, @Nullable BitSet blocks, boolean fogOfWar, int @Nullable [] saved, @Nullable Overlays overlays
	) {
		return bake(key, source, looks, blocks, fogOfWar, saved, overlays, BakeContext.capture());
    }

    public static TileImage bake(TileKey key, ChunkSource source, BlockLooks looks, @Nullable BitSet blocks,
        boolean fogOfWar, int @Nullable [] saved, @Nullable Overlays overlays, BakeContext context) {
        return bakePaired(key, source, looks, blocks, fogOfWar, saved == null ? null : new TilePixels(saved, null), overlays, context);
    }

    public static TileImage bakePaired(TileKey key, ChunkSource source, BlockLooks looks, @Nullable BitSet blocks,
        boolean fogOfWar, @Nullable TilePixels saved, @Nullable Overlays overlays, BakeContext context) {
        if (blocks == null) {
            return bakeFull(key, source, looks, fogOfWar, saved, overlays, context).image();
        }
        TileBaker baker = new TileBaker(key, source, looks, fogOfWar, overlays, context);
		int size = Projection.TILE_PIXELS;
		int perRow = size / TileImage.BLOCK;
		List<TileImage.Patch> patches = new ArrayList<>();
		for (int i = blocks.nextSetBit(0); i >= 0; i = blocks.nextSetBit(i + 1)) {
			int bx = i % perRow;
			int by = i / perRow;
			int[] pixels = new int[TileImage.BLOCK * TileImage.BLOCK];
            int[] night = baker.lit || !source.nightLighting() || !dev.diorama.EnvironmentLighting.supported() ? null : new int[pixels.length];
			byte[] info = overlays == null ? null : new byte[TileImage.BLOCK * TileImage.BLOCK];
			baker.bakePixels(bx * TileImage.BLOCK, by * TileImage.BLOCK, TileImage.BLOCK, TileImage.BLOCK, pixels, night, info);
			merge(pixels, night, saved, bx * TileImage.BLOCK, by * TileImage.BLOCK, TileImage.BLOCK);
			patches.add(new TileImage.Patch(bx, by, Mipmaps.build(pixels, TileImage.BLOCK), coverage(pixels, TileImage.BLOCK, TileImage.BLOCK), info, nightMips(night, TileImage.BLOCK)));
		}
		if (VoxelReader.DEBUG && !baker.reader.missing.isEmpty()) {
			StringBuilder sb = new StringBuilder();
			for (long m : baker.reader.missing) {
				int cx = (int) (m >> 32);
				int cz = (int) m;
				sb.append(' ').append(cx).append(',').append(cz).append(source.chunk(cx, cz) == null ? "(still missing)" : "(present now)");
			}
			dev.diorama.Diorama.LOGGER.info("Partial bake of {} ({} blocks) hit unknown chunks:{}", key, blocks.cardinality(), sb);
		}
		return TileImage.partial(key, patches);
	}

    /**
     * A full bake, and whether it drew the whole tile: {@code complete} when no more than a sliver
     * of the pixels known before had to be kept from {@code saved} for lack of terrain, so the
     * tile shows this bake's look throughout.
     */
    public record Baked(TileImage image, boolean complete) {
    }

    public static Baked bakeFull(TileKey key, ChunkSource source, BlockLooks looks, boolean fogOfWar,
        @Nullable TilePixels saved, @Nullable Overlays overlays, BakeContext context) {
        return bakeFull(key, source, looks, fogOfWar, saved, overlays, context, null);
    }

    /**
     * Same, and where {@code source} can't redraw a pixel that {@code saved} knows, traces it again
     * through {@code fallback} (saved chunks) instead of keeping the old pixel. Only pixels already
     * known are taken from the fallback, so it never maps anything that wasn't explored.
     */
    public static Baked bakeFull(TileKey key, ChunkSource source, BlockLooks looks, boolean fogOfWar,
        @Nullable TilePixels saved, @Nullable Overlays overlays, BakeContext context, @Nullable ChunkSource fallback) {
        TileBaker baker = new TileBaker(key, source, looks, fogOfWar, overlays, context);
        int size = Projection.TILE_PIXELS;
        int[] pixels = new int[size * size];
        int[] night = baker.lit || !source.nightLighting() || !dev.diorama.EnvironmentLighting.supported() ? null : new int[pixels.length];
        byte[] info = overlays == null ? null : new byte[size * size];
        baker.bakePixels(0, 0, size, size, pixels, night, info);
        if (fallback != null && saved != null) {
            redrawFromFallback(key, fallback, looks, fogOfWar, saved, overlays, context, pixels, night, info);
        }
        int known = 0;
        if (saved != null) for (int pixel : saved.day()) if (pixel >>> 24 != 0) known++;
        int kept = merge(pixels, night, saved, 0, 0, size);
        // The very edge of explored land can border chunks that were never saved; a sliver there
        // stays as it was, under the fog's rounded edge.
        boolean complete = kept <= known / 100;
        return new Baked(TileImage.full(key, Mipmaps.build(pixels, size), coverage(pixels, size, size), info, nightMips(night, size)), complete);
    }

    /** Re-traces, block by block, the pixels {@code saved} knows better than this bake did. */
    private static void redrawFromFallback(TileKey key, ChunkSource fallback, BlockLooks looks, boolean fogOfWar, TilePixels saved,
        @Nullable Overlays overlays, BakeContext context, int[] pixels, int @Nullable [] night, byte @Nullable [] info) {
        int size = Projection.TILE_PIXELS;
        int edge = TileImage.BLOCK;
        TileBaker baker = null;
        int[] block = new int[edge * edge];
        int[] blockNight = night == null ? null : new int[edge * edge];
        byte[] blockInfo = info == null ? null : new byte[edge * edge];
        for (int by = 0; by < size / edge; by++) {
            for (int bx = 0; bx < size / edge; bx++) {
                boolean needed = false;
                for (int y = 0; y < edge && !needed; y++) {
                    int row = (by * edge + y) * size + bx * edge;
                    for (int x = 0; x < edge && !needed; x++) needed = saved.day()[row + x] >>> 24 > pixels[row + x] >>> 24;
                }
                if (!needed) continue;
                if (baker == null) baker = new TileBaker(key, fallback, looks, fogOfWar, overlays, context);
                java.util.Arrays.fill(block, 0);
                if (blockNight != null) java.util.Arrays.fill(blockNight, 0);
                if (blockInfo != null) java.util.Arrays.fill(blockInfo, (byte) 0);
                baker.bakePixels(bx * edge, by * edge, edge, edge, block, blockNight, blockInfo);
                for (int y = 0; y < edge; y++) {
                    for (int x = 0; x < edge; x++) {
                        int i = (by * edge + y) * size + bx * edge + x;
                        int j = y * edge + x;
                        // Only where the saved tile knew something: what was never explored stays unknown.
                        if (saved.day()[i] >>> 24 == 0 || block[j] >>> 24 <= pixels[i] >>> 24) continue;
                        pixels[i] = block[j];
                        if (night != null) night[i] = blockNight[j];
                        if (info != null) info[i] = blockInfo[j];
                    }
                }
            }
        }
    }

	/** A full tile image from level-0 pixels: mips and fog coverage. */
	public static TileImage image(TileKey key, int[] pixels) {
		int size = Projection.TILE_PIXELS;
		return TileImage.full(key, Mipmaps.build(pixels, size), coverage(pixels, size, size));
	}

    public static TileImage imagePaired(TileKey key, TilePixels pixels) {
        int size = Projection.TILE_PIXELS;
        return TileImage.full(key, Mipmaps.build(pixels.day(), size), coverage(pixels.day(), size, size), null,
            nightMips(pixels.night(), size));
    }

    /** An unavailable night plane stays legacy; darkness with known alpha is still valid data. */
    static int @Nullable [][] nightMips(int @Nullable [] night, int edge) {
        if (night != null) for (int pixel : night) {
            if (pixel >>> 24 != 0) return Mipmaps.build(night, edge);
        }
        return null;
    }

	/** Keeps the saved pixel wherever the fresh one is less known; square areas only. Returns how many were kept. */
	static int merge(int[] pixels, int @Nullable [] night, @Nullable TilePixels saved, int px0, int py0, int edge) {
		if (saved == null) {
			return 0;
		}
		int kept = 0;
		int size = Projection.TILE_PIXELS;
		for (int y = 0; y < edge; y++) {
			for (int x = 0; x < edge; x++) {
				int i = y * edge + x;
				int index = (py0 + y) * size + px0 + x;
                int old = saved.day()[index];
				if ((old >>> 24) > (pixels[i] >>> 24)) {
					pixels[i] = old;
                    if (night != null) night[i] = saved.night() == null ? 0 : saved.night()[index];
					kept++;
				}
			}
		}
		return kept;
	}

	private void bakePixels(int px0, int py0, int width, int height, int[] out, int @Nullable [] nightOut, byte @Nullable [] info) {
		if (this.startY <= this.limitY) {
			return;
		}
		// Outlines compare each pixel with its neighbours, so bake a one-pixel border around the area;
		// the painted finish reads a little further.
		boolean painted = this.style.painted();
		int border = painted ? PaintedFinish.BORDER : this.style.outline() > 0 ? 1 : 0;
		int w = width + 2 * border;
		int h = height + 2 * border;
		int[] pixels = border == 0 ? out : new int[w * h];
        int[] nights = nightOut == null ? null : border == 0 ? nightOut : new int[w * h];
		float[] depth = border == 0 ? null : new float[w * h];
		float[] wet = painted ? new float[w * h] : null;
		float[] waterDepth = painted ? new float[w * h] : null;
		float[] sunlit = painted ? new float[w * h] : null;
		double minU = this.key.minU();
		double minV = this.key.minV();
		double unitsPerPixel = 1.0 / Projection.PIXELS_PER_UNIT;
		double[][] samples = this.fastQuality ? FAST_SAMPLES : SAMPLES;
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				float r = 0;
				float g = 0;
				float b = 0;
				float d = 0;
                float nr = 0, ng = 0, nb = 0;
				int known = 0;
				int wetRays = 0;
				int sunRays = 0;
				float water = 0;
				for (int s = 0; s < samples.length; s++) {
					double u = minU + (px0 + x - border + samples[s][0]) * unitsPerPixel;
					double v = minV + (py0 + y - border + samples[s][1]) * unitsPerPixel;
					if (this.trace(u, v, s)) {
						if (s == 0 && info != null && this.hitSolid) {
							int ix = x - border;
							int iy = y - border;
							if (ix >= 0 && iy >= 0 && ix < width && iy < height) {
								info[iy * width + ix] = (byte) this.overlayBits();
							}
						}
						r += this.outR;
						g += this.outG;
						b += this.outB;
						d += this.outDepth;
                        nr += this.nightR; ng += this.nightG; nb += this.nightB;
						known++;
						if (!Float.isNaN(this.outWater)) {
							wetRays++;
							water += this.outWater;
						}
						if (this.outSun) {
							sunRays++;
						}
					}
				}
				if (known > 0) {
					pixels[y * w + x] = this.abgr(r / known, g / known, b / known, known * 255 / samples.length, this.style.painted());
                    if (nights != null) nights[y * w + x] = this.abgr(nr / known, ng / known, nb / known, known * 255 / samples.length);
				}
				if (depth != null) {
					depth[y * w + x] = known > 0 ? d / known : Float.NaN;
				}
				if (painted && known > 0) {
					wet[y * w + x] = wetRays / (float) known;
					waterDepth[y * w + x] = wetRays > 0 ? water / wetRays : 0;
					sunlit[y * w + x] = sunRays / (float) known;
				}
			}
		}
		if (border == 0) {
			return;
		}
		if (painted) {
			int gx0 = (int) Math.round(minU * Projection.PIXELS_PER_UNIT) + px0 - border;
			int gy0 = (int) Math.round(minV * Projection.PIXELS_PER_UNIT) + py0 - border;
			PaintedFinish.apply(width, height, gx0, gy0, pixels, nights, depth, wet, waterDepth, sunlit, out, nightOut);
			return;
		}
		float keep = 1 - this.style.outline();
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int i = (y + 1) * w + x + 1;
				int pixel = pixels[i];
                int nightPixel = nights == null ? 0 : nights[i];
				float here = depth[i];
				if (pixel != 0 && (nearer(depth[i - 1], here) || nearer(depth[i + 1], here) || nearer(depth[i - w], here) || nearer(depth[i + w], here))) {
					pixel = darken(pixel, keep);
                    nightPixel = darken(nightPixel, keep);
				}
				out[y * width + x] = pixel;
                if (nightOut != null) nightOut[y * width + x] = nightPixel;
			}
		}
	}

	/** The {@link Overlays} bits for the block the last trace stopped on. */
	private int overlayBits() {
		int bits = 0;
		// Lines one pixel wide: a pixel spans a quarter block across, and less of a top face down
		// the screen, since top faces are foreshortened.
		if ((this.hitX & 15) == 0 && this.hitFx < 1.0 / Projection.PIXELS_PER_UNIT) {
			bits |= Overlays.CHUNK_WEST;
		}
		if (this.hitTop && (this.hitZ & 15) == 0 && this.hitFz < 1.0 / (Projection.PIXELS_PER_UNIT * this.sin)) {
			bits |= Overlays.CHUNK_NORTH;
		}
		Long seed = this.overlays.slimeSeed();
		if (seed != null) {
			int cx = this.reader.direction.worldBlockX(this.hitX, this.hitZ) >> 4;
			int cz = this.reader.direction.worldBlockZ(this.hitX, this.hitZ) >> 4;
			if (this.slimeChunks.computeIfAbsent(((long) cx << 32) | (cz & 0xFFFFFFFFL), k -> Overlays.slimeChunk(seed, cx, cz))) {
				bits |= Overlays.SLIME;
			}
		}
		// A dot in the middle of each top face a monster could spawn on: a block it can stand on,
		// two blocks of room above, and no block light, the rule for most monsters since 1.18.
		// By day the sky light there must be low too; at night any sky light is dark enough.
		if (this.overlays.spawnSpots() && this.hitTop && this.looks.spawnOn[this.hitId]
			&& this.hitFx >= 0.3 && this.hitFx < 0.7 && this.hitFz >= 0.3 && this.hitFz < 0.7) {
			int feet = this.reader.state(this.hitX, this.hitY + 1, this.hitZ);
			int head = this.reader.state(this.hitX, this.hitY + 2, this.hitZ);
			if (feet != VoxelReader.UNKNOWN && head != VoxelReader.UNKNOWN && this.looks.spawnIn[feet] && this.looks.spawnIn[head]
				&& this.reader.blockLight(this.hitX, this.hitY + 1, this.hitZ) == 0) {
				int sky = this.reader.skyLight(this.hitX, this.hitY + 1, this.hitZ);
				bits |= sky <= Overlays.DAYLIGHT_SPAWN_LIMIT ? Overlays.SPAWN_ANY_TIME : Overlays.SPAWN_AT_NIGHT;
			}
		}
		return bits;
	}

	/** True if the neighbour is a much nearer surface, so this pixel sits just behind an edge. */
	private static boolean nearer(float neighbour, float here) {
		return here - neighbour > OUTLINE_DEPTH;
	}

	private static int darken(int abgr, float keep) {
		int r = Math.round((abgr & 0xFF) * keep);
		int g = Math.round(((abgr >> 8) & 0xFF) * keep);
		int b = Math.round(((abgr >> 16) & 0xFF) * keep);
		return (abgr & 0xFF000000) | (b << 16) | (g << 8) | r;
	}

	/** Traces one ray. Returns false when it ran into a chunk the map has no data for. */
	private boolean trace(double u, double v, int sample) {
		final double sin = this.sin;
		final double cos = this.cos;
		final int x = (int) Math.floor(u);
		final double fx = u - x;
		final double y0 = this.startY;
		final double z0 = (v + y0 * cos) / sin;

		int by = this.startY - 1;
		int bz = (int) Math.floor(z0);
		double tMaxY = (y0 - by) / sin;
		double tMaxZ = (z0 - bz) / cos;
		final double tDeltaY = 1.0 / sin;
		final double tDeltaZ = 1.0 / cos;
		double t = 0;
		boolean enteredTop = true;

		boolean skippingRock = this.lit;
		this.hitSolid = false;
		this.outWater = Float.NaN;
		this.outSun = false;
		// Colour picked up from plants and glass on the way down, applied to the surface below.
		float overR = 0;
		float overG = 0;
		float overB = 0;
        float overNightR = 0, overNightG = 0, overNightB = 0;
		float through = 1;
		// Water the ray passed through.
		int waterId = -1;
		double waterT = 0;
		int waterBy = 0;
		int waterBz = 0;

		while (by >= this.limitY) {
			if (skippingRock) {
				int solid = this.reader.solid(x, by, bz);
				if (solid == VoxelReader.UNKNOWN) {
					return false;
				}
				if (solid == 1) {
					// Still inside the rock above the first open space below the cut.
					if (tMaxY < tMaxZ) {
						by--;
						t = tMaxY;
						tMaxY += tDeltaY;
						enteredTop = true;
					} else {
						bz--;
						t = tMaxZ;
						tMaxZ += tDeltaZ;
						enteredTop = false;
					}
					continue;
				}
				skippingRock = false;
			}
			int id = this.reader.state(x, by, bz);
			if (id == VoxelReader.UNKNOWN) {
				return false;
			}
			byte kind = this.looks.kind[id];

			switch (kind) {
				case BlockLooks.SOLID, BlockLooks.LAVA -> {
					if (this.fogOfWar && !this.reader.isSeen(x, by, bz)
						&& !(enteredTop ? this.reader.isSeen(x, by + 1, bz) : this.reader.isSeen(x, by, bz + 1))) {
						return false;
					}
					double hitY = y0 - t * sin;
					double hitZ = z0 - t * cos;
					this.hitSolid = kind == BlockLooks.SOLID;
					this.hitId = id;
					this.hitTop = enteredTop;
					this.hitX = x;
					this.hitY = by;
					this.hitZ = bz;
					this.hitFx = fx;
					this.hitFz = hitZ - bz;
					this.outDepth = (float) (-(sin * hitY + cos * hitZ) - this.depthOrigin);
					this.shade(id, x, by, bz, fx, hitY, hitZ, enteredTop, sample);
					if (through < 1) {
						// The overlay tints the albedo; approximate by mixing it in after lighting.
						this.nightR = this.nightR * through + overNightR;
                        this.nightG = this.nightG * through + overNightG;
                        this.nightB = this.nightB * through + overNightB;
                        float light = luminance(this.outR, this.outG, this.outB) / Math.max(0.05F, luminance(this.albedoR, this.albedoG, this.albedoB));
						this.outR = this.outR * through + overR * light;
						this.outG = this.outG * through + overG * light;
						this.outB = this.outB * through + overB * light;
					}
					if (waterId >= 0) {
						this.mixWater(waterId, x, waterBy, waterBz, u, z0, waterT, t, sample);
					}
					return true;
				}
				case BlockLooks.WATER -> {
					if (waterId < 0) {
						waterId = id;
						waterT = t;
						waterBy = by;
						waterBz = bz;
					}
				}
				case BlockLooks.PLANT, BlockLooks.GLASS -> {
					float w = kind == BlockLooks.PLANT ? (this.style.painted() ? 0.22F : 0.4F) : 0.25F;
					this.albedo(id, x, bz, true);
					overR += through * w * this.albedoR;
					overG += through * w * this.albedoG;
					overB += through * w * this.albedoB;
					float block = (float) Math.pow(this.reader.blockLight(x, by, bz) / 15.0F, 1.5);
                    float sky = this.reader.skyLight(x, by, bz) / 15.0F;
                    float emission = this.looks.emission[id] == 0 ? 0 : 0.35F + this.looks.emission[id] / 15.0F * .85F;
                    overNightR += through * w * this.albedoR * Math.max(emission, .018F * sky + .9F * block);
                    overNightG += through * w * this.albedoG * Math.max(emission, .025F * sky + .65F * block);
                    overNightB += through * w * this.albedoB * Math.max(emission, .047F * sky + .35F * block);
                    through *= 1 - w;
				}
				default -> {
				}
			}

			if (tMaxY < tMaxZ) {
				by--;
				t = tMaxY;
				tMaxY += tDeltaY;
				enteredTop = true;
			} else {
				bz--;
				t = tMaxZ;
				tMaxZ += tDeltaZ;
				enteredTop = false;
			}
		}

		if (this.fogOfWar && (skippingRock || waterId < 0 || !this.reader.isSeen(x, waterBy, waterBz))) {
			return false;
		}
		this.outDepth = (float) (-(sin * (y0 - t * sin) + cos * (z0 - t * cos)) - this.depthOrigin);
		if (skippingRock) {
			// Solid rock all the way down the slice: draw the cut face.
			this.outR = 0.0065F;
			this.outG = 0.0068F;
			this.outB = 0.0085F;
            this.nightR = this.outR; this.nightG = this.outG; this.nightB = this.outB;
		} else if (waterId >= 0) {
			this.outR = 0;
			this.outG = 0;
			this.outB = 0;
            this.nightR = this.nightG = this.nightB = 0;
			this.mixWater(waterId, x, waterBy, waterBz, u, z0, waterT, waterT + 64, sample);
		} else {
			this.outR = 0.01F;
			this.outG = 0.01F;
			this.outB = 0.015F;
            this.nightR = 0.001F; this.nightG = 0.001F; this.nightB = 0.002F;
		}
		return true;
	}

	private float albedoR;
	private float albedoG;
	private float albedoB;

	/** Sets {@code albedo*} to the block's linear colour for its top or front face at this column. */
	private void albedo(int id, int x, int z, boolean top) {
		BlockLooks l = this.looks;
		int base = top ? l.top[id] : l.side[id];
		int tinted = top ? l.topTinted[id] : l.sideTinted[id];
		float w = top ? l.topTintWeight[id] : l.sideTintWeight[id];
		int tint = this.tint(id, x, z);
		float bw = 1 - w;
		this.albedoR = SRGB_TO_LINEAR[(base >> 16) & 0xFF] * bw + SRGB_TO_LINEAR[(tinted >> 16) & 0xFF] * SRGB_TO_LINEAR[(tint >> 16) & 0xFF] * w;
		this.albedoG = SRGB_TO_LINEAR[(base >> 8) & 0xFF] * bw + SRGB_TO_LINEAR[(tinted >> 8) & 0xFF] * SRGB_TO_LINEAR[(tint >> 8) & 0xFF] * w;
		this.albedoB = SRGB_TO_LINEAR[base & 0xFF] * bw + SRGB_TO_LINEAR[tinted & 0xFF] * SRGB_TO_LINEAR[tint & 0xFF] * w;
	}

	private int tint(int id, int x, int z) {
		byte kind = this.looks.tint[id];
		if (kind == BlockLooks.TINT_NONE) {
			int constant = this.looks.constantTint[id];
			return constant < 0 ? 0xFFFFFF : constant;
		}
		ChunkSnapshot snapshot = this.reader.snapshot(x, z);
		if (snapshot == null) {
			return 0xFFFFFF;
		}
		int wx = this.reader.direction.worldBlockX(x, z);
        int wz = this.reader.direction.worldBlockZ(x, z);
        int i = ((wz & 15) << 4) | (wx & 15);
		ChunkSnapshot.Tints tints = BiomeTints.of(snapshot);
		return switch (kind) {
			case BlockLooks.TINT_GRASS -> tints.grass()[i];
			case BlockLooks.TINT_FOLIAGE -> tints.foliage()[i];
			case BlockLooks.TINT_DRY_FOLIAGE -> tints.dryFoliage()[i];
			case BlockLooks.TINT_WATER -> tints.water()[i];
			default -> 0xFFFFFF;
		};
	}

	private void shade(int id, int x, int by, int bz, double fx, double hitY, double hitZ, boolean top, int sample) {
		this.albedo(id, x, bz, top);
		float variation = this.style.blockVariation();
		if (variation > 0) {
			// A fixed per-block nudge in brightness, so a field of one block isn't a flat sheet.
			float nudge = 1 + variation * blockNoise(x, by, bz);
			this.albedoR *= nudge;
			this.albedoG *= nudge;
			this.albedoB *= nudge;
		}
		// The open cell in front of the face the ray hit.
		int frontY = top ? by + 1 : by;
		int frontZ = top ? bz : bz + 1;
		float ao = top
			? this.ambientOcclusionTop(x, by + 1, bz, fx, hitZ - bz)
			: this.ambientOcclusionSouth(x, by, bz + 1, fx, hitY - by);
		// The painted look is lit flat: sunlit or in shadow, with no soft falloff in between.
		float bandedAo = this.style.painted() ? 1 : ao;
		float emission = this.looks.emission[id] / 15.0F;
		if (this.looks.kind[id] == BlockLooks.LAVA) {
			float crust = 1 + 0.3F * blockNoise(x, by, bz);
			this.albedoR *= crust;
			this.albedoG *= crust * crust;
			this.albedoB *= crust;
		}

		float warmLocal = (float) Math.pow(this.reader.blockLight(x, frontY, frontZ) / 15.0F, 1.5);
        float skyLocal = this.reader.skyLight(x, frontY, frontZ) / 15.0F;
        float nr = (0.018F * skyLocal + 0.9F * warmLocal) * bandedAo;
        float ng = (0.025F * skyLocal + 0.65F * warmLocal) * bandedAo;
        float nb = (0.047F * skyLocal + 0.35F * warmLocal) * bandedAo;
        float lr;
		float lg;
		float lb;
		if (this.lit) {
			int skyLight = this.reader.skyLight(x, frontY, frontZ);
			float bl = this.reader.blockLight(x, frontY, frontZ) / 15.0F;
			float sl = skyLight / 15.0F * 0.8F;
			float warm = (float) Math.pow(bl, 1.5);
			float cool = (float) Math.pow(sl, 1.5);
			// Unlit caves stay readable; torches, lava and daylight make them glow.
			float base = 0.42F;
			lr = (base * 0.92F + warm * 0.9F + cool * 0.6F) * bandedAo;
			lg = (base * 0.96F + warm * 0.75F + cool * 0.65F) * bandedAo;
			lb = (base * 1.08F + warm * 0.5F + cool * 0.75F) * bandedAo;
			if (this.cave) {
				// Deeper below the cut reads darker; under a roof the whole view is one level.
				float depth = (float) (this.startY - hitY);
				float fade = 1 - Math.min(0.5F, depth / 60.0F);
				lr *= fade;
				lg *= fade;
				lb *= fade;
			}
		} else {
			// Painted shadows keep a crisp edge, the way they are laid in with one flat colour.
			double[] j = this.style.painted() ? NO_JITTER : SUN_JITTER[sample];
			double sx = this.sunX + j[0];
			double sy = this.sunY + j[1];
			double sz = this.sunZ + j[2];
			// Relative to a top face, so tops stay as bright whatever the sun's height.
			double lambert = top ? 1 : Math.max(0, sz) / sy;
			float direct = 0;
			if (lambert > 0) {
				double ox = x + fx;
				double oy = top ? by + 1 + 1e-4 : hitY;
				double oz = top ? hitZ : bz + 1 + 1e-4;
				direct = this.inShadow(ox, oy, oz, sx, sy, sz) ? 0 : (float) (this.style.sunStrength() * lambert);
			}
			this.outSun = direct > 0;
			// Both endpoints share material, intersections and expensive occlusion samples.
			float ambient = this.style.ambient() * ao;
			float occlusion = this.style.skyOcclusion();
			if (occlusion > 0) {
				// Less sky overhead, less ambient light: under trees, down in valleys and at the
				// foot of walls. This is what makes the levels of the world read apart.
				float sky = top ? this.skyTop(x, by + 1, bz, fx, hitZ - bz) : this.skySouth(x, by, bz + 1, fx, hitY - by);
				ambient *= 1 - occlusion * (1 - (0.15F + 0.85F * sky));
			}
			if (this.style.painted()) {
				// One flat shadow colour: no corner or sky occlusion.
				ambient = this.style.ambient();
			}
			float[] sky = this.style.shadowTint();
			float[] sun = this.style.sunTint();
			lr = ambient * sky[0] + direct * sun[0] + warmLocal * 0.08F;
			lg = ambient * sky[1] + direct * sun[1] + warmLocal * 0.065F;
			lb = ambient * sky[2] + direct * sun[2] + warmLocal * 0.04F;
			float heightShade = this.style.painted() ? 0 : this.style.heightShade();
			if (heightShade != 0) {
				// High ground a little brighter, low ground darker, so hills and valleys still read
				// apart when zoomed out too far to see their shadows.
				float above = (float) Math.max(-1, Math.min(1, (hitY - 64) / 64));
				float f = 1 + heightShade * above;
				lr *= f;
				lg *= f;
				lb *= f;
			}
		}
		if (emission > 0) {
			float e = 0.35F + emission * 0.85F;
            nr = Math.max(nr, e); ng = Math.max(ng, e); nb = Math.max(nb, e);
			lr = Math.max(lr, e);
			lg = Math.max(lg, e);
			lb = Math.max(lb, e);
		}
		if (!top) {
			// Faces that aren't up read darker, the way Minecraft shades its own block sides; painted,
			// just enough to tell a wall from the ground in front of it.
			float side = this.style.painted() ? PAINTED_SIDE : 0.82F;
			lr *= side;
			lg *= side;
			lb *= side;
            nr *= side; ng *= side; nb *= side;
		}
		this.outR = this.albedoR * lr;
		this.outG = this.albedoG * lg;
		this.outB = this.albedoB * lb;
        this.nightR = this.lit ? this.outR : this.albedoR * nr;
        this.nightG = this.lit ? this.outG : this.albedoG * ng;
        this.nightB = this.lit ? this.outB : this.albedoB * nb;
	}

	private void mixWater(int waterId, int x, int by, int bz, double u, double z0, double tEntry, double tHit, int sample) {
		this.albedo(waterId, x, bz, true);
		float wr = this.albedoR;
		float wg = this.albedoG;
		float wb = this.albedoB;
		float light;
		if (this.lit) {
			int bl = this.reader.blockLight(x, by + 1, bz);
			int sl = this.reader.skyLight(x, by + 1, bz);
			light = 0.42F + (float) Math.pow(Math.max(bl, sl * 0.8F) / 15.0F, 1.5) * 0.8F;
		} else {
			double sy = this.sunY + SUN_JITTER[sample][1];
			double ox = u;
			double oy = this.startY - tEntry * this.sin + 1e-4;
			double oz = z0 - tEntry * this.cos;
			boolean shadow = this.inShadow(ox, oy, oz, this.sunX, sy, this.sunZ);
			// Lit like the ground around it: the style's light, sky occlusion and height shade.
			float ambient = this.style.ambient();
			float occlusion = this.style.painted() ? 0 : this.style.skyOcclusion();
			if (occlusion > 0) {
				float sky = this.skyTop(x, by + 1, bz, u - x, oz - bz);
				ambient *= 1 - occlusion * (1 - (0.15F + 0.85F * sky));
			}
			light = ambient + (shadow ? 0 : this.style.sunStrength());
			float heightShade = this.style.painted() ? 0 : this.style.heightShade();
			if (heightShade != 0) {
				light *= 1 + heightShade * (float) Math.max(-1, Math.min(1, (by + 1 - 64) / 64.0));
			}
			this.outSun = !shadow;
		}
		float nightLight = this.lit ? light : 0.035F * this.reader.skyLight(x, by + 1, bz) / 15.0F
            + 0.8F * (float) Math.pow(this.reader.blockLight(x, by + 1, bz) / 15.0F, 1.5);
        double depth = Math.max(0, tHit - tEntry);
		this.outWater = (float) (depth * this.sin);
		if (this.style.painted()) {
			// Flat washes that step from pale shallows to deep water, each a little darker and
			// hiding more of the bottom.
			float vertical = this.outWater;
			float clear = bands(vertical, WATER_CLARITY);
			float tone = bands(vertical, WATER_TONE);
			// Toward the teal of a painted sea, keeping some of the biome's own water colour.
			wr = wr * (1 - PAINTED_SEA_MIX) + PAINTED_SEA[0] * PAINTED_SEA_MIX;
			wg = wg * (1 - PAINTED_SEA_MIX) + PAINTED_SEA[1] * PAINTED_SEA_MIX;
			wb = wb * (1 - PAINTED_SEA_MIX) + PAINTED_SEA[2] * PAINTED_SEA_MIX;
			float sheen = 0.035F;
			this.outR = this.outR * clear + wr * light * tone * (1 - clear) + sheen * light;
			this.outG = this.outG * clear + wg * light * tone * (1 - clear) + sheen * light;
			this.outB = this.outB * clear + wb * light * tone * (1 - clear) + sheen * 1.2F * light;
			this.nightR = this.nightR * clear + wr * nightLight * tone * (1 - clear) + sheen * nightLight;
			this.nightG = this.nightG * clear + wg * nightLight * tone * (1 - clear) + sheen * nightLight;
			this.nightB = this.nightB * clear + wb * nightLight * tone * (1 - clear) + sheen * 1.2F * nightLight;
			return;
		}
		if (this.style.clearWater()) {
			// Light through water loses the colours the water doesn't have: red goes first, so
			// shallows over sand turn turquoise and deep water ends up the water's own blue.
			float peak = Math.max(wr, Math.max(wg, wb));
			float tr = (float) Math.exp(-depth * 0.45 * (1 - wr / peak) - depth * 0.04);
			float tg = (float) Math.exp(-depth * 0.45 * (1 - wg / peak) - depth * 0.04);
			float tb = (float) Math.exp(-depth * 0.45 * (1 - wb / peak) - depth * 0.04);
			float deepR = wr * 0.55F * light;
			float deepG = wg * 0.55F * light;
			float deepB = wb * 0.55F * light;
			// A little sky reflected off the surface.
			float sheen = 0.05F * light;
			this.nightR = this.nightR * tr + wr * 0.55F * nightLight * (1 - tr) + 0.05F * nightLight;
            this.nightG = this.nightG * tg + wg * 0.55F * nightLight * (1 - tg) + 0.05F * nightLight;
            this.nightB = this.nightB * tb + wb * 0.55F * nightLight * (1 - tb) + 0.06F * nightLight;
            this.outR = this.outR * tr + deepR * (1 - tr) + sheen;
			this.outG = this.outG * tg + deepG * (1 - tg) + sheen;
			this.outB = this.outB * tb + deepB * (1 - tb) + sheen * 1.2F;
			return;
		}
		float a = (float) Math.max(0.5, Math.min(0.94, 1 - Math.exp(-0.2 * depth)));
		this.nightR = this.nightR * (1 - a) + wr * nightLight * a;
        this.nightG = this.nightG * (1 - a) + wg * nightLight * a;
        this.nightB = this.nightB * (1 - a) + wb * nightLight * a;
        this.outR = this.outR * (1 - a) + wr * light * a;
		this.outG = this.outG * (1 - a) + wg * light * a;
		this.outB = this.outB * (1 - a) + wb * light * a;
	}

	/** Walks from a point toward the sun; true if a solid block is in the way. */
	private boolean inShadow(double ox, double oy, double oz, double dx, double dy, double dz) {
		return this.blocked(ox, oy, oz, dx, dy, dz, SHADOW_DISTANCE, SHADOW_STEPS);
	}

	/** Sky visibility on a top face at {@code y}, blended from its four corners. */
	private float skyTop(int x, int y, int z, double fx, double fz) {
		return bilinear(this.skyAt(x, y, z), this.skyAt(x + 1, y, z), this.skyAt(x, y, z + 1), this.skyAt(x + 1, y, z + 1), fx, fz);
	}

	/** Sky visibility on a south face at {@code z}, blended from its four corners; {@code fy} runs bottom to top. */
	private float skySouth(int x, int y, int z, double fx, double fy) {
		return bilinear(this.skyAt(x, y + 1, z), this.skyAt(x + 1, y + 1, z), this.skyAt(x, y, z), this.skyAt(x + 1, y, z), fx, 1 - fy);
	}

	/** Share of the sky seen from a block corner, 0..1, from a few short rays upward. */
	private float skyAt(int x, int y, int z) {
		long key = ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | ((y + 2048) & 0xFFF);
		float cached = this.skyAtCorner.getOrDefault(key, -1F);
		if (cached >= 0) {
			return cached;
		}
		double open = 0;
		double total = 0;
		for (int i = 0; i < SKY_RAYS.length; i++) {
			double[] d = SKY_RAYS[i];
			// Start a hair along the ray, so it begins in the block it heads into, not on a boundary.
			if (!this.blocked(x + d[0] * 1e-3, y + d[1] * 1e-3, z + d[2] * 1e-3, d[0], d[1], d[2], SKY_REACH, SKY_REACH * 3)) {
				open += SKY_WEIGHTS[i];
			}
			total += SKY_WEIGHTS[i];
		}
		float sky = (float) (open / total);
		this.skyAtCorner.put(key, sky);
		return sky;
	}

	/** Walks from a point along a direction for up to {@code distance} blocks; true if a solid block is in the way. */
	private boolean blocked(double ox, double oy, double oz, double dx, double dy, double dz, double distance, int steps) {
		int cx = (int) Math.floor(ox);
		int cy = (int) Math.floor(oy);
		int cz = (int) Math.floor(oz);
		if (cy < this.rayTop && this.occludes(cx, cy, cz)) {
			return true;
		}
		int top = this.rayTop;
		if (dy > 0) {
			// A ray that only climbs can't be blocked once it is above every solid block in the
			// chunks it can reach, so it stops there instead of walking on through open sky.
			double ex = ox + dx * distance;
			double ez = oz + dz * distance;
			top = Math.min(top, this.reader.solidTop(
				((int) Math.floor(Math.min(ox, ex)) - 1) >> 4, ((int) Math.floor(Math.min(oz, ez)) - 1) >> 4,
				((int) Math.floor(Math.max(ox, ex)) + 1) >> 4, ((int) Math.floor(Math.max(oz, ez)) + 1) >> 4
			));
		}
		int stepX = dx > 0 ? 1 : -1;
		int stepY = dy > 0 ? 1 : -1;
		int stepZ = dz > 0 ? 1 : -1;
		double tDeltaX = dx == 0 ? Double.MAX_VALUE : Math.abs(1 / dx);
		double tDeltaY = dy == 0 ? Double.MAX_VALUE : Math.abs(1 / dy);
		double tDeltaZ = dz == 0 ? Double.MAX_VALUE : Math.abs(1 / dz);
		double tMaxX = dx == 0 ? Double.MAX_VALUE : (dx > 0 ? cx + 1 - ox : ox - cx) * tDeltaX;
		double tMaxY = dy == 0 ? Double.MAX_VALUE : (dy > 0 ? cy + 1 - oy : oy - cy) * tDeltaY;
		double tMaxZ = dz == 0 ? Double.MAX_VALUE : (dz > 0 ? cz + 1 - oz : oz - cz) * tDeltaZ;
		for (int i = 0; i < steps; i++) {
			if (Math.min(tMaxX, Math.min(tMaxY, tMaxZ)) > distance) {
				return false;
			}
			if (tMaxX < tMaxY && tMaxX < tMaxZ) {
				cx += stepX;
				tMaxX += tDeltaX;
			} else if (tMaxY < tMaxZ) {
				cy += stepY;
				tMaxY += tDeltaY;
			} else {
				cz += stepZ;
				tMaxZ += tDeltaZ;
			}
			if (cy >= top) {
				return false;
			}
			int solid = this.reader.solid(cx, cy, cz);
			if (solid == VoxelReader.UNKNOWN) {
				return false;
			}
			if (solid == 1) {
				return true;
			}
		}
		return false;
	}

	private boolean occludes(int x, int y, int z) {
		return this.reader.solid(x, y, z) == 1;
	}

	/** Smooth corner occlusion on a top face, from the eight blocks around the cell above it. */
	private float ambientOcclusionTop(int x, int y, int z, double fx, double fz) {
		boolean n = this.occludes(x, y, z - 1);
		boolean s = this.occludes(x, y, z + 1);
		boolean w = this.occludes(x - 1, y, z);
		boolean e = this.occludes(x + 1, y, z);
		float nw = corner(w, n, this.occludes(x - 1, y, z - 1));
		float ne = corner(e, n, this.occludes(x + 1, y, z - 1));
		float sw = corner(w, s, this.occludes(x - 1, y, z + 1));
		float se = corner(e, s, this.occludes(x + 1, y, z + 1));
		return bilinear(nw, ne, sw, se, fx, fz);
	}

	/** Same for a south face; {@code z} is the open layer in front of it, {@code fy} runs bottom to top. */
	private float ambientOcclusionSouth(int x, int y, int z, double fx, double fy) {
		boolean up = this.occludes(x, y + 1, z);
		boolean down = this.occludes(x, y - 1, z);
		boolean w = this.occludes(x - 1, y, z);
		boolean e = this.occludes(x + 1, y, z);
		float tw = corner(w, up, this.occludes(x - 1, y + 1, z));
		float te = corner(e, up, this.occludes(x + 1, y + 1, z));
		float bw = corner(w, down, this.occludes(x - 1, y - 1, z));
		float be = corner(e, down, this.occludes(x + 1, y - 1, z));
		return bilinear(tw, te, bw, be, fx, 1 - fy);
	}

	private static float corner(boolean side1, boolean side2, boolean diagonal) {
		if (side1 && side2) {
			return AO_LEVELS[0];
		}
		return AO_LEVELS[3 - (side1 ? 1 : 0) - (side2 ? 1 : 0) - (diagonal ? 1 : 0)];
	}

	/** {@code a b} on the first row, {@code c d} on the second. */
	private static float bilinear(float a, float b, float c, float d, double fx, double fy) {
		float top = (float) (a + (b - a) * fx);
		float bottom = (float) (c + (d - c) * fx);
		return (float) (top + (bottom - top) * fy);
	}

	private static float luminance(float r, float g, float b) {
		return 0.2126F * r + 0.7152F * g + 0.0722F * b;
	}

	/** No nudge of the sun: the painted look's single, crisp shadow. */
	private static final double[] NO_JITTER = {0, 0, 0};
	/** How much darker a painted wall is than the ground in front of it. */
	private static final float PAINTED_SIDE = 0.86F;
	/** Per painted water wash ({@link PaintedFinish#WATER_STEPS}): how much of the bottom shows, and how light the water is. */
	private static final float[] WATER_CLARITY = {0.38F, 0.16F, 0.04F, 0F};
	private static final float[] WATER_TONE = {1.12F, 0.96F, 0.8F, 0.66F};
	/** The painted sea, linear, and how far biome water colours are pulled toward it. */
	private static final float[] PAINTED_SEA = {0.05F, 0.25F, 0.36F};
	private static final float PAINTED_SEA_MIX = 0.55F;

	/** The water wash's value at this vertical depth, eased over a fifth of a block either side of each step. */
	private static float bands(float vertical, float[] values) {
		float value = values[0];
		float[] steps = PaintedFinish.WATER_STEPS;
		for (int i = 0; i < steps.length; i++) {
			value += (values[i + 1] - values[i]) * PaintedFinish.smoothstep(steps[i] - 0.2F, steps[i] + 0.2F, vertical);
		}
		return value;
	}

	/** -1..1, fixed per block position. */
	private static float blockNoise(int x, int y, int z) {
		int h = x * 73_856_093 ^ y * 19_349_663 ^ z * 83_492_791;
		h ^= h >>> 13;
		h *= 0x5bd1e995;
		h ^= h >>> 15;
		return ((h & 0xFFFF) / 32767.5F) - 1;
	}

	private int abgr(float r, float g, float b, int alpha) {
		return this.abgr(r, g, b, alpha, false);
	}

	/** Darkest a painted colour gets, linear: pigment over paper is never black, and leans violet. */
	private static final float[] PIGMENT_FLOOR = {0.016F, 0.012F, 0.021F};
	/** Where painted highlights start to roll off, linear, and how much further they can still climb. */
	private static final float PIGMENT_KNEE = 0.6F;
	private static final float PIGMENT_HEADROOM = 0.32F;

	/** {@code pigment}: the painted look's paper and paint, no pure black and no blown-out white. */
	private int abgr(float r, float g, float b, int alpha, boolean pigment) {
		float saturation = this.style.saturation();
		float exposure = this.style.exposure();
		float l = luminance(r, g, b);
		r = (l + (r - l) * saturation) * exposure;
		g = (l + (g - l) * saturation) * exposure;
		b = (l + (b - l) * saturation) * exposure;
		if (pigment) {
			r = pigment(r, PIGMENT_FLOOR[0]);
			g = pigment(g, PIGMENT_FLOOR[1]);
			b = pigment(b, PIGMENT_FLOOR[2]);
		}
		int ri = LINEAR_TO_SRGB[Math.min(4095, Math.max(0, (int) (r * 4095)))];
		int gi = LINEAR_TO_SRGB[Math.min(4095, Math.max(0, (int) (g * 4095)))];
		int bi = LINEAR_TO_SRGB[Math.min(4095, Math.max(0, (int) (b * 4095)))];
		return (alpha << 24) | (bi << 16) | (gi << 8) | ri;
	}

	private static float pigment(float x, float floor) {
		x = floor + Math.max(0, x) * (1 - floor);
		if (x <= PIGMENT_KNEE) {
			return x;
		}
		float over = x - PIGMENT_KNEE;
		return PIGMENT_KNEE + over / (1 + over / PIGMENT_HEADROOM);
	}

	private static byte[] coverage(int[] pixels, int width, int height) {
		int cellPixels = Projection.TILE_PIXELS / Projection.COVERAGE_CELLS;
		int cellsX = width / cellPixels;
		int cellsY = height / cellPixels;
		byte[] out = new byte[cellsX * cellsY];
		for (int cy = 0; cy < cellsY; cy++) {
			for (int cx = 0; cx < cellsX; cx++) {
				int sum = 0;
				for (int y = 0; y < cellPixels; y++) {
					int row = (cy * cellPixels + y) * width + cx * cellPixels;
					for (int x = 0; x < cellPixels; x++) {
						sum += pixels[row + x] >>> 24;
					}
				}
				out[cy * cellsX + cx] = (byte) (sum / (cellPixels * cellPixels));
			}
		}
		return out;
	}
}
