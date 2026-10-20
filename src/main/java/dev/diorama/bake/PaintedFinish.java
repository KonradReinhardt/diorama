package dev.diorama.bake;

import java.util.SplittableRandom;

/**
 * Finishes a freshly traced patch of the painted look: what turns a lit render into a map painted
 * by hand. In order:
 *
 * <ol>
 * <li>Paint: a Kuwahara filter flattens each little area into the dab of colour a brush leaves,
 * keeping the edges between them.
 * <li>Ink: soft, warm lines of varying weight behind every nearer edge and between areas of
 * clearly different colour, a firmer line round every coast, and lighter lines where water
 * steps into a deeper wash.
 * <li>Light: a thin warm catch-light on sunlit edges that face the camera, and a line of foam
 * where water meets land.
 * <li>Texture: brush strokes that follow a slowly turning flow (long and level on water), an
 * uneven wash of pigment over large areas, and paper grain.
 * </ol>
 *
 * <p>Every pattern is looked up by world pixel, never by position in the patch, so a patch baked
 * alone matches the same pixels of a whole tile and neighbouring tiles meet without a seam. The
 * cel-shaded light itself is worked out per ray in {@link TileBaker}; this only needs what a
 * pixel's neighbours look like.
 */
public final class PaintedFinish {
	/** Kuwahara reach in pixels: the four windows touching a pixel are squares of {@code RADIUS + 1}. */
	private static final int RADIUS = 3;
	/** Traced pixels needed round the patch: the filter, plus one for the lines read off its result. */
	public static final int BORDER = RADIUS + 1;
	/** How much of the brush dab replaces the traced colour: nearly all, so block textures melt into flat paint. */
	private static final float DAB = 0.9F;

	/** Weight of the line behind a nearer edge, and how much nearer it must be (blocks) to start and to reach full weight. */
	private static final float EDGE_INK = 0.48F;
	private static final float EDGE_NEAR = 1.2F;
	private static final float EDGE_FULL = 3.5F;
	/** Weight of the line between areas of clearly different colour, and the colour distances it fades in over. */
	private static final float COLOUR_INK = 0.24F;
	private static final float COLOUR_FROM = 0.11F;
	private static final float COLOUR_TO = 0.30F;
	/** The coast: an ink line on the land and foam on the water. */
	private static final float COAST_INK = 0.42F;
	private static final float FOAM = 0.38F;
	/** Lines where water steps into a deeper wash, like the rings a painter draws off a coast. */
	private static final float RIPPLE_INK = 0.15F;
	/** Catch-light on sunlit edges of a nearer surface. */
	private static final float RIM_LIGHT = 0.13F;
	/** What an ink line multiplies a colour by at full weight: a dark, warm sepia rather than black. */
	private static final float[] INK = {0.40F, 0.33F, 0.30F};
	/** Ripples are inked in a deeper blue-green. */
	private static final float[] RIPPLE = {0.62F, 0.74F, 0.80F};
	private static final float[] FOAM_COLOUR = {0.94F, 0.95F, 0.90F};
	private static final float[] RIM_COLOUR = {1.0F, 0.93F, 0.78F};

	/** Brush stroke contrast on land and water, the large uneven wash, its warm-cool drift, and the paper grain. */
	private static final float STROKES = 0.035F;
	/** The same strokes four times larger, so open ground still shows brushwork from further out. */
	private static final float BROAD_STROKES = 0.045F;
	private static final int BROAD_SCALE = 4;
	private static final float WATER_STROKES = 0.05F;
	private static final float WASH = 0.06F;
	private static final float WASH_HUE = 0.035F;
	private static final float GRAIN = 0.012F;

	/** Water vertical depths, in blocks, where one wash gives way to the next. Matches {@link #washBand}. */
	static final float[] WATER_STEPS = {1.25F, 3.5F, 8F};

	private static final int BRUSH_SIZE = 256;
	private static final int WASH_SIZE = 128;
	/** World pixels per wash texel: the wash repeats every {@code WASH_SIZE * WASH_SCALE} pixels, 4096 blocks. */
	private static final int WASH_SCALE = 128;
	private static final int GRAIN_SIZE = 128;
	private static final float[] LAND_BRUSH = strokes(0x5EED_B105L, false);
	private static final float[] WATER_BRUSH = strokes(0x0CEA_17L, true);
	private static final float[] WASH_NOISE = wash(0xA0A7_E2L);
	private static final float[] GRAIN_NOISE = grain(0x9A_9E7L);

	private PaintedFinish() {
	}

	/**
	 * Which wash a water pixel of this vertical depth belongs to, 0 for the shallows. Rings are
	 * inked where neighbouring water changes wash.
	 */
	static int washBand(float depth) {
		int band = 0;
		while (band < WATER_STEPS.length && depth >= WATER_STEPS[band]) {
			band++;
		}
		return band;
	}

	/**
	 * Finishes {@code width x height} pixels from a traced area {@link #BORDER} pixels larger on every
	 * side. Unknown pixels (alpha 0) stay unknown and every alpha is kept, so coverage never grows.
	 *
	 * @param gx0 world pixel column of the traced area's first column
	 * @param gy0 world pixel row of the traced area's first row
	 * @param day traced colours, ABGR, {@code (width + 2 * BORDER)} wide
	 * @param night the night colours, or null
	 * @param depth camera depth per pixel, NaN where unknown
	 * @param wet share of a pixel's rays that went through water first, 0..1
	 * @param waterDepth vertical depth of that water, blocks
	 * @param sun share of a pixel's rays whose surface was in direct sunlight, 0..1
	 */
	static void apply(int width, int height, int gx0, int gy0, int[] day, int @org.jspecify.annotations.Nullable [] night,
		float[] depth, float[] wet, float[] waterDepth, float[] sun, int[] out, int @org.jspecify.annotations.Nullable [] nightOut) {
		int w = width + 2 * BORDER;
		int h = height + 2 * BORDER;
		int n = w * h;
		float[] r = new float[n], g = new float[n], b = new float[n];
		unpack(day, r, g, b);
		float[] nr = null, ng = null, nb = null;
		if (night != null) {
			nr = new float[n];
			ng = new float[n];
			nb = new float[n];
			unpack(night, nr, ng, nb);
		}

		// 1. Paint: every pixel the lines will read, so the interior and a ring of one round it.
		float[] pr = r.clone(), pg = g.clone(), pb = b.clone();
		float[] qr = nr == null ? null : nr.clone(), qg = ng == null ? null : ng.clone(), qb = nb == null ? null : nb.clone();
		float[] mean = new float[6];
		Windows windows = new Windows(w, h, day, r, g, b);
		for (int y = RADIUS; y < h - RADIUS; y++) {
			for (int x = RADIUS; x < w - RADIUS; x++) {
				int i = y * w + x;
				if (day[i] >>> 24 == 0 || !dab(x, y, w, day, windows, r, g, b, nr, ng, nb, mean)) {
					continue;
				}
				pr[i] += (mean[0] - pr[i]) * DAB;
				pg[i] += (mean[1] - pg[i]) * DAB;
				pb[i] += (mean[2] - pb[i]) * DAB;
				if (qr != null) {
					qr[i] += (mean[3] - qr[i]) * DAB;
					qg[i] += (mean[4] - qg[i]) * DAB;
					qb[i] += (mean[5] - qb[i]) * DAB;
				}
			}
		}

		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int i = (y + BORDER) * w + x + BORDER;
				int o = y * width + x;
				int alpha = day[i] >>> 24;
				if (alpha == 0) {
					out[o] = day[i];
					if (nightOut != null) {
						nightOut[o] = night[i];
					}
					continue;
				}
				int gx = gx0 + x + BORDER;
				int gy = gy0 + y + BORDER;
				boolean water = wet[i] >= 0.5F;

				// 2. Ink, 3. light. The strongest line wins rather than adding up.
				float wobble = 0.72F + 0.5F * (0.5F + 0.5F * washAt(gx * 9 + 1_337, gy * 9 - 4_242));
				float edge = 0;
				float colour = 0;
				float coast = 0;
				float ripple = 0;
				float rim = 0;
				float foam = 0;
				float here = depth[i];
				float lum = luma(pr[i], pg[i], pb[i]);
				int band = water ? washBand(waterDepth[i]) : 0;
				for (int k = 0; k < 8; k++) {
					int j = i + NEIGHBOUR_X[k] + NEIGHBOUR_Y[k] * w;
					if (day[j] >>> 24 == 0) {
						continue;
					}
					float weight = k < 4 ? 1 : 0.5F;
					float gap = here - depth[j];
					edge = Math.max(edge, weight * smoothstep(EDGE_NEAR, EDGE_FULL, gap));
					rim = Math.max(rim, weight * smoothstep(EDGE_NEAR, EDGE_FULL, -gap));
					boolean otherWater = wet[j] >= 0.5F;
					if (water != otherWater) {
						if (water) {
							foam = Math.max(foam, weight);
						} else {
							coast = Math.max(coast, weight);
						}
					} else if (water && washBand(waterDepth[j]) < band) {
						ripple = Math.max(ripple, weight);
					}
					if (k < 4 && water == otherWater) {
						// Only the darker side of a colour boundary takes the line, so it stays one pixel thin.
						float dr = pr[j] - pr[i], dg = pg[j] - pg[i], db = pb[j] - pb[i];
						if (luma(pr[j], pg[j], pb[j]) > lum) {
							colour = Math.max(colour, smoothstep(COLOUR_FROM, COLOUR_TO, (float) Math.sqrt(dr * dr + dg * dg + db * db)));
						}
					}
				}
				float ink = Math.max(Math.max(edge * EDGE_INK, colour * COLOUR_INK), coast * COAST_INK) * wobble;
				float rings = ripple * RIPPLE_INK * wobble;
				float light = rim * RIM_LIGHT * sun[i] * (1 - edge);
				float froth = foam * FOAM * (0.75F + 0.25F * wobble);

				// 4. Texture.
				float stroke = water
					? WATER_BRUSH[Math.floorMod(gy, BRUSH_SIZE) * BRUSH_SIZE + Math.floorMod(gx, BRUSH_SIZE)] * WATER_STROKES
					: LAND_BRUSH[Math.floorMod(gy, BRUSH_SIZE) * BRUSH_SIZE + Math.floorMod(gx, BRUSH_SIZE)] * STROKES;
				if (!water) {
					stroke += broadStroke(gx, gy) * BROAD_STROKES;
				}
				float wash = washAt(gx, gy);
				float hue = washAt(gx + 52_000, gy - 31_000);
				float grain = GRAIN_NOISE[Math.floorMod(gy, GRAIN_SIZE) * GRAIN_SIZE + Math.floorMod(gx, GRAIN_SIZE)] * GRAIN;
				float tone = 1 + stroke + wash * WASH + grain;

				float cr = pr[i], cg = pg[i], cb = pb[i];
				cr = ink(cr, ink, INK[0]);
				cg = ink(cg, ink, INK[1]);
				cb = ink(cb, ink, INK[2]);
				cr = ink(cr, rings, RIPPLE[0]);
				cg = ink(cg, rings, RIPPLE[1]);
				cb = ink(cb, rings, RIPPLE[2]);
				cr += (FOAM_COLOUR[0] - cr) * froth;
				cg += (FOAM_COLOUR[1] - cg) * froth;
				cb += (FOAM_COLOUR[2] - cb) * froth;
				cr += (RIM_COLOUR[0] - cr) * light;
				cg += (RIM_COLOUR[1] - cg) * light;
				cb += (RIM_COLOUR[2] - cb) * light;
				out[o] = pack(cr * tone * (1 + hue * WASH_HUE), cg * tone, cb * tone * (1 - hue * WASH_HUE), alpha);

				if (nightOut != null) {
					int nightAlpha = night[i] >>> 24;
					if (nightAlpha == 0) {
						nightOut[o] = night[i];
						continue;
					}
					// The same lines and brushwork at night; foam catches a little of what light there is.
					float mr = ink(ink(qr[i], ink, INK[0]), rings, RIPPLE[0]);
					float mg = ink(ink(qg[i], ink, INK[1]), rings, RIPPLE[1]);
					float mb = ink(ink(qb[i], ink, INK[2]), rings, RIPPLE[2]);
					float lift = 1 + froth * 0.6F;
					float nightTone = 1 + stroke + grain;
					nightOut[o] = pack(mr * lift * nightTone, mg * lift * nightTone, mb * lift * nightTone, nightAlpha);
				}
			}
		}
	}

	private static final int[] NEIGHBOUR_X = {-1, 1, 0, 0, -1, 1, -1, 1};
	private static final int[] NEIGHBOUR_Y = {0, 0, -1, 1, -1, -1, 1, 1};

	/**
	 * The Kuwahara dab at (x, y): the mean colour of whichever of the four overlapping windows
	 * touching the pixel varies least, ignoring unknown pixels. Night takes the window day chose,
	 * so the two stay in register. False if no window has enough known pixels.
	 */
	private static boolean dab(int x, int y, int w, int[] day, Windows windows, float[] r, float[] g, float[] b,
		float @org.jspecify.annotations.Nullable [] nr, float @org.jspecify.annotations.Nullable [] ng,
		float @org.jspecify.annotations.Nullable [] nb, float[] mean) {
		float best = Float.MAX_VALUE;
		int bestX = 0;
		int bestY = 0;
		for (int quadrant = 0; quadrant < 4; quadrant++) {
			int x0 = (quadrant & 1) == 0 ? x - RADIUS : x;
			int y0 = (quadrant & 2) == 0 ? y - RADIUS : y;
			int corner = y0 * w + x0;
			float count = windows.count[corner];
			float sum = windows.sum[corner];
			float squares = windows.squares[corner];
			if (count < (RADIUS + 1) * (RADIUS + 1) / 2 + 1) {
				continue;
			}
			float variance = squares / count - (sum / count) * (sum / count);
			if (variance < best) {
				best = variance;
				bestX = x0;
				bestY = y0;
			}
		}
		if (best == Float.MAX_VALUE) {
			return false;
		}
		java.util.Arrays.fill(mean, 0);
		int count = 0;
		for (int yy = bestY; yy <= bestY + RADIUS; yy++) {
			for (int xx = bestX; xx <= bestX + RADIUS; xx++) {
				int j = yy * w + xx;
				if (day[j] >>> 24 == 0) {
					continue;
				}
				mean[0] += r[j];
				mean[1] += g[j];
				mean[2] += b[j];
				if (nr != null) {
					mean[3] += nr[j];
					mean[4] += ng[j];
					mean[5] += nb[j];
				}
				count++;
			}
		}
		for (int c = 0; c < 6; c++) {
			mean[c] /= count;
		}
		return true;
	}

	/**
	 * The known-pixel count, luma sum and squared luma sum of every Kuwahara window, stored at its
	 * top-left pixel. Each is summed afresh from its own pixels, a row of sums then a column, never
	 * by sliding, so a window adds up the same in a patch as in a whole tile.
	 */
	private static final class Windows {
		final float[] count;
		final float[] sum;
		final float[] squares;

		Windows(int w, int h, int[] day, float[] r, float[] g, float[] b) {
			int n = w * h;
			float[] known = new float[n];
			float[] luma = new float[n];
			float[] luma2 = new float[n];
			for (int i = 0; i < n; i++) {
				if (day[i] >>> 24 != 0) {
					float l = luma(r[i], g[i], b[i]);
					known[i] = 1;
					luma[i] = l;
					luma2[i] = l * l;
				}
			}
			float[] row = new float[n];
			this.count = box(known, row, w, h);
			this.sum = box(luma, row, w, h);
			this.squares = box(luma2, row, w, h);
		}

		/** Sums over each {@code RADIUS + 1} square, by its top-left corner; {@code row} is scratch. */
		private static float[] box(float[] values, float[] row, int w, int h) {
			int k = RADIUS + 1;
			for (int y = 0; y < h; y++) {
				for (int x = 0; x + k <= w; x++) {
					float total = 0;
					for (int d = 0; d < k; d++) {
						total += values[y * w + x + d];
					}
					row[y * w + x] = total;
				}
			}
			float[] out = new float[w * h];
			for (int y = 0; y + k <= h; y++) {
				for (int x = 0; x + k <= w; x++) {
					float total = 0;
					for (int d = 0; d < k; d++) {
						total += row[(y + d) * w + x];
					}
					out[y * w + x] = total;
				}
			}
			return out;
		}
	}

	/** Darkens toward {@code ink} times the colour by {@code amount}. */
	private static float ink(float colour, float amount, float ink) {
		return colour * (1 - amount * (1 - ink));
	}

	/** The large wash at a world pixel, -1..1, smoothly interpolated. */
	private static float washAt(int gx, int gy) {
		float fx = Math.floorMod(gx, WASH_SIZE * WASH_SCALE) / (float) WASH_SCALE;
		float fy = Math.floorMod(gy, WASH_SIZE * WASH_SCALE) / (float) WASH_SCALE;
		int x0 = (int) fx;
		int y0 = (int) fy;
		float tx = smooth(fx - x0);
		float ty = smooth(fy - y0);
		int x1 = (x0 + 1) % WASH_SIZE;
		int y1 = (y0 + 1) % WASH_SIZE;
		float top = WASH_NOISE[y0 * WASH_SIZE + x0] + (WASH_NOISE[y0 * WASH_SIZE + x1] - WASH_NOISE[y0 * WASH_SIZE + x0]) * tx;
		float bottom = WASH_NOISE[y1 * WASH_SIZE + x0] + (WASH_NOISE[y1 * WASH_SIZE + x1] - WASH_NOISE[y1 * WASH_SIZE + x0]) * tx;
		return top + (bottom - top) * ty;
	}

	/** {@link #LAND_BRUSH} magnified {@link #BROAD_SCALE} times, interpolated, at a world pixel. */
	private static float broadStroke(int gx, int gy) {
		int size = BRUSH_SIZE * BROAD_SCALE;
		float fx = Math.floorMod(gx + 7_919, size) / (float) BROAD_SCALE;
		float fy = Math.floorMod(gy - 3_571, size) / (float) BROAD_SCALE;
		int x0 = (int) fx;
		int y0 = (int) fy;
		float tx = fx - x0;
		float ty = fy - y0;
		int x1 = (x0 + 1) % BRUSH_SIZE;
		int y1 = (y0 + 1) % BRUSH_SIZE;
		float top = LAND_BRUSH[y0 * BRUSH_SIZE + x0] + (LAND_BRUSH[y0 * BRUSH_SIZE + x1] - LAND_BRUSH[y0 * BRUSH_SIZE + x0]) * tx;
		float bottom = LAND_BRUSH[y1 * BRUSH_SIZE + x0] + (LAND_BRUSH[y1 * BRUSH_SIZE + x1] - LAND_BRUSH[y1 * BRUSH_SIZE + x0]) * tx;
		return top + (bottom - top) * ty;
	}

	private static float smooth(float t) {
		return t * t * (3 - 2 * t);
	}

	static float smoothstep(float from, float to, float x) {
		if (!(x > from)) {
			return 0;
		}
		if (x >= to) {
			return 1;
		}
		return smooth((x - from) / (to - from));
	}

	private static float luma(float r, float g, float b) {
		return 0.299F * r + 0.587F * g + 0.114F * b;
	}

	private static void unpack(int[] abgr, float[] r, float[] g, float[] b) {
		for (int i = 0; i < abgr.length; i++) {
			int c = abgr[i];
			r[i] = (c & 0xFF) / 255F;
			g[i] = ((c >> 8) & 0xFF) / 255F;
			b[i] = ((c >> 16) & 0xFF) / 255F;
		}
	}

	private static int pack(float r, float g, float b, int alpha) {
		int ri = Math.round(Math.max(0, Math.min(1, r)) * 255);
		int gi = Math.round(Math.max(0, Math.min(1, g)) * 255);
		int bi = Math.round(Math.max(0, Math.min(1, b)) * 255);
		return (alpha << 24) | (bi << 16) | (gi << 8) | ri;
	}

	/**
	 * A tiling sheet of brush strokes, -1..1 around 0: thousands of short tapered dabs laid one
	 * over another, each a little lighter or darker, with faint bristle streaks along it. On land
	 * they follow a direction that turns slowly across the sheet; on water they run long and level.
	 */
	private static float[] strokes(long seed, boolean water) {
		int size = BRUSH_SIZE;
		float[] sheet = new float[size * size];
		float[] flow = wash(seed ^ 0x51DE_F10EL, 16);
		SplittableRandom random = new SplittableRandom(seed);
		int count = water ? 1_700 : 2_900;
		for (int s = 0; s < count; s++) {
			double cx = random.nextDouble(size);
			double cy = random.nextDouble(size);
			double angle;
			double length;
			double width;
			if (water) {
				angle = random.nextDouble(-0.12, 0.12);
				length = random.nextDouble(9, 22);
				width = random.nextDouble(0.7, 1.4);
			} else {
				int fx = (int) cx * 16 / size;
				int fy = (int) cy * 16 / size;
				angle = Math.PI * (0.15 + 0.7 * (0.5 + 0.5 * flow[fy * 16 + fx])) + random.nextDouble(-0.25, 0.25);
				length = random.nextDouble(4, 11);
				width = random.nextDouble(1.1, 2.4);
			}
			float value = (float) random.nextDouble(-1, 1);
			double opacity = random.nextDouble(0.35, 0.8);
			double bristles = random.nextDouble(1.2, 2.2);
			double phase = random.nextDouble(Math.PI * 2);
			double cos = Math.cos(angle);
			double sin = Math.sin(angle);
			int reach = (int) Math.ceil(length) + 1;
			for (int dy = -reach; dy <= reach; dy++) {
				for (int dx = -reach; dx <= reach; dx++) {
					int px = (int) Math.floor(cx) + dx;
					int py = (int) Math.floor(cy) + dy;
					double ox = px + 0.5 - cx;
					double oy = py + 0.5 - cy;
					double along = (ox * cos + oy * sin) / length;
					double across = (-ox * sin + oy * cos) / width;
					// Wider at the start than at the lifted end, like a real stroke.
					double taper = 1 - 0.35 * (along + 1) / 2;
					double e = along * along + across * across / (taper * taper);
					if (e >= 1) {
						continue;
					}
					double streak = 0.78 + 0.22 * Math.sin(across * width * bristles * Math.PI + phase);
					float alpha = (float) (opacity * Math.sqrt(1 - e) * streak);
					int i = Math.floorMod(py, size) * size + Math.floorMod(px, size);
					sheet[i] += (value - sheet[i]) * alpha;
				}
			}
		}
		return normalise(sheet);
	}

	private static float[] wash(long seed) {
		return wash(seed, WASH_SIZE);
	}

	/** Tiling fractal value noise, -1..1: three octaves on lattices that divide {@code size}. */
	private static float[] wash(long seed, int size) {
		float[] out = new float[size * size];
		float amplitude = 1;
		for (int cells = 4; cells <= 16 && cells <= size; cells *= 2) {
			SplittableRandom random = new SplittableRandom(seed + cells);
			float[] lattice = new float[cells * cells];
			for (int i = 0; i < lattice.length; i++) {
				lattice[i] = (float) random.nextDouble(-1, 1);
			}
			for (int y = 0; y < size; y++) {
				for (int x = 0; x < size; x++) {
					float fx = x * cells / (float) size;
					float fy = y * cells / (float) size;
					int x0 = (int) fx;
					int y0 = (int) fy;
					float tx = smooth(fx - x0);
					float ty = smooth(fy - y0);
					int x1 = (x0 + 1) % cells;
					int y1 = (y0 + 1) % cells;
					float top = lattice[y0 * cells + x0] + (lattice[y0 * cells + x1] - lattice[y0 * cells + x0]) * tx;
					float bottom = lattice[y1 * cells + x0] + (lattice[y1 * cells + x1] - lattice[y1 * cells + x0]) * tx;
					out[y * size + x] += amplitude * (top + (bottom - top) * ty);
				}
			}
			amplitude *= 0.5F;
		}
		return normalise(out);
	}

	/** Paper tooth: white noise softened by its neighbours, so it reads as fibre, not static. */
	private static float[] grain(long seed) {
		int size = GRAIN_SIZE;
		SplittableRandom random = new SplittableRandom(seed);
		float[] noise = new float[size * size];
		for (int i = 0; i < noise.length; i++) {
			noise[i] = (float) random.nextDouble(-1, 1);
		}
		float[] out = new float[noise.length];
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				float sum = 2 * noise[y * size + x];
				sum += noise[y * size + (x + 1) % size] + noise[y * size + (x + size - 1) % size];
				sum += 0.5F * (noise[((y + 1) % size) * size + x] + noise[((y + size - 1) % size) * size + x]);
				out[y * size + x] = sum;
			}
		}
		return normalise(out);
	}

	/** Shifts to a mean of 0 and scales so the largest swing is 1. */
	private static float[] normalise(float[] values) {
		double sum = 0;
		for (float v : values) {
			sum += v;
		}
		float mean = (float) (sum / values.length);
		float peak = 1e-6F;
		for (int i = 0; i < values.length; i++) {
			values[i] -= mean;
			peak = Math.max(peak, Math.abs(values[i]));
		}
		for (int i = 0; i < values.length; i++) {
			values[i] /= peak;
		}
		return values;
	}
}
