package dev.diorama.render;

import org.jspecify.annotations.Nullable;

/**
 * CPU endpoints only; the GPU eases discovery without rebuilding or uploading a mask per frame.
 *
 * <p>The curve is an ease-out: it moves fastest at the start. Tiles stream in over many frames
 * while a map opens, and each arrival restarts the clock from the displayed state. An ease-in
 * curve (smoothstep) has zero speed at its start, so frequent restarts kept the reveal almost
 * frozen until loading finished. With ease-out every restart keeps the fog visibly receding.
 *
 * <p>Coverage borrowed from another level of detail, while a tile of the shown one loads, is
 * known ground drawn from other tiles: it never eases in, and neither does the tile replacing it.
 */
public final class FogReveal {
	public static final long DURATION_NANOS = 420_000_000L;
	private final int size;
	private final byte[] from, to, scratch;
	/** Texels whose last value was borrowed; see {@link #update(int[], boolean[], double, double, double, boolean, long)}. */
	private final boolean[] borrowed, borrowedScratch;
	private boolean initialized, changing;
	private double u, v, texel;
	private long started;

	public FogReveal(int size) {
		this.size = size;
		this.from = new byte[size * size];
		this.to = new byte[size * size];
		this.scratch = new byte[size * size];
		this.borrowed = new boolean[size * size];
		this.borrowedScratch = new boolean[size * size];
	}

	public float progress(long now) {
		float t = changing ? Math.max(0, Math.min(1, (now - started) / (float) DURATION_NANOS)) : 1;
		float rest = 1 - t;
		return 1 - rest * rest * rest;
	}

	/** A coverage decrease is immediate; only new knowledge fades in. Reprojects a moving viewport. */
	public void update(int[] pixels, double originU, double originV, double step, boolean reset, long now) {
		this.update(pixels, null, originU, originV, step, reset, now);
	}

	/**
	 * Same, where {@code lent} marks texels whose coverage came from another level of detail's
	 * tiles: they're shown as they are, and so is the first value after one, when the tile of the
	 * shown level arrives. Neither is a discovery.
	 */
	public void update(int[] pixels, boolean @Nullable [] lent, double originU, double originV, double step, boolean reset, long now) {
		float t = progress(now);
		byte[] next = scratch;
		boolean animate = false;
		boolean aligned = originU == u && originV == v && step == texel;
		if (initialized && !reset && aligned) {
			boolean same = true;
			for (int i = 0; i < to.length && same; i++) same = (pixels[i] & 255) == (to[i] & 255);
			if (same) {
				if (t == 1) { System.arraycopy(to, 0, from, 0, to.length); changing = false; }
				for (int i = 0; i < to.length; i++) borrowed[i] = lent != null && lent[i];
				for (int i = 0; i < to.length; i++) pixels[i] = (from[i] & 255) << 24 | (to[i] & 255);
				return; // Metadata/seed refinement must not restart an ongoing discovery.
			}
		}
		for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
			int i = y * size + x;
			int target = pixels[i] & 255;
			int old = target;
			boolean lends = lent != null && lent[i];
			if (initialized && !reset && !lends) {
				int ox = aligned ? x : (int) Math.floor((originU + (x + .5) * step - u) / texel);
				int oy = aligned ? y : (int) Math.floor((originV + (y + .5) * step - v) / texel);
				if (ox >= 0 && oy >= 0 && ox < size && oy < size) {
					int j = oy * size + ox;
					if (!borrowed[j]) old = Math.round((from[j] & 255) * (1 - t) + (to[j] & 255) * t);
				}
			}
			old = Math.min(old, target);
			next[i] = (byte) old;
			borrowedScratch[i] = lends;
			animate |= old != target;
			pixels[i] = (old << 24) | target;
		}
		System.arraycopy(next, 0, from, 0, next.length);
		System.arraycopy(borrowedScratch, 0, borrowed, 0, borrowed.length);
		for (int i = 0; i < to.length; i++) to[i] = (byte) pixels[i];
		u = originU; v = originV; texel = step; started = now;
		initialized = true; changing = animate;
	}

	public void reset() { initialized = changing = false; java.util.Arrays.fill(borrowed, false); }
}
