package dev.diorama.ui;

import dev.diorama.Diorama;
import dev.diorama.render.gpu.MapCanvas;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * The world map book's art, from one sheet, {@code assets/diorama/textures/gui/book.png}, drawn by
 * {@code docs/design/book-sprites.py}; resource packs replace that sheet. One texel is one GUI
 * pixel. This is also the only place the book touches GUI calls that differ between versions:
 * drawing an item, and starting a layer above what was drawn before.
 */
public final class BookSprites {
	public static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(Diorama.MOD_ID, "textures/gui/book.png");
	private static final int SHEET = 256;

	/** Ink for writing on the pages, and a lighter ink for hints and disabled things. */
	public static final int INK = 0xFF5A2E22;
	public static final int INK_SOFT = 0xFF9A6A4C;
	/** The parchment of the pages, for text that sits on something dark. */
	public static final int PAPER = 0xFFF5C99B;

	/** A part of the sheet; {@code border} is how much of each side is kept when it is stretched by tiling. */
	public record Region(int u, int v, int w, int h, int border) {
		public Region(int u, int v, int w, int h) {
			this(u, v, w, h, 0);
		}
	}

	public static final Region COVER = new Region(0, 0, 64, 64, 8);
	public static final Region PAGE = new Region(64, 0, 64, 64, 8);
	public static final Region SPINE = new Region(128, 0, 8, 64);
	public static final Region SLOT = new Region(0, 64, 16, 16, 4);
	public static final Region SLOT_HOVER = new Region(16, 64, 16, 16, 4);
	public static final Region SLOT_SELECTED = new Region(32, 64, 16, 16, 4);
	public static final Region BUTTON = new Region(48, 64, 16, 16, 4);
	public static final Region BUTTON_HOVER = new Region(64, 64, 16, 16, 4);
	public static final Region BUTTON_OFF = new Region(80, 64, 16, 16, 4);
	public static final Region LABEL = new Region(96, 64, 16, 16, 4);
	public static final Region FIELD = new Region(112, 64, 16, 16, 4);
	public static final Region RIBBON = new Region(0, 80, 36, 22);
	public static final Region TAB = new Region(40, 80, 24, 20);
	public static final Region TAB_SELECTED = new Region(64, 80, 24, 20);
	public static final Region CLOSE = new Region(88, 80, 11, 11);
	public static final Region CLOSE_HOVER = new Region(99, 80, 11, 11);
	public static final Region TOGGLE_OFF = new Region(110, 80, 9, 9);
	public static final Region TOGGLE_ON = new Region(119, 80, 9, 9);
	public static final Region TOGGLE_OFF_DISABLED = new Region(128, 80, 9, 9);
	public static final Region TOGGLE_ON_DISABLED = new Region(137, 80, 9, 9);
	public static final Region ARROW_LEFT = new Region(146, 80, 7, 9);
	public static final Region ARROW_RIGHT = new Region(153, 80, 7, 9);
	public static final Region ARROW_LEFT_HOVER = new Region(160, 80, 7, 9);
	public static final Region ARROW_RIGHT_HOVER = new Region(167, 80, 7, 9);
	public static final Region LEAVE = new Region(174, 80, 7, 7);
	public static final Region HELP = new Region(181, 80, 12, 12);
	public static final Region HELP_HOVER = new Region(193, 80, 12, 12);
	public static final Region SEPARATOR = new Region(0, 112, 16, 3);
	public static final Region FLOURISH = new Region(16, 112, 16, 5);
	public static final Region NEEDLE = new Region(32, 112, 9, 9);
	/** Thumbnails, 28x20: the render styles, then the fog styles, in their enums' order. */
	public static final int THUMB_W = 28, THUMB_H = 20;

	public static Region renderThumb(int i) {
		return new Region(i * THUMB_W, 128, THUMB_W, THUMB_H);
	}

	public static Region fogThumb(int i) {
		return new Region(i * THUMB_W, 148, THUMB_W, THUMB_H);
	}

	/** A region style's picture: a red region on a patch of ground, drawn in that style. */
	public static Region regionThumb(int i) {
		return new Region(i * THUMB_W, 168, THUMB_W, THUMB_H);
	}

	private BookSprites() {
	}

	/** A region at its own size, multiplied by {@code argb}. */
	public static void sprite(GuiGraphicsExtractor graphics, Region r, int x, int y, int argb) {
		MapCanvas.of(graphics).blit(TEXTURE, x, y, r.u(), r.v(), r.w(), r.h(), SHEET, SHEET, argb);
	}

	/** Part of a region: {@code w} by {@code h} texels from its top left corner shifted by (du, dv). */
	public static void part(GuiGraphicsExtractor graphics, Region r, int x, int y, int du, int dv, int w, int h, int argb) {
		MapCanvas.of(graphics).blit(TEXTURE, x, y, r.u() + du, r.v() + dv, w, h, SHEET, SHEET, argb);
	}

	/**
	 * A region nine-sliced to {@code w} by {@code h}: corners as they are, edges and middle repeated
	 * rather than stretched, so every texel stays one GUI pixel. Without {@code middle} only the rim is
	 * drawn, for a cover whose middle is hidden under the pages.
	 */
	public static void nine(GuiGraphicsExtractor graphics, Region r, int x, int y, int w, int h, int argb, boolean middle) {
		MapCanvas canvas = MapCanvas.of(graphics);
		int b = r.border();
		int innerU = r.w() - 2 * b, innerV = r.h() - 2 * b;
		int innerW = w - 2 * b, innerH = h - 2 * b;
		if (innerW < 0 || innerH < 0) {
			return;
		}
		// Corners.
		blit(canvas, r, x, y, 0, 0, b, b, argb);
		blit(canvas, r, x + w - b, y, r.w() - b, 0, b, b, argb);
		blit(canvas, r, x, y + h - b, 0, r.h() - b, b, b, argb);
		blit(canvas, r, x + w - b, y + h - b, r.w() - b, r.h() - b, b, b, argb);
		// Edges.
		for (int at = 0; at < innerW; at += innerU) {
			int len = Math.min(innerU, innerW - at);
			blit(canvas, r, x + b + at, y, b, 0, len, b, argb);
			blit(canvas, r, x + b + at, y + h - b, b, r.h() - b, len, b, argb);
		}
		for (int at = 0; at < innerH; at += innerV) {
			int len = Math.min(innerV, innerH - at);
			blit(canvas, r, x, y + b + at, 0, b, b, len, argb);
			blit(canvas, r, x + w - b, y + b + at, r.w() - b, b, b, len, argb);
		}
		if (middle) {
			for (int dy = 0; dy < innerH; dy += innerV) {
				for (int dx = 0; dx < innerW; dx += innerU) {
					blit(canvas, r, x + b + dx, y + b + dy, b, b, Math.min(innerU, innerW - dx), Math.min(innerV, innerH - dy), argb);
				}
			}
		}
	}

	/** A region repeated across {@code w}, cut at the end. */
	public static void across(GuiGraphicsExtractor graphics, Region r, int x, int y, int w, int argb) {
		MapCanvas canvas = MapCanvas.of(graphics);
		for (int at = 0; at < w; at += r.w()) {
			blit(canvas, r, x + at, y, 0, 0, Math.min(r.w(), w - at), r.h(), argb);
		}
	}

	/** A region repeated down {@code h}, cut at the end. */
	public static void down(GuiGraphicsExtractor graphics, Region r, int x, int y, int h, int argb) {
		MapCanvas canvas = MapCanvas.of(graphics);
		for (int at = 0; at < h; at += r.h()) {
			blit(canvas, r, x, y + at, 0, 0, r.w(), Math.min(r.h(), h - at), argb);
		}
	}

	private static void blit(MapCanvas canvas, Region r, int x, int y, int du, int dv, int w, int h, int argb) {
		if (w > 0 && h > 0) {
			canvas.blit(TEXTURE, x, y, r.u() + du, r.v() + dv, w, h, SHEET, SHEET, argb);
		}
	}

	/** The item with this vanilla id, as a stack for drawing; air if it isn't there. */
	public static ItemStack stack(String id) {
		Identifier key = Identifier.withDefaultNamespace(id);
		//? if >=1.21.2 {
		return new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(key));
		//?} else {
		/*return new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(key));
		*///?}
	}

	/** An item's icon, 16 GUI pixels across, as the inventory draws it: resource packs and all. */
	public static void item(GuiGraphicsExtractor graphics, ItemStack stack, int x, int y) {
		//? if >=26.1 {
		graphics.fakeItem(stack, x, y);
		//?} else {
		/*graphics.renderFakeItem(stack, x, y);
		*///?}
	}

	/**
	 * Whether edit boxes can be written in ink on the page: their text's drop shadow can only be
	 * turned off from 1.21.6. Before, dark ink with its shadow smudges, so they keep vanilla's box.
	 */
	//? if >=1.21.6 {
	public static final boolean INKED_FIELDS = true;
	//?} else {
	/*public static final boolean INKED_FIELDS = false;
	*///?}

	/** Draws an edit box's text as ink on the page, without its box or drop shadow. */
	public static void ink(net.minecraft.client.gui.components.EditBox box) {
		box.setBordered(false);
		box.setTextColor(INK);
		//? if >=1.21.6 {
		box.setTextShadow(false);
		//?}
	}

	/**
	 * Starts drawing over everything drawn so far, text included: the GUI draws text above shapes
	 * within one layer, so the map's labels would show through the book otherwise.
	 */
	public static void above(GuiGraphicsExtractor graphics) {
		//? if >=1.21.6 {
		graphics.nextStratum();
		//?} else {
		/*graphics.flush();
		*///?}
	}
}
