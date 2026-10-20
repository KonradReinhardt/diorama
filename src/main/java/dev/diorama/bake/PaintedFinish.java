package dev.diorama.bake;

import dev.diorama.Projection;
import java.util.SplittableRandom;
import org.jspecify.annotations.Nullable;

/**
 * Finishes a freshly traced patch of the painted look: what turns a lit render into a map painted
 * by hand. The map is a grid of block faces, each {@link #CELL} pixels square (see
 * {@link Projection}), and everything here keeps to it. In order:
 *
 * <ol>
 * <li>Paint: a Kuwahara filter over whole faces flattens neighbouring blocks into the dab of
 * colour a brush leaves, keeping the edges between areas. It moves each face's albedo, never its
 * light, so shadows keep their exact texels.
 * <li>Ink: soft, warm lines behind every nearer edge, from the faces' own geometry, and between
 * faces of clearly different colour; a firmer line round every coast, and lighter lines where
 * water steps into a deeper wash. One pixel, four neighbours, so lines never thicken at corners.
 * <li>Light: a thin warm catch-light on sunlit edges that face the camera, and a line of foam
 * where water meets land.
 * <li>Texture: brush strokes that follow a slowly turning flow (long and level on water), an
 * uneven wash of pigment over large areas, and paper grain.
 * </ol>
 *
 * <p>Every pattern is looked up by world pixel, never by position in the patch, so a patch baked
 * alone matches the same pixels of a whole tile and neighbouring tiles meet without a seam. The
 * cel-shaded light itself is worked out per ray in {@link TileBaker}.
 */
public final class PaintedFinish {
	/** Pixels across a block face on the map. */
	static final int CELL = Projection.PIXELS_PER_UNIT;
	/** Kuwahara reach in faces: the four windows touching a face are squares of {@code RADIUS + 1} faces. */
	private static final int RADIUS = 1;
	/** Traced pixels needed round the patch, in whole faces: a face's windows, then a neighbour's for the lines. */
	public static final int BORDER = 2 * CELL;
	/** How far each face's colour moves to its dab: nearly all, so block textures melt into flat paint. */
	private static final float DAB = 0.9F;

	/** Weight of the line behind a nearer edge, and how much nearer it must be (blocks) to start and to reach full weight. */
	private static final float EDGE_INK = 0.48F;
	private static final float EDGE_NEAR = 1.2F;
	private static final float EDGE_FULL = 3.5F;
	/** Weight of the line between faces of clearly different colour, and the colour distances it fades in over. */
	private static final float COLOUR_INK = 0.24F;
	private static final float COLOUR_FROM = 0.11F;
	private static final float COLOUR_TO = 0.30F;
	/** The coast: an ink line on the land and foam on the water. */
	private static final float COAST_INK = 0.42F;
	private static final float FOAM = 0.38F;
	/** Lines where water steps into a deeper wash, like the rings a painter draws off a coast. */
	private static final float RIPPLE_INK = 0.24F;
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
	 * What the baker traced for a patch and its border, one entry per pixel, row by row.
	 *
	 * @param day colours, ABGR; alpha 0 where unknown
	 * @param night the night colours, or null
	 * @param wet whether the pixel looks through water first
	 * @param waterDepth vertical depth of that water, blocks
	 * @param sun whether the pixel's surface is in direct sunlight
	 * @param albedo the surface's own linear colour before light, three floats a pixel
	 * @param behind how far, in blocks, the pixel lies behind a neighbour across a block edge; 0 for none
	 * @param front how far a neighbour across a block edge lies behind the pixel; 0 for none
	 */
	record Traced(int[] day, int @Nullable [] night, boolean[] wet, float[] waterDepth, boolean[] sun, float[] albedo,
		float[] behind, float[] front) {
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
	 * @param gx0 world pixel column of the traced area's first column, on a face boundary
	 * @param gy0 world pixel row of the traced area's first row, on a face boundary
	 */
	static void apply(int width, int height, int gx0, int gy0, Traced traced, int[] out, int @Nullable [] nightOut) {
		if (Math.floorMod(gx0, CELL) != 0 || Math.floorMod(gy0, CELL) != 0 || width % CELL != 0 || height % CELL != 0) {
			throw new IllegalArgumentException("A painted patch must cover whole block faces");
		}
		int w = width + 2 * BORDER;
		int h = height + 2 * BORDER;
		int n = w * h;
		int[] day = traced.day();
		int[] night = traced.night();
		boolean[] wet = traced.wet();
		float[] r = new float[n], g = new float[n], b = new float[n];
		unpack(day, r, g, b);
		float[] nr = null, ng = null, nb = null;
		if (night != null) {
			nr = new float[n];
			ng = new float[n];
			nb = new float[n];
			unpack(night, nr, ng, nb);
		}

		// 1. Paint: each face's dab, then every land pixel's colour moved by the change in albedo.
		Faces faces = new Faces(w, h, day, wet, traced.albedo());
		float[] dab = faces.dabs();
		float[] albedo = traced.albedo();
		for (int y = CELL; y < h - CELL; y++) {
			for (int x = CELL; x < w - CELL; x++) {
				int i = y * w + x;
				int c = faces.of(x, y);
				if (day[i] >>> 24 == 0 || wet[i] || Float.isNaN(dab[c * 3])) {
					continue;
				}
				float fr = repaint(dab[c * 3], albedo[i * 3]);
				float fg = repaint(dab[c * 3 + 1], albedo[i * 3 + 1]);
				float fb = repaint(dab[c * 3 + 2], albedo[i * 3 + 2]);
				r[i] *= fr;
				g[i] *= fg;
				b[i] *= fb;
				if (nr != null) {
					nr[i] *= fr;
					ng[i] *= fg;
					nb[i] *= fb;
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
				boolean water = wet[i];

				// 2. Ink, 3. light. The strongest line wins rather than adding up.
				float wobble = 0.72F + 0.5F * (0.5F + 0.5F * washAt(gx * 9 + 1_337, gy * 9 - 4_242));
				float edge = smoothstep(EDGE_NEAR, EDGE_FULL, traced.behind()[i]);
				float rim = smoothstep(EDGE_NEAR, EDGE_FULL, traced.front()[i]);
				float colour = 0;
				float coast = 0;
				float ripple = 0;
				float foam = 0;
				int here = faces.of(x + BORDER, y + BORDER);
				int band = water ? washBand(traced.waterDepth()[i]) : 0;
				for (int k = 0; k < 4; k++) {
					int nx = x + BORDER + NEIGHBOUR_X[k];
					int ny = y + BORDER + NEIGHBOUR_Y[k];
					int j = ny * w + nx;
					if (day[j] >>> 24 == 0) {
						continue;
					}
					if (water != wet[j]) {
						if (water) {
							foam = 1;
						} else {
							coast = 1;
						}
					} else if (water) {
						if (washBand(traced.waterDepth()[j]) < band) {
							ripple = 1;
						}
					} else {
						int there = faces.of(nx, ny);
						if (there != here) {
							colour = Math.max(colour, faces.contrast(here, there));
						}
					}
				}
				float ink = Math.max(Math.max(edge * EDGE_INK, colour * COLOUR_INK), coast * COAST_INK) * wobble;
				float rings = ripple * RIPPLE_INK * wobble;
				float light = traced.sun()[i] ? rim * RIM_LIGHT * (1 - edge) : 0;
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

				float cr = r[i], cg = g[i], cb = b[i];
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
					float mr = ink(ink(nr[i], ink, INK[0]), rings, RIPPLE[0]);
					float mg = ink(ink(ng[i], ink, INK[1]), rings, RIPPLE[1]);
					float mb = ink(ink(nb[i], ink, INK[2]), rings, RIPPLE[2]);
					float lift = 1 + froth * 0.6F;
					float nightTone = 1 + stroke + grain;
					nightOut[o] = pack(mr * lift * nightTone, mg * lift * nightTone, mb * lift * nightTone, nightAlpha);
				}
			}
		}
	}

	private static final int[] NEIGHBOUR_X = {-1, 1, 0, 0};
	private static final int[] NEIGHBOUR_Y = {0, 0, -1, 1};

	/**
	 * What an sRGB colour is multiplied by when its face's albedo moves from {@code was} to
	 * {@code dab}, both linear. Bounded, so a near-black albedo can't blow a pixel out.
	 */
	private static float repaint(float dab, float was) {
		float ratio = (float) Math.pow(Math.max(0.25F, Math.min(4F, dab / Math.max(was, 1e-4F))), 1 / 2.4);
		return 1 + (ratio - 1) * DAB;
	}

	/**
	 * The land faces of a traced area: each one's mean albedo, and the Kuwahara dab it takes, the
	 * mean of whichever of the four 2 by 2 face windows around it varies least. Water and unknown
	 * pixels take no part; faces with too few land pixels have no colour (NaN).
	 */
	private static final class Faces {
		private final int across;
		private final int down;
		/** Mean linear albedo per face, three floats each; NaN where the face has too little land. */
		private final float[] mean;
		/** The dab per face once {@link #dabs} has run, three floats each; NaN where there is none. */
		private float @Nullable [] dab;

		Faces(int w, int h, int[] day, boolean[] wet, float[] albedo) {
			this.across = w / CELL;
			this.down = h / CELL;
			int faces = this.across * this.down;
			float[] sum = new float[faces * 3];
			int[] count = new int[faces];
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					int i = y * w + x;
					if (day[i] >>> 24 == 0 || wet[i]) {
						continue;
					}
					int c = (y / CELL) * this.across + x / CELL;
					sum[c * 3] += albedo[i * 3];
					sum[c * 3 + 1] += albedo[i * 3 + 1];
					sum[c * 3 + 2] += albedo[i * 3 + 2];
					count[c]++;
				}
			}
			this.mean = new float[faces * 3];
			for (int c = 0; c < faces; c++) {
				boolean enough = count[c] * 2 >= CELL * CELL;
				for (int k = 0; k < 3; k++) {
					this.mean[c * 3 + k] = enough ? sum[c * 3 + k] / count[c] : Float.NaN;
				}
			}
		}

		/** The face holding pixel (x, y) of the traced area. */
		int of(int x, int y) {
			return (y / CELL) * this.across + x / CELL;
		}

		/** Each face's dab, three floats each; NaN at the outer ring of faces and where the face has no colour. */
		float[] dabs() {
			float[] dab = new float[this.mean.length];
			java.util.Arrays.fill(dab, Float.NaN);
			for (int cy = RADIUS; cy < this.down - RADIUS; cy++) {
				for (int cx = RADIUS; cx < this.across - RADIUS; cx++) {
					int c = cy * this.across + cx;
					if (Float.isNaN(this.mean[c * 3])) {
						continue;
					}
					float best = Float.MAX_VALUE;
					for (int quadrant = 0; quadrant < 4; quadrant++) {
						int x0 = (quadrant & 1) == 0 ? cx - RADIUS : cx;
						int y0 = (quadrant & 2) == 0 ? cy - RADIUS : cy;
						int known = 0;
						float sum = 0;
						float squares = 0;
						float r = 0, g = 0, b = 0;
						for (int yy = y0; yy <= y0 + RADIUS; yy++) {
							for (int xx = x0; xx <= x0 + RADIUS; xx++) {
								int f = yy * this.across + xx;
								if (Float.isNaN(this.mean[f * 3])) {
									continue;
								}
								float l = luma(this.mean[f * 3], this.mean[f * 3 + 1], this.mean[f * 3 + 2]);
								sum += l;
								squares += l * l;
								r += this.mean[f * 3];
								g += this.mean[f * 3 + 1];
								b += this.mean[f * 3 + 2];
								known++;
							}
						}
						// A window needs most of its faces, or a lone face at a coast would take the water's side.
						if (known < (RADIUS + 1) * (RADIUS + 1) - 1) {
							continue;
						}
						float variance = squares / known - (sum / known) * (sum / known);
						if (variance < best) {
							best = variance;
							dab[c * 3] = r / known;
							dab[c * 3 + 1] = g / known;
							dab[c * 3 + 2] = b / known;
						}
					}
					if (best == Float.MAX_VALUE) {
						dab[c * 3] = this.mean[c * 3];
						dab[c * 3 + 1] = this.mean[c * 3 + 1];
						dab[c * 3 + 2] = this.mean[c * 3 + 2];
					}
				}
			}
			this.dab = dab;
			return dab;
		}

		/**
		 * How strongly to ink face {@code here} against neighbouring face {@code there}, 0..1: by how
		 * different their painted colours are, and only on the darker side, so the line is one pixel.
		 */
		float contrast(int here, int there) {
			float[] dab = this.dab;
			if (dab == null || Float.isNaN(dab[here * 3]) || Float.isNaN(dab[there * 3])) {
				return 0;
			}
			// Compared as sRGB, roughly, so the distance follows what the eye sees.
			float hr = (float) Math.sqrt(dab[here * 3]), hg = (float) Math.sqrt(dab[here * 3 + 1]), hb = (float) Math.sqrt(dab[here * 3 + 2]);
			float tr = (float) Math.sqrt(dab[there * 3]), tg = (float) Math.sqrt(dab[there * 3 + 1]), tb = (float) Math.sqrt(dab[there * 3 + 2]);
			if (luma(tr, tg, tb) <= luma(hr, hg, hb)) {
				return 0;
			}
			float dr = tr - hr, dg = tg - hg, db = tb - hb;
			return smoothstep(COLOUR_FROM, COLOUR_TO, (float) Math.sqrt(dr * dr + dg * dg + db * db));
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
