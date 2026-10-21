package dev.diorama.bake;

import org.jspecify.annotations.Nullable;

/**
 * What a bake records about the surface each pixel shows, a byte per pixel: whether the pixel was
 * traced at all ({@link #KNOWN}), whether it shows a water surface ({@link #WATER}), and whether it
 * shows land that really meets the water ({@link #SHORE}). The coast effect draws foam and ripples
 * from it (see docs/design/coast.md). Zero means unknown: pixels kept from tiles saved before bakes
 * recorded it, or never traced.
 */
public final class Surface {
	public static final int KNOWN = 1;
	public static final int WATER = 2;
	/**
	 * Land whose block has water beside it, near its own height: a beach, a bank, the foot of a
	 * cliff or a tower. Land standing high over water it hides from the map (a tower's top and
	 * upper walls) isn't shore, so no foam is drawn along it.
	 */
	public static final int SHORE = 4;

	private Surface() {
	}

	/** The byte for a traced pixel. */
	public static byte of(boolean water) {
		return (byte) (KNOWN | (water ? WATER : 0));
	}

	/**
	 * The bits of an overview pixel from the four it shrinks: known if any is, water if most known
	 * ones are, shore if any is, so a coast never vanishes as the map zooms out.
	 */
	public static byte shrink(byte a, byte b, byte c, byte d) {
		int known = 0, water = 0, shore = 0;
		for (byte bits : new byte[] {a, b, c, d}) {
			if ((bits & KNOWN) == 0) continue;
			known++;
			if ((bits & WATER) != 0) water++;
			if ((bits & SHORE) != 0) shore++;
		}
		if (known == 0) return 0;
		return (byte) (KNOWN | (2 * water > known ? WATER : 0) | (shore > 0 ? SHORE : 0));
	}

	/** Whether any pixel of the plane is known. */
	public static boolean anyKnown(byte @Nullable [] plane) {
		if (plane != null) for (byte b : plane) if ((b & KNOWN) != 0) return true;
		return false;
	}
}
