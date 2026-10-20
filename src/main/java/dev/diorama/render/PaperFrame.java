package dev.diorama.render;

import dev.diorama.render.gpu.ClippedCanvas;
import dev.diorama.render.gpu.MapCanvas;
import dev.diorama.render.gpu.MapTexture;
import dev.diorama.render.gpu.Sampling;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.jspecify.annotations.Nullable;

/**
 * The minimap framed as a vanilla map held in hand: the paper of {@code map_background.png} from
 * the resource packs, sliced in nine so its torn edge and corners keep their pixels, at whole GUI
 * pixels per texel. The edges repeat rather than stretch, so every texel stays square.
 */
public final class PaperFrame {
	private static final String TEXTURE = "textures/map/map_background.png";
	private static final int SIZE = 64;
	/** Texels of torn edge and paper around the map, on each side. */
	private static final int EDGE = 5;
	/** GUI pixels per texel. */
	public static final int SCALE = 2;
	/** GUI pixels of the torn outline at the outside of {@link #BORDER}; writing stays inside it. */
	public static final int OUTLINE = 4;
	/** How far the paper reaches out from the map, in GUI pixels. */
	public static final int BORDER = EDGE * SCALE;
	/** Ink for writing on the paper, from the texture's own outline. */
	public static final int INK = 0xFF4A3A26;
	private static @Nullable MapTexture paper;
	/** The paper eating into the map's edge, for the size it was made for. */
	private static @Nullable MapTexture edge;
	/** The cut-out style's sheet over the map: its torn outline as it is, its paper as a multiply. */
	private static @Nullable MapTexture sheet;
	/** How far the paper darkens the terrain toward a true multiply, of 1. */
	private static final double MULTIPLY = 0.85;
	private static int edgeSize = -1;
	/** The cut-out style: the texture's opaque texels and the last shape built from them. */
	private static final boolean[] opaque = new boolean[SIZE * SIZE];
	private static @Nullable List<ClippedCanvas.Band> bands;
	private static int bandsX, bandsY, bandsSize;
	/** Paper and outline colours read from the texture, ABGR, so resource packs carry over. */
	private static int paperColour = 0xFF96BED6, inkColour = 0xFF6C8799;
	/** Texels of the edge texture; maps up to 256 GUI pixels across. */
	private static final int EDGE_TEXTURE = 256;
	/** How deep the paper reaches into the map, in GUI pixels: at least, and at most. */
	private static final int SHALLOW = 1, DEEP = 3;

	private PaperFrame() {
	}

	/** The paper around a map of {@code size} at (x, y). */
	public static void draw(GuiGraphicsExtractor graphics, int x, int y, int size) {
		nineSlice(MapCanvas.of(graphics), texture(), x, y, size);
	}

	/**
	 * The cut-out style's shape: the texture laid over the whole map of {@code size} at (x, y), one
	 * texel to {@code size / 64} GUI pixels, and the rows and columns of its opaque texels, for
	 * {@link ClippedCanvas#within}. The map is drawn only where the texture is, so it takes the
	 * sheet's torn pixels as its own edge.
	 */
	public static List<ClippedCanvas.Band> silhouette(int x, int y, int size) {
		texture();
		if (bands == null || bandsX != x || bandsY != y || bandsSize != size) {
			bands = bands(opaque, x, y, size);
			bandsX = x;
			bandsY = y;
			bandsSize = size;
		}
		return bands;
	}

	/**
	 * Over a map cut to {@link #silhouette}: the sheet's torn outline drawn as it is, so the edge
	 * keeps its brown pixels, and its paper as a multiply, so the terrain takes on its tone and
	 * grain. Built from whatever texture the resource packs give.
	 */
	public static void cutout(GuiGraphicsExtractor graphics, int x, int y, int size) {
		texture();
		MapCanvas.of(graphics).rect(sheet, Sampling.TILE_CRISP, x, y, x + size, y + size, 0, 0, 1, 1, -1);
	}

	/**
	 * The sheet as plain alpha blending, ABGR, so it draws the same on every version. The outline
	 * (opaque texels on the silhouette's edge, darker than halfway from the paper to the darkest
	 * texel) stays opaque. Paper texels stand in for a multiply by the texel T: the terrain under
	 * alpha {@code a} of colour {@code T * k} becomes {@code dst * (1 - a) + T * k * a}, and with
	 * {@code a} the texel's darkness that is {@code dst} times its lightness, warmed by its hue.
	 * Pure, for tests.
	 */
	static int[] sheetPixels(int[] pixels, boolean[] opaque) {
		int darkest = 255, paperLuma = luma(pixels[(SIZE / 2) * SIZE + SIZE / 2]);
		for (int i = 0; i < pixels.length; i++) {
			if (opaque[i]) darkest = Math.min(darkest, luma(pixels[i]));
		}
		int middle = (paperLuma + darkest) / 2;
		int[] out = new int[pixels.length];
		for (int ty = 0; ty < SIZE; ty++) {
			for (int tx = 0; tx < SIZE; tx++) {
				int i = ty * SIZE + tx;
				if (!opaque[i]) continue;
				int p = pixels[i];
				int l = luma(p);
				boolean rim = false;
				for (int d = 0; d < 4 && !rim; d++) {
					int nx = tx + (d == 0 ? -1 : d == 1 ? 1 : 0), ny = ty + (d == 2 ? -1 : d == 3 ? 1 : 0);
					rim = nx < 0 || ny < 0 || nx >= SIZE || ny >= SIZE || !opaque[ny * SIZE + nx]
						|| nx > 0 && nx < SIZE - 1 && ny > 0 && ny < SIZE - 1 && near(opaque, nx, ny);
				}
				if (rim && l < middle) {
					out[i] = p | 0xFF000000;
					continue;
				}
				int alpha = (int) Math.round((255 - l) * MULTIPLY);
				// The texel's hue at a third of its lightness: dark enough to only darken.
				int r = (p & 255) / 3, g = (p >> 8 & 255) / 3, b = (p >> 16 & 255) / 3;
				out[i] = alpha << 24 | b << 16 | g << 8 | r;
			}
		}
		return out;
	}

	/** Whether a texel next to (x, y) is transparent: the outline is two texels deep in places. */
	private static boolean near(boolean[] opaque, int x, int y) {
		return !opaque[y * SIZE + x - 1] || !opaque[y * SIZE + x + 1] || !opaque[(y - 1) * SIZE + x] || !opaque[(y + 1) * SIZE + x];
	}

	/** Lightness of an ABGR pixel, 0 to 255. */
	static int luma(int abgr) {
		return (299 * (abgr & 255) + 587 * (abgr >> 8 & 255) + 114 * (abgr >> 16 & 255)) / 1000;
	}

	/** Each texel row's spans of opaque texels, rows alike gathered into one band. Pure, for tests. */
	static List<ClippedCanvas.Band> bands(boolean[] opaque, int x, int y, int size) {
		float s = size / (float) SIZE;
		List<ClippedCanvas.Band> out = new ArrayList<>();
		List<int[]> before = List.of();
		int start = 0;
		for (int ty = 0; ty <= SIZE; ty++) {
			List<int[]> runs = new ArrayList<>();
			for (int tx = 0; ty < SIZE && tx < SIZE; tx++) {
				if (opaque[ty * SIZE + tx]) {
					int from = tx;
					while (tx + 1 < SIZE && opaque[ty * SIZE + tx + 1]) tx++;
					runs.add(new int[] {from, tx + 1});
				}
			}
			if (ty == SIZE || !same(runs, before)) {
				for (int[] run : before) {
					out.add(new ClippedCanvas.Band(y + start * s, y + ty * s, x + run[0] * s, x + run[1] * s));
				}
				start = ty;
				before = runs;
			}
		}
		return out;
	}

	private static boolean same(List<int[]> a, List<int[]> b) {
		if (a.size() != b.size()) return false;
		for (int i = 0; i < a.size(); i++) {
			if (!java.util.Arrays.equals(a.get(i), b.get(i))) return false;
		}
		return true;
	}

	private static void nineSlice(MapCanvas canvas, MapTexture texture, int x, int y, int size) {
		int x0 = x - BORDER, y0 = y - BORDER, x1 = x + size + BORDER, y1 = y + size + BORDER;
		int inner = SIZE - 2 * EDGE;
		// The middle, under the map, where nothing has been drawn yet.
		part(canvas, texture, x, y, x + size, y + size, EDGE, EDGE, SIZE - EDGE, SIZE - EDGE);
		// Corners.
		part(canvas, texture, x0, y0, x, y, 0, 0, EDGE, EDGE);
		part(canvas, texture, x + size, y0, x1, y, SIZE - EDGE, 0, SIZE, EDGE);
		part(canvas, texture, x0, y + size, x, y1, 0, SIZE - EDGE, EDGE, SIZE);
		part(canvas, texture, x + size, y + size, x1, y1, SIZE - EDGE, SIZE - EDGE, SIZE, SIZE);
		// Edges, the texture's own edge repeated along each side.
		for (int at = 0; at < size; at += inner * SCALE) {
			int length = Math.min(inner * SCALE, size - at);
			float u1 = EDGE + length / (float) SCALE;
			part(canvas, texture, x + at, y0, x + at + length, y, EDGE, 0, u1, EDGE);
			part(canvas, texture, x + at, y + size, x + at + length, y1, EDGE, SIZE - EDGE, u1, SIZE);
			part(canvas, texture, x0, y + at, x, y + at + length, 0, EDGE, EDGE, u1);
			part(canvas, texture, x + size, y + at, x1, y + at + length, SIZE - EDGE, EDGE, SIZE, u1);
		}
	}

	/**
	 * The paper's edge over the map's own, so the map doesn't end in a straight line: a band where
	 * the paper reaches in by a ragged amount, with a faint ink line along it, like the explored
	 * frontier of the fog. Pixel art at one GUI pixel per texel, like the fog's, so it takes only a
	 * sliver of the map; fixed to the paper, not the terrain. Draw after the map and before the
	 * markers.
	 */
	public static void edge(GuiGraphicsExtractor graphics, int x, int y, int size) {
		texture();
		int texels = size;
		if (edge == null || edgeSize != size) {
			if (edge == null) {
				edge = MapTexture.create("diorama map paper edge", EDGE_TEXTURE, 1);
			}
			edge.write(0, 0, 0, EDGE_TEXTURE, edgePixels(Math.min(texels, EDGE_TEXTURE), paperColour, inkColour));
			edgeSize = size;
		}
		float uv = (float) Math.min(texels, EDGE_TEXTURE) / EDGE_TEXTURE;
		MapCanvas.of(graphics).rect(edge, Sampling.TILE_CRISP, x, y, x + size, y + size, 0, 0, uv, uv, -1);
	}

	/**
	 * The edge mask, {@link #EDGE_TEXTURE} square, ABGR: paper where it covers the map, ink at 30%
	 * on the map's texels touching it, clear elsewhere. Each side's depth follows its own noise
	 * along the side, and the corners are rounded off, so no two sides tear alike. Pure, for tests.
	 */
	static int[] edgePixels(int texels, int paper, int ink) {
		int[] out = new int[EDGE_TEXTURE * EDGE_TEXTURE];
		boolean[] covered = new boolean[texels * texels];
		int[][] depth = new int[4][texels];
		for (int side = 0; side < 4; side++) {
			for (int t = 0; t < texels; t++) {
				// The noise keeps near its middle; spread it so the tear uses the whole depth.
				double spread = Math.clamp((ragged(t, side) - 0.25) / 0.5, 0, 1);
				depth[side][t] = SHALLOW + (int) Math.round(spread * (DEEP - SHALLOW));
			}
		}
		int corner = DEEP + 2;
		for (int ty = 0; ty < texels; ty++) {
			for (int tx = 0; tx < texels; tx++) {
				int right = texels - 1 - tx, bottom = texels - 1 - ty;
				boolean in = tx < depth[0][ty] || right < depth[1][ty] || ty < depth[2][tx] || bottom < depth[3][tx];
				// Round the corners: outside a circle of the corner's radius counts as paper.
				int cx = Math.min(tx, right), cy = Math.min(ty, bottom);
				if (cx < corner && cy < corner) {
					double dx = corner - cx - 0.5, dy = corner - cy - 0.5;
					in |= dx * dx + dy * dy > corner * corner;
				}
				covered[ty * texels + tx] = in;
			}
		}
		int line = (ink & 0x00FFFFFF) | 0x4C000000;
		for (int ty = 0; ty < texels; ty++) {
			for (int tx = 0; tx < texels; tx++) {
				int i = ty * texels + tx;
				int o = ty * EDGE_TEXTURE + tx;
				if (covered[i]) {
					out[o] = paper;
				} else if (tx > 0 && covered[i - 1] || tx < texels - 1 && covered[i + 1]
					|| ty > 0 && covered[i - texels] || ty < texels - 1 && covered[i + texels]) {
					out[o] = line;
				}
			}
		}
		return out;
	}

	/** Smooth value noise in [0, 1] along one side, with a finer second octave for small tears. */
	static double ragged(int t, int side) {
		return 0.7 * valueNoise(t / 14.0, side * 7919 + 1) + 0.3 * valueNoise(t / 4.0, side * 7919 + 2);
	}

	private static double valueNoise(double at, int seed) {
		int i = (int) Math.floor(at);
		double f = at - i;
		f = f * f * (3 - 2 * f);
		return hash(i, seed) * (1 - f) + hash(i + 1, seed) * f;
	}

	private static double hash(int i, int seed) {
		int h = i * 0x27d4eb2d ^ seed * 0x165667b1;
		h ^= h >>> 15;
		h *= 0x85ebca6b;
		h ^= h >>> 13;
		return (h & 0xFFFF) / 65535.0;
	}

	private static void part(MapCanvas canvas, MapTexture texture, float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1) {
		canvas.rect(texture, Sampling.TILE_CRISP, x0, y0, x1, y1, u0 / SIZE, v0 / SIZE, u1 / SIZE, v1 / SIZE, -1);
	}

	private static MapTexture texture() {
		if (paper == null) {
			int[] pixels = new int[SIZE * SIZE];
			try (var input = net.minecraft.client.Minecraft.getInstance().getResourceManager()
				.open(net.minecraft.resources.Identifier.withDefaultNamespace(TEXTURE));
				var image = com.mojang.blaze3d.platform.NativeImage.read(input)) {
				int size = Math.min(image.getWidth(), image.getHeight());
				for (int y = 0; y < SIZE; y++) {
					for (int x = 0; x < SIZE; x++) {
						// A larger resource-pack texture is read at its nearest pixels.
						int sx = x * size / SIZE, sy = y * size / SIZE;
						//? if >=1.21.2 {
						int argb = image.getPixel(sx, sy);
						pixels[y * SIZE + x] = (argb & 0xff00ff00) | (argb & 255) << 16 | (argb >> 16 & 255);
						//?} else {
						/*pixels[y * SIZE + x] = image.getPixelRGBA(sx, sy);
						*///?}
					}
				}
			} catch (Exception error) {
				// Plain paper with a dark edge, like the vanilla one.
				for (int i = 0; i < pixels.length; i++) {
					int x = i % SIZE, y = i / SIZE;
					boolean edge = x == 0 || y == 0 || x == SIZE - 1 || y == SIZE - 1;
					pixels[i] = edge ? 0xFF6C8799 : 0xFF96BED6;
				}
				dev.diorama.Diorama.LOGGER.warn("Couldn't load the map background", error);
			}
			paperColour = pixels[(SIZE / 2) * SIZE + SIZE / 2] | 0xFF000000;
			inkColour = pixels[SIZE * (SIZE / 2) + 1] | 0xFF000000;
			edgeSize = -1;
			paper = MapTexture.create("diorama map paper", SIZE, 1);
			paper.write(0, 0, 0, SIZE, pixels);
			// For the cut-out style: the silhouette, and the sheet laid over the map.
			for (int i = 0; i < pixels.length; i++) {
				opaque[i] = pixels[i] >>> 24 >= 128;
			}
			bands = null;
			sheet = MapTexture.create("diorama map sheet", SIZE, 1);
			sheet.write(0, 0, 0, SIZE, sheetPixels(pixels, opaque));
		}
		return paper;
	}

	/** Drops the texture so the next draw reads it again from the current resource packs. */
	public static void reload() {
		if (paper != null) {
			paper.close();
			paper = null;
		}
		if (edge != null) {
			edge.close();
			edge = null;
		}
		if (sheet != null) {
			sheet.close();
			sheet = null;
		}
		bands = null;
	}
}
