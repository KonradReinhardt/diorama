package dev.diorama.bake;

/** Box-filtered mip chain for ABGR tiles, weighted by alpha so unknown pixels don't darken edges. */
public final class Mipmaps {
	public static final int LEVELS = 6;

	private Mipmaps() {
	}

	public static int[][] build(int[] base, int size) {
		int[][] levels = new int[LEVELS][];
		levels[0] = base;
		for (int level = 1; level < LEVELS; level++) {
			int src = size >> (level - 1);
			int dst = src >> 1;
			int[] from = levels[level - 1];
			int[] to = new int[dst * dst];
			for (int y = 0; y < dst; y++) {
				for (int x = 0; x < dst; x++) {
					int i = (y * 2) * src + x * 2;
					to[y * dst + x] = average(from[i], from[i + 1], from[i + src], from[i + src + 1]);
				}
			}
			levels[level] = to;
		}
		return levels;
	}

	/** Alpha-weighted average of four ABGR pixels. */
	public static int average(int p0, int p1, int p2, int p3) {
		int a0 = p0 >>> 24;
		int a1 = p1 >>> 24;
		int a2 = p2 >>> 24;
		int a3 = p3 >>> 24;
		int alpha = a0 + a1 + a2 + a3;
		if (alpha == 0) {
			return 0;
		}
        int r = channel(p0, p1, p2, p3, a0, a1, a2, a3, alpha, 0);
        int g = channel(p0, p1, p2, p3, a0, a1, a2, a3, alpha, 8);
        int b = channel(p0, p1, p2, p3, a0, a1, a2, a3, alpha, 16);
		return ((alpha / 4) << 24) | (b << 16) | (g << 8) | r;
	}
    private static int channel(int p0, int p1, int p2, int p3, int a0, int a1, int a2, int a3, int alpha, int shift) {
        return LinearColor.encode((LinearColor.decode(p0 >> shift) * a0 + LinearColor.decode(p1 >> shift) * a1
            + LinearColor.decode(p2 >> shift) * a2 + LinearColor.decode(p3 >> shift) * a3) / alpha);
    }
}
