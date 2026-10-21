package dev.diorama.render;

import dev.diorama.MapDirection;

/**
 * The clouds drifting over the explored map and their shadows: a shader pass of its own
 * (clouds.fsh) drawn over the tiles and under the fog, never baked into tiles or saved. It reads
 * the fog's mask and noise; this packs what else it needs into the quad's vertex colour. See
 * docs/design/map-clouds.md. Pure, for tests.
 */
public final class MapClouds {
	/**
	 * Map units across after which the shader's world-anchored pattern repeats, and down (the
	 * same depth at the map's 45 degree pitch, three quarters of a unit a block). Must match
	 * clouds.fsh's REGION_PERIOD and ROW.
	 */
	static final int PERIOD_U = 8192;
	static final int PERIOD_V = PERIOD_U * 3 / 4;
	/** The mask's origin snaps to multiples of this or more, so the shader gets it in these steps. */
	static final int ORIGIN_STEP = 128;

	private MapClouds() {
	}

	/** Whether this version can draw them: they need the render pipelines of 1.21.6 on. */
	public static boolean supported() {
		//? if >=1.21.6 {
		return true;
		//?} else {
		/*return false;
		*///?}
	}

	/**
	 * The vertex colour for the clouds over a fog mask whose origin is at (originU, originV), a
	 * multiple of {@link #ORIGIN_STEP}; {@code scaleCode} and {@code pixelCode} as for the fog.
	 */
	static int params(int scaleCode, double originU, double originV, int pixelCode, float daylight, float rain,
		MapDirection direction, boolean sepia) {
		int u = (int) (Math.floorMod((long) originU, (long) PERIOD_U) / ORIGIN_STEP);
		int v = (int) (Math.floorMod((long) originV, (long) PERIOD_V) / ORIGIN_STEP);
		int day = Math.round(clamp(daylight) * 15);
		int wet = Math.round(clamp(rain) * 3);
		return (scaleCode << 4 | day) << 24 | (u | wet << 6) << 16 | (v | direction.ordinal() << 6) << 8
			| (pixelCode & 63) | (sepia ? 64 : 0);
	}

	private static float clamp(float x) {
		return Float.isFinite(x) ? Math.max(0, Math.min(1, x)) : 1;
	}
}
