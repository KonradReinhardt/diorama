package dev.diorama.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the world map's book goes on a screen of a given GUI size: the cover, its one or two
 * pages, the ribbons down its right side (and at the screen's edge while it is shut), the tabs
 * along its top and its close mark. Whole GUI pixels throughout. Pure, for tests.
 */
public record BookLayout(
	int width, int height, boolean twoPages, Rect cover, Rect left, Rect right, Rect spine,
	List<Rect> ribbons, int ribbonOpenX, int ribbonShutX, Rect close, int tabY
) {
	public record Rect(int x, int y, int w, int h) {
		public int right() {
			return this.x + this.w;
		}

		public int bottom() {
			return this.y + this.h;
		}

		public boolean contains(double px, double py) {
			return px >= this.x && px < this.x + this.w && py >= this.y && py < this.y + this.h;
		}

		public boolean overlaps(Rect o) {
			return this.x < o.right() && o.x < this.right() && this.y < o.bottom() && o.y < this.bottom();
		}

		/** The rectangle shrunk by {@code d} on every side. */
		public Rect inset(int d) {
			return new Rect(this.x + d, this.y + d, this.w - 2 * d, this.h - 2 * d);
		}
	}

	/** Cover showing around the pages, and the crease between them. */
	public static final int COVER = 6, SPINE = 8;
	/** How far a ribbon reaches out of the book, and one more when it is the open section. */
	public static final int RIBBON_OUT = 24, RIBBON_SELECTED = 6, RIBBON_H = 22;
	/** How much of a ribbon shows at the screen's edge while the book is shut. */
	public static final int RIBBON_SHUT = 26;
	/** Tabs rise this far over the cover's top. */
	public static final int TAB_RISE = 18, TAB_W = 24;
	public static final int MARGIN = 6;
	public static final int PAGE_MIN_W = 120, PAGE_MAX_W = 160, PAGE_MIN_H = 124, PAGE_MAX_H = 196;

	public static BookLayout of(int width, int height, int ribbonCount) {
		int reach = RIBBON_OUT + RIBBON_SELECTED;
		int spareW = width - 2 * MARGIN - reach - 2 * COVER;
		boolean two = (spareW - SPINE) / 2 >= PAGE_MIN_W;
		int pageW = two ? Math.min(PAGE_MAX_W, (spareW - SPINE) / 2) : Math.max(60, Math.min(PAGE_MAX_W + 20, spareW));
		// Below PAGE_MIN_H the pages still shrink to fit; their contents scroll by page instead.
		int pageH = Math.max(40, Math.min(PAGE_MAX_H, height - 2 * MARGIN - TAB_RISE - 2 * COVER));
		int coverW = 2 * COVER + (two ? 2 * pageW + SPINE : pageW);
		int coverH = 2 * COVER + pageH;
		int x = (width - coverW - reach) / 2;
		int y = MARGIN + TAB_RISE + Math.max(0, (height - 2 * MARGIN - TAB_RISE - coverH) / 2);
		Rect cover = new Rect(x, y, coverW, coverH);
		Rect left = new Rect(x + COVER, y + COVER, pageW, pageH);
		Rect right = two ? new Rect(left.right() + SPINE, left.y(), pageW, pageH) : left;
		Rect spine = two ? new Rect(left.right(), left.y(), SPINE, pageH) : new Rect(left.right(), left.y(), 0, pageH);
		// Ribbons as far apart as fits, never overlapping, from just below the cover's top.
		int pitch = Math.max(RIBBON_H, Math.min(RIBBON_H + 3, (coverH - 16) / Math.max(1, ribbonCount)));
		List<Rect> ribbons = new ArrayList<>();
		for (int i = 0; i < ribbonCount; i++) {
			ribbons.add(new Rect(cover.right(), y + 10 + i * pitch, RIBBON_OUT, RIBBON_H));
		}
		Rect close = new Rect(cover.right() - 13, y - 4, 11, 11);
		return new BookLayout(width, height, two, cover, left, right, spine, ribbons, cover.right(), width - RIBBON_SHUT, close, y - TAB_RISE);
	}

	/** The part of a page that holds its contents, inside its frame. */
	public static Rect content(Rect page) {
		return page.inset(8);
	}
}
