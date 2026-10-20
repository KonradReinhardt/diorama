package dev.diorama.render;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds a waypoint's banner sprite in pixel art, on the CPU and without game classes. The cloth is
 * composed like vanilla's banner renderer, base then layers, each a mask tinted with its dye; it
 * is then reduced to the sprite's size by averaging and snapped back to the design's own colours,
 * so a small banner stays crisp instead of blurring. A hand-drawn frame goes around it: the pole
 * and crossbar in wood with a dark ink outline, a finial, a shade on the cloth away from the
 * map's light, and a dotted shadow on the ground. Pixels are ARGB.
 */
public final class BannerCompositor {
	/** The front of the cloth in vanilla's 64x64 banner texture: 20x40 texels at (1, 1). */
	public static final int TEXTURE = 64, FRONT_X = 1, FRONT_Y = 1, FRONT_W = 20, FRONT_H = 40;

	/** How many frames the waving cloth loops through. A further frame holds it still. */
	public static final int WAVE_FRAMES = 4;
	public static final int STILL = WAVE_FRAMES;

	static final int INK = 0xFF1E140C;
	static final int WOOD = 0xFF8C6A3C;
	static final int WOOD_LIGHT = 0xFFB08A52;
	static final int WOOD_DARK = 0xFF5E4426;
	static final int SHADOW = 0x60000000;
	static final int FOOT_SHADOW = 0x90000000;

	/** The two sizes drawn: the fullscreen map's and the smaller one for the minimap and HUD. */
	public enum Size {
		MAP(10, 20, 3),
		SMALL(6, 12, 2);

		/** The cloth, in sprite pixels. */
		public final int clothW, clothH;
		/** How much pole shows below the cloth. */
		final int foot;

		Size(int clothW, int clothH, int foot) {
			this.clothW = clothW;
			this.clothH = clothH;
			this.foot = foot;
		}

		/** The whole sprite: crossbar overhang and cloth outline on each side. */
		public int width() {
			return this.clothW + 4;
		}

		public int height() {
			return CLOTH_TOP + this.clothH + 1 + this.foot + 1;
		}

		/** Sprite row of the pole's foot: the top of the ground shadow, where the waypoint's block is. */
		public int footY() {
			return this.height() - 1;
		}
	}

	/** Finial (2 rows) and crossbar (3 rows) above the cloth. */
	static final int CLOTH_TOP = 5;

	/**
	 * A banner texture's cloth front: ARGB pixels of any size (a resource pack may draw banners at
	 * 32x or more), its colour multiplied by the dye where it is opaque.
	 */
	public record Mask(int width, int height, int[] argb) {
		public Mask {
			if (argb.length != width * height) {
				throw new IllegalArgumentException("Mask size doesn't match its pixels");
			}
		}

		/** The cloth's front cut from a whole banner texture of {@code size} x {@code size}. */
		public static Mask front(int[] argb, int size) {
			int scale = Math.max(1, size / TEXTURE);
			int w = FRONT_W * scale, h = FRONT_H * scale;
			int[] out = new int[w * h];
			for (int y = 0; y < h; y++) {
				System.arraycopy(argb, (FRONT_Y * scale + y) * size + FRONT_X * scale, out, y * w, w);
			}
			return new Mask(w, h, out);
		}

		/** The colour at a point given in this mask's fraction of the cloth, nearest pixel. */
		int at(double fx, double fy) {
			int x = Math.min(this.width - 1, (int) (fx * this.width));
			int y = Math.min(this.height - 1, (int) (fy * this.height));
			return this.argb[y * this.width + x];
		}
	}

	/** One layer to compose: a mask and the dye it's tinted with, 0xRRGGBB. */
	public record Tinted(Mask mask, int rgb) {
	}

	private BannerCompositor() {
	}

	/**
	 * The cloth at full resolution, the size of the base mask: the base, then each layer in order,
	 * blended by the layer's alpha. Layers of another resolution are sampled to fit.
	 */
	public static int[] compose(Tinted base, List<Tinted> layers) {
		Mask m = base.mask();
		int w = m.width(), h = m.height();
		int[] out = new int[w * h];
		for (int i = 0; i < out.length; i++) {
			int px = m.argb()[i];
			out[i] = 0xFF000000 | tint(px, base.rgb());
		}
		for (Tinted layer : layers) {
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					int px = layer.mask().at((x + 0.5) / w, (y + 0.5) / h);
					int alpha = px >>> 24;
					if (alpha > 0) {
						out[y * w + x] = blend(out[y * w + x], tint(px, layer.rgb()), alpha);
					}
				}
			}
		}
		return out;
	}

	/**
	 * The colours a reduced cloth snaps to: each dye as its mask tints it, the mask's mean colour
	 * where it is opaque. Masks are near white, so this is near the dye itself.
	 */
	public static int[] palette(Tinted base, List<Tinted> layers) {
		List<Integer> colours = new ArrayList<>();
		colours.add(meanTint(base));
		for (Tinted layer : layers) {
			int c = meanTint(layer);
			if (!colours.contains(c)) {
				colours.add(c);
			}
		}
		return colours.stream().mapToInt(Integer::intValue).toArray();
	}

	/**
	 * Shrinks a full cloth to {@code w} x {@code h}: each target pixel averages the source area it
	 * covers, fractional edges weighted, then takes the nearest palette colour.
	 */
	public static int[] reduce(int[] full, int fw, int fh, int w, int h, int[] palette) {
		int[] out = new int[w * h];
		double sx = (double) fw / w, sy = (double) fh / h;
		for (int y = 0; y < h; y++) {
			double y0 = y * sy, y1 = y0 + sy;
			for (int x = 0; x < w; x++) {
				double x0 = x * sx, x1 = x0 + sx;
				double r = 0, g = 0, b = 0, total = 0;
				for (int iy = (int) y0; iy < Math.min(fh, Math.ceil(y1)); iy++) {
					double wy = Math.min(y1, iy + 1) - Math.max(y0, iy);
					for (int ix = (int) x0; ix < Math.min(fw, Math.ceil(x1)); ix++) {
						double weight = wy * (Math.min(x1, ix + 1) - Math.max(x0, ix));
						int px = full[iy * fw + ix];
						r += weight * ((px >> 16) & 255);
						g += weight * ((px >> 8) & 255);
						b += weight * (px & 255);
						total += weight;
					}
				}
				out[y * w + x] = nearest(palette, (int) Math.round(r / total), (int) Math.round(g / total), (int) Math.round(b / total));
			}
		}
		return out;
	}

	/**
	 * One frame of the whole sprite, {@code size.width()} x {@code size.height()}. In the wave
	 * frames, columns of the cloth's lower half hang a pixel lower in a wave that runs across the
	 * cloth with the wind; {@link #STILL} hangs it flat.
	 */
	public static int[] frame(int[] cloth, Size size, int frame) {
		int w = size.width(), h = size.height();
		int cw = size.clothW, ch = size.clothH;
		int[] out = new int[w * h];
		int c = w / 2;
		// The pole, behind everything, from the finial to the foot.
		for (int y = 1; y < size.footY(); y++) {
			put(out, w, c - 2, y, INK);
			put(out, w, c - 1, y, WOOD_LIGHT);
			put(out, w, c, y, WOOD);
			put(out, w, c + 1, y, INK);
		}
		put(out, w, c - 1, 0, INK);
		put(out, w, c, 0, INK);
		// The crossbar.
		for (int x = 0; x < w; x++) {
			put(out, w, x, 2, INK);
			put(out, w, x, 3, x == 0 || x == w - 1 ? INK : x == w - 2 ? WOOD_DARK : WOOD);
			put(out, w, x, 4, INK);
		}
		// The cloth, outlined, its lower half waving.
		int half = ch / 2;
		for (int cx = 0; cx < cw; cx++) {
			int drop = frame >= STILL ? 0 : wave(cx, frame);
			int x = cx + 2;
			for (int y = 0; y < ch + drop; y++) {
				int source = y < half ? y : y - drop;
				int px = cloth[source * cw + cx];
				// Light comes from the map's upper left: the crossbar shades the top row and the
				// far edge sits in shade; a dropped column is a fold turned from the light.
				double shade = cx == cw - 1 ? 0.74 : 1;
				if (y == 0) shade *= 0.82;
				if (drop > 0 && y >= half) shade *= 0.88;
				put(out, w, x, CLOTH_TOP + y, darken(px, shade));
			}
			put(out, w, x, CLOTH_TOP + ch + drop, INK);
		}
		for (int y = CLOTH_TOP; y < CLOTH_TOP + ch + 1; y++) {
			put(out, w, 1, y, INK);
			put(out, w, w - 2, y, INK);
		}
		// A shadow on the ground, dotted away from the light.
		int ground = size.footY();
		for (int x = c - 2; x <= c + 1; x++) {
			put(out, w, x, ground, FOOT_SHADOW);
		}
		for (int x = c + 3; x < w - 1; x += 2) {
			put(out, w, x, ground, SHADOW);
		}
		return out;
	}

	/** How far a cloth column hangs in a wave frame: two columns down, two up, moving one a frame. */
	static int wave(int column, int frame) {
		return Math.floorMod(column - frame, WAVE_FRAMES) < 2 ? 1 : 0;
	}

	/** The fog's sepia ramp from dark umber to parchment, by lightness, for the antique map. */
	public static int sepia(int argb) {
		double l = (0.299 * ((argb >> 16) & 255) + 0.587 * ((argb >> 8) & 255) + 0.114 * (argb & 255)) / 255;
		double[] a, b;
		double t;
		if (l < 0.5) {
			a = new double[] {0.20, 0.12, 0.06};
			b = new double[] {0.58, 0.42, 0.25};
			t = l * 2;
		} else {
			a = new double[] {0.58, 0.42, 0.25};
			b = new double[] {0.95, 0.875, 0.71};
			t = (l - 0.5) * 2;
		}
		int r = (int) Math.round((a[0] + (b[0] - a[0]) * t) * 255);
		int g = (int) Math.round((a[1] + (b[1] - a[1]) * t) * 255);
		int bl = (int) Math.round((a[2] + (b[2] - a[2]) * t) * 255);
		return (argb & 0xFF000000) | r << 16 | g << 8 | bl;
	}

	private static void put(int[] out, int w, int x, int y, int argb) {
		if (x >= 0 && x < w && y >= 0 && y * w + x < out.length) {
			out[y * w + x] = argb;
		}
	}

	/** A mask pixel's colour multiplied by a dye, alpha dropped. */
	static int tint(int px, int rgb) {
		int r = ((px >> 16) & 255) * ((rgb >> 16) & 255) / 255;
		int g = ((px >> 8) & 255) * ((rgb >> 8) & 255) / 255;
		int b = (px & 255) * (rgb & 255) / 255;
		return r << 16 | g << 8 | b;
	}

	private static int blend(int under, int over, int alpha) {
		int r = mix((under >> 16) & 255, (over >> 16) & 255, alpha);
		int g = mix((under >> 8) & 255, (over >> 8) & 255, alpha);
		int b = mix(under & 255, over & 255, alpha);
		return 0xFF000000 | r << 16 | g << 8 | b;
	}

	private static int mix(int a, int b, int alpha) {
		return (a * (255 - alpha) + b * alpha + 127) / 255;
	}

	private static int meanTint(Tinted layer) {
		long r = 0, g = 0, b = 0, n = 0;
		for (int px : layer.mask().argb()) {
			if ((px >>> 24) >= 128) {
				r += (px >> 16) & 255;
				g += (px >> 8) & 255;
				b += px & 255;
				n++;
			}
		}
		int mean = n == 0 ? 0xFFFFFF : (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
		return 0xFF000000 | tint(mean, layer.rgb());
	}

	private static int nearest(int[] palette, int r, int g, int b) {
		int best = palette[0];
		long bestDistance = Long.MAX_VALUE;
		for (int c : palette) {
			long dr = r - ((c >> 16) & 255), dg = g - ((c >> 8) & 255), db = b - (c & 255);
			long distance = 3 * dr * dr + 4 * dg * dg + 2 * db * db;
			if (distance < bestDistance) {
				best = c;
				bestDistance = distance;
			}
		}
		return best;
	}

	private static int darken(int argb, double shade) {
		if (shade >= 1) {
			return argb;
		}
		int r = (int) (((argb >> 16) & 255) * shade);
		int g = (int) (((argb >> 8) & 255) * shade);
		int b = (int) ((argb & 255) * shade);
		return (argb & 0xFF000000) | r << 16 | g << 8 | b;
	}
}
