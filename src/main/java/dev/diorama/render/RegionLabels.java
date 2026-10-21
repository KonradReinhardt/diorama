package dev.diorama.render;

import dev.diorama.Projection;
import dev.diorama.region.MapRegion;
import dev.diorama.region.RegionStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Regions' names on the world map, written where each region's label point is (see
 * {@link MapRegion#anchor}) once the region is wide enough on screen to hold its name, fading in
 * as it gets there. The lettering follows each region's style: the game's font with a shadow on the plain and
 * outlined maps, in the region's dye; in ink with a shadow of paper on the painted ones; in spaced
 * capitals of old ink on the antique maps, the way old maps letter provinces. Bigger regions are
 * named first, and a name that would sit on another is left out. On whole GUI pixels.
 */
public final class RegionLabels {
	/** Paint and old ink, as regions.fsh draws the lines. */
	private static final int PAINT_INK = 0xFF4C3021, OLD_INK = 0xFF3C2513;
	private static final int PAPER = 0xFFF2DFB8;
	/** Pixels of region on each side of its name before the name shows whole. */
	private static final int ROOM = 6;

	private RegionLabels() {
	}

	/** Writes the names; regions whose style is {@link RegionStyle#AUTO} take {@code mapStyle}. */
	public static void draw(GuiGraphicsExtractor graphics, MapViewport.View view, List<MapRegion> regions, RegionStyle mapStyle) {
		Font font = Minecraft.getInstance().font;
		List<MapRegion> byArea = new ArrayList<>();
		for (MapRegion region : regions) {
			if (region.showLabel && !region.name.isBlank()) {
				byArea.add(region);
			}
		}
		byArea.sort(Comparator.comparingDouble(MapRegion::area).reversed());
		List<int[]> placed = new ArrayList<>();
		for (MapRegion region : byArea) {
			RegionStyle look = region.style.or(mapStyle);
			boolean capitals = look == RegionStyle.ANTIQUE || look == RegionStyle.ANTIQUE_COLOR;
			String text = capitals ? region.name.toUpperCase(Locale.ROOT) : region.name;
			int width = capitals ? spacedWidth(font, text) : font.width(text);
			// How wide the region is on screen: the span of its bounds across the map.
			double[] b = region.bounds();
			double[] us = {Projection.u(b[0], b[1]), Projection.u(b[2], b[1]), Projection.u(b[0], b[3]), Projection.u(b[2], b[3])};
			double span = (max(us) - min(us)) * view.zoom();
			float shown = (float) Math.max(0, Math.min(1, (span - width * 0.6) / (width * 0.4 + 2 * ROOM)));
			if (shown <= 0.05F) {
				continue;
			}
			double[] at = region.anchor();
			int x = Math.round(view.screenX(Projection.u(at[0], at[1]))) - width / 2;
			int y = Math.round(view.screenY(Projection.worldV(at[0], at[2], at[1]))) - 4;
			int[] box = {x - 2, y - 2, x + width + 2, y + 10};
			if (box[2] < view.x() || box[0] > view.x() + view.width() || box[3] < view.y() || box[1] > view.y() + view.height() || overlaps(placed, box)) {
				continue;
			}
			placed.add(box);
			switch (look) {
				case AUTO, PLAIN, OUTLINED -> graphics.text(font, text, x, y, alpha(readable(region.dye.argb()), shown), true);
				case PAINTED, PAINTED_NO_INK -> {
					graphics.text(font, text, x + 1, y + 1, alpha(PAPER, shown * 0.6F), false);
					graphics.text(font, text, x, y, alpha(PAINT_INK, shown), false);
				}
				case ANTIQUE, ANTIQUE_COLOR -> {
					spaced(graphics, font, text, x + 1, y + 1, alpha(PAPER, shown * 0.6F));
					spaced(graphics, font, text, x, y, alpha(OLD_INK, shown));
				}
			}
		}
	}

	/** Capitals a pixel further apart than the font sets them. */
	private static void spaced(GuiGraphicsExtractor graphics, Font font, String text, int x, int y, int argb) {
		for (int i = 0; i < text.length(); ) {
			int cp = text.codePointAt(i);
			String ch = new String(Character.toChars(cp));
			graphics.text(font, ch, x, y, argb, false);
			x += font.width(ch) + 1;
			i += Character.charCount(cp);
		}
	}

	private static int spacedWidth(Font font, String text) {
		int w = 0;
		for (int i = 0; i < text.length(); ) {
			int cp = text.codePointAt(i);
			w += font.width(new String(Character.toChars(cp))) + 1;
			i += Character.charCount(cp);
		}
		return Math.max(0, w - 1);
	}

	/** A dye bright enough to read over the map: the dark ones are lightened. */
	static int readable(int argb) {
		int r = (argb >> 16) & 255, g = (argb >> 8) & 255, b = argb & 255;
		if (0.299 * r + 0.587 * g + 0.114 * b >= 70) {
			return argb;
		}
		return 0xFF000000 | (r + (255 - r) * 2 / 5) << 16 | (g + (255 - g) * 2 / 5) << 8 | (b + (255 - b) * 2 / 5);
	}

	private static boolean overlaps(List<int[]> placed, int[] box) {
		for (int[] p : placed) {
			if (box[0] < p[2] && p[0] < box[2] && box[1] < p[3] && p[1] < box[3]) {
				return true;
			}
		}
		return false;
	}

	private static int alpha(int argb, float shown) {
		return Math.round((argb >>> 24) * Math.max(0, Math.min(1, shown))) << 24 | (argb & 0xFFFFFF);
	}

	private static double min(double[] values) {
		double m = Double.POSITIVE_INFINITY;
		for (double v : values) m = Math.min(m, v);
		return m;
	}

	private static double max(double[] values) {
		double m = Double.NEGATIVE_INFINITY;
		for (double v : values) m = Math.max(m, v);
		return m;
	}
}
