package dev.diorama.bake;

import org.jspecify.annotations.Nullable;

/**
 * What a bake records about the surface each pixel shows, a byte per pixel: whether the pixel was
 * traced at all ({@link #KNOWN}) and whether it shows a water surface ({@link #WATER}). The coast
 * effect draws foam and ripples from it (see docs/design/coast.md). Zero means unknown: pixels
 * kept from tiles saved before bakes recorded it, or never traced.
 */
public final class Surface {
	public static final int KNOWN = 1;
	public static final int WATER = 2;

	private Surface() {
	}

	/** The byte for a traced pixel. */
	public static byte of(boolean water) {
		return (byte) (KNOWN | (water ? WATER : 0));
	}

	/** Whether any pixel of the plane is known. */
	public static boolean anyKnown(byte @Nullable [] plane) {
		if (plane != null) for (byte b : plane) if ((b & KNOWN) != 0) return true;
		return false;
	}
}
