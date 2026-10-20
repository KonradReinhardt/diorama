package dev.diorama.render;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class BannerCompositorTest {
	private static final int W = BannerCompositor.FRONT_W, H = BannerCompositor.FRONT_H;
	private static final int RED = 0xB02E26, BLUE = 0x3C44AA, WHITE = 0xFFFFFF;

	private static BannerCompositor.Mask full() {
		int[] px = new int[W * H];
		Arrays.fill(px, 0xFFFFFFFF);
		return new BannerCompositor.Mask(W, H, px);
	}

	/** Opaque white on the left half, clear on the right. */
	private static BannerCompositor.Mask leftHalf() {
		int[] px = new int[W * H];
		for (int i = 0; i < px.length; i++) px[i] = i % W < W / 2 ? 0xFFFFFFFF : 0;
		return new BannerCompositor.Mask(W, H, px);
	}

	@Test void layersTintAndStackInOrder() {
		var base = new BannerCompositor.Tinted(full(), WHITE);
		int[] redThenBlue = BannerCompositor.compose(base, List.of(
			new BannerCompositor.Tinted(leftHalf(), RED), new BannerCompositor.Tinted(full(), BLUE)));
		assertEquals(0xFF000000 | BLUE, redThenBlue[0]);
		int[] blueThenRed = BannerCompositor.compose(base, List.of(
			new BannerCompositor.Tinted(full(), BLUE), new BannerCompositor.Tinted(leftHalf(), RED)));
		assertEquals(0xFF000000 | RED, blueThenRed[0]);
		assertEquals(0xFF000000 | BLUE, blueThenRed[W - 1]);
	}

	@Test void halfAlphaBlendsAndGreyMasksDarken() {
		int[] px = new int[W * H];
		Arrays.fill(px, 0x80808080);
		int[] out = BannerCompositor.compose(new BannerCompositor.Tinted(full(), WHITE),
			List.of(new BannerCompositor.Tinted(new BannerCompositor.Mask(W, H, px), RED)));
		int expectedRed = (255 * 127 + (0x80 * 0xB0 / 255) * 128 + 127) / 255;
		assertEquals(expectedRed, (out[0] >> 16) & 255);
	}

	@Test void reducingKeepsOnlyTheDesignsColours() {
		var base = new BannerCompositor.Tinted(full(), WHITE);
		var layers = List.of(new BannerCompositor.Tinted(leftHalf(), RED));
		int[] cloth = BannerCompositor.compose(base, layers);
		int[] palette = BannerCompositor.palette(base, layers);
		for (BannerCompositor.Size size : BannerCompositor.Size.values()) {
			int[] small = BannerCompositor.reduce(cloth, W, H, size.clothW, size.clothH, palette);
			for (int px : small) {
				assertTrue(px == (0xFF000000 | WHITE) || px == (0xFF000000 | RED), Integer.toHexString(px));
			}
			assertEquals(0xFF000000 | RED, small[0]);
			assertEquals(0xFF000000 | WHITE, small[size.clothW - 1]);
		}
	}

	@Test void largerResourcePackMasksReduceTheSame() {
		int[] hd = new int[128 * 128];
		for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) {
			int fx = x - 2;
			hd[y * 128 + x] = fx >= 0 && fx < 20 ? 0xFFFFFFFF : 0;
		}
		var mask = BannerCompositor.Mask.front(hd, 128);
		assertEquals(40, mask.width());
		assertEquals(80, mask.height());
		var base = new BannerCompositor.Tinted(full(), WHITE);
		var layers = List.of(new BannerCompositor.Tinted(mask, BLUE));
		int[] cloth = BannerCompositor.compose(base, layers);
		int[] small = BannerCompositor.reduce(cloth, W, H, 10, 20, BannerCompositor.palette(base, layers));
		assertEquals(0xFF000000 | BLUE, small[0]);
		assertEquals(0xFF000000 | WHITE, small[9]);
	}

	@Test void frameStandsOnItsFootAndWaves() {
		BannerCompositor.Size size = BannerCompositor.Size.MAP;
		int[] cloth = new int[size.clothW * size.clothH];
		Arrays.fill(cloth, 0xFF000000 | RED);
		int[] still = BannerCompositor.frame(cloth, size, BannerCompositor.STILL);
		int w = size.width();
		assertEquals(size.width() * size.height(), still.length);
		// The pole reaches the ground at the middle; the shadow lies under it.
		assertEquals(BannerCompositor.WOOD, still[(size.footY() - 1) * w + w / 2]);
		assertEquals(BannerCompositor.FOOT_SHADOW, still[size.footY() * w + w / 2]);
		assertEquals(0xFF000000 | RED, still[(BannerCompositor.CLOTH_TOP + 3) * w + 3]);
		assertNotEquals(Arrays.hashCode(still), Arrays.hashCode(BannerCompositor.frame(cloth, size, 0)));
		for (int f = 0; f < BannerCompositor.WAVE_FRAMES; f++) {
			assertNotEquals(Arrays.hashCode(BannerCompositor.frame(cloth, size, f)), Arrays.hashCode(BannerCompositor.frame(cloth, size, (f + 1) % 4)));
		}
	}
}
