package dev.diorama.ui;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BookLayoutTest {
	private static final int RIBBONS = 6;

	@Test
	void fitsCommonScreensWithTwoPages() {
		// 1280x720 at GUI scale 3, 1920x1080 at 3, and 1920x1080 at 2.
		for (int[] size : new int[][] {{427, 240}, {640, 360}, {960, 540}}) {
			BookLayout layout = BookLayout.of(size[0], size[1], RIBBONS);
			assertTrue(layout.twoPages(), size[0] + "x" + size[1]);
			check(layout);
			assertTrue(layout.left().h() >= BookLayout.PAGE_MIN_H);
		}
	}

	@Test
	void narrowScreensGetOnePage() {
		BookLayout layout = BookLayout.of(280, 240, RIBBONS);
		assertFalse(layout.twoPages());
		assertEquals(layout.left(), layout.right());
		check(layout);
	}

	@Test
	void pagesNeverGrowPastTheirLargestSize() {
		BookLayout layout = BookLayout.of(1920, 1080, RIBBONS);
		assertEquals(BookLayout.PAGE_MAX_W, layout.left().w());
		assertEquals(BookLayout.PAGE_MAX_H, layout.left().h());
		check(layout);
	}

	private static void check(BookLayout layout) {
		int w = layout.width(), h = layout.height();
		List<BookLayout.Rect> all = new ArrayList<>();
		all.add(layout.cover());
		all.add(layout.close());
		all.add(new BookLayout.Rect(layout.left().x(), layout.tabY(), BookLayout.TAB_W * 2, BookLayout.TAB_RISE));
		for (BookLayout.Rect ribbon : layout.ribbons()) {
			// The open section's ribbon reaches out a little further.
			all.add(new BookLayout.Rect(layout.ribbonOpenX(), ribbon.y(), BookLayout.RIBBON_OUT + BookLayout.RIBBON_SELECTED, ribbon.h()));
			all.add(new BookLayout.Rect(layout.ribbonShutX(), ribbon.y(), BookLayout.RIBBON_SHUT, ribbon.h()));
		}
		for (BookLayout.Rect r : all) {
			assertTrue(r.x() >= 0 && r.y() >= 0 && r.right() <= w && r.bottom() <= h, r + " runs off " + w + "x" + h);
		}
		// The pages sit inside the cover, side by side.
		assertTrue(layout.left().x() > layout.cover().x() && layout.right().right() < layout.cover().right());
		assertTrue(layout.left().bottom() < layout.cover().bottom());
		if (layout.twoPages()) {
			assertFalse(layout.left().overlaps(layout.right()));
			assertEquals(layout.left().right(), layout.spine().x());
			assertEquals(layout.spine().right(), layout.right().x());
		}
		List<BookLayout.Rect> ribbons = layout.ribbons();
		assertEquals(RIBBONS, ribbons.size());
		for (int i = 1; i < ribbons.size(); i++) {
			assertFalse(ribbons.get(i - 1).overlaps(ribbons.get(i)), "ribbons overlap");
		}
		assertTrue(ribbons.get(ribbons.size() - 1).bottom() <= layout.cover().bottom(), "ribbons hang below the book");
	}
}
