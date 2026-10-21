package dev.diorama.bake;

/** Shared sRGB transfer functions for coverage-weighted mipmaps and tests. */
public final class LinearColor {
	private static final float[] DECODE = new float[256];
	private static final int[] ENCODE = new int[4096];
	static {
		for (int i = 0; i < 256; i++) {
			double x = i / 255.0;
			DECODE[i] = (float) (x <= 0.04045 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4));
		}
		for (int i = 0; i < ENCODE.length; i++) {
			double x = i / 4095.0;
			ENCODE[i] = (int) Math.round(255 * (x <= 0.0031308 ? 12.92 * x : 1.055 * Math.pow(x, 1 / 2.4) - 0.055));
		}
	}
	private LinearColor() {}
	public static float decode(int value) { return DECODE[value & 255]; }
	public static int encode(float value) { return ENCODE[Math.max(0, Math.min(4095, Math.round(value * 4095)))]; }
}
