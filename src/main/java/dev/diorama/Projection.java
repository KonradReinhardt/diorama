package dev.diorama;

/**
 * The oblique camera every map pixel is traced through.
 *
 * <p>The camera looks toward the selected cardinal direction and down at the style's pitch.
 * The formulas below describe camera-local coordinates; MapDirection converts world x/z. Map space is measured in blocks:
 * <ul>
 *   <li>{@code u = x} (east is right)</li>
 *   <li>{@code v = z * zScale - y * yScale} (south is down, and higher blocks move up the screen)</li>
 * </ul>
 *
 * <p>It is the view down at the pitch, stretched up the screen so a top face is always exactly
 * one unit, {@link #PIXELS_PER_UNIT} pixels, tall: {@code zScale} is 1 and {@code yScale} the
 * cotangent of the pitch. At 45 degrees a block's side is a unit tall too, so every block edge
 * lands on a pixel boundary, every visible face is a 4 by 4 pixel square of the map, and every
 * pixel shows a single face.
 */
public final class Projection {
	// The default style's angle until a style is applied.
	private static volatile double zScale = zScale(MapStyle.DEFAULT.pitchDegrees());
	private static volatile double yScale = yScale(MapStyle.DEFAULT.pitchDegrees());

	/** Map units per tile edge. */
	public static final int TILE_UNITS = 128;
	/** Texture pixels per map unit. */
	public static final int PIXELS_PER_UNIT = 4;
	public static final int TILE_PIXELS = TILE_UNITS * PIXELS_PER_UNIT;
	/** Fog coverage cells per tile edge (one cell covers {@code TILE_UNITS / COVERAGE_CELLS} units). */
	public static final int COVERAGE_CELLS = 64;

    private static volatile MapDirection direction = MapDirection.NORTH;
    public static MapDirection direction() { return direction; }
    static void setDirection(MapDirection value) { direction = value; }
    public static double u(double x, double z) { return direction.u(x, z); }
    public static double worldV(double x, double y, double z) { return v(y, direction.depth(x, z)); }

	private Projection() {
	}

	/** Changes the camera angle. Everything baked before is wrong afterwards; see {@link MapStyle}. */
	static void setPitch(double degrees) {
		zScale = zScale(degrees);
		yScale = yScale(degrees);
	}

	/** Map units a block spans north to south: always one, so top faces are whole pixels. */
	public static double zScale(double pitchDegrees) {
		return 1;
	}

	/** Map units a block of height spans: the cotangent of the pitch, so the view keeps its angle. */
	public static double yScale(double pitchDegrees) {
		// Exactly 1 at 45 degrees, where floating point would give 1.0000000000000002.
		return pitchDegrees == 45 ? 1 : 1 / Math.tan(Math.toRadians(pitchDegrees));
	}

	public static double zScale() {
		return zScale;
	}

	public static double yScale() {
		return yScale;
	}

	public static double v(double y, double z) {
		return z * zScale - y * yScale;
	}

	/** World z where a ray through map row {@code v} crosses height {@code y}. */
	public static double zAt(double v, double y) {
		return (v + y * yScale) / zScale;
	}
}
