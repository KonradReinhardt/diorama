package dev.diorama.render;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PaperFrameTest {
	private static final int PAPER = 0xFF96BED6, INK = 0xFF6C8799, TEXTURE = 256;

	@Test void paperCoversTheRimRaggedlyAndLeavesTheMiddle() {
		int texels = 64;
		int[] edge = PaperFrame.edgePixels(texels, PAPER, INK);
		for (int t = 0; t < texels; t++) {
			assertEquals(PAPER, edge[t], "top rim");
			assertEquals(PAPER, edge[(texels - 1) * TEXTURE + t], "bottom rim");
			assertEquals(PAPER, edge[t * TEXTURE], "left rim");
			assertEquals(PAPER, edge[t * TEXTURE + texels - 1], "right rim");
			// The tear and its ink stay in the outer few pixels.
			assertEquals(0, edge[(texels / 2) * TEXTURE + 7 + t % (texels - 14)]);
		}
		// The depth varies along a side, so the map's edge isn't a straight line.
		int shallow = Integer.MAX_VALUE, deep = 0;
		for (int y = 8; y < texels - 8; y++) {
			int d = 0;
			while (edge[y * TEXTURE + d] == PAPER) d++;
			shallow = Math.min(shallow, d);
			deep = Math.max(deep, d);
			assertEquals(INK & 0x00FFFFFF, edge[y * TEXTURE + d] & 0x00FFFFFF, "faint ink along the tear");
		}
		assertTrue(deep - shallow >= 2 && deep <= 3, "depth " + shallow + ".." + deep);
	}

	@Test void sheetKeepsTheOutlineAndMultipliesThePaper() {
		int size = 64;
		int[] pixels = new int[size * size];
		boolean[] opaque = new boolean[size * size];
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				int i = y * size + x;
				// A transparent notch in the left side, a dark rim two texels deep beside it, light paper inside.
				opaque[i] = !(x == 0 && y == 1);
				boolean rim = x == 0 || y == 0 || x == size - 1 || y == size - 1 || x <= 2 && y == 1;
				pixels[i] = rim ? INK : PAPER;
			}
		}
		int[] sheet = PaperFrame.sheetPixels(pixels, opaque);
		assertEquals(0, sheet[size], "transparent stays transparent");
		assertEquals(INK, sheet[size / 2], "the rim is drawn as it is");
		assertEquals(INK, sheet[size + 2], "two texels in from the notch too");
		int middle = sheet[(size / 2) * size + size / 2];
		int alpha = middle >>> 24;
		assertTrue(alpha > 0 && alpha < 128, "paper darkens lightly: " + alpha);
		assertTrue(PaperFrame.luma(middle) < PaperFrame.luma(PAPER) / 2, "and only darkens");
	}
}
