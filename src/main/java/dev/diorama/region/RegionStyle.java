package dev.diorama.region;

/**
 * How a region's border and inside are drawn (regions.fsh). {@link #AUTO} defers: a region's own
 * to the map's setting, and the map's setting to the map's render style (or an antique fog).
 */
public enum RegionStyle {
	AUTO,
	/** A solid line in the dye and a flat translucent fill. */
	PLAIN,
	/** Dark ink outside a line in the dye, a stronger band along the inside, as a political map. */
	OUTLINED,
	/** A watercolour wash pooling at the edge, with a sepia pen line. */
	PAINTED,
	/** The same wash, no pen. */
	PAINTED_NO_INK,
	/** An old map's dash-dot border, a band tinted in sepia, the inside hatched. */
	ANTIQUE,
	/** The same border, the band in the dye, the inside almost bare. */
	ANTIQUE_COLOR;

	/** This style, or {@code fallback} for {@link #AUTO}. */
	public RegionStyle or(RegionStyle fallback) {
		return this == AUTO ? fallback : this;
	}

	/** The number regions.fsh knows the style by, 0 to 5; never asked of {@link #AUTO}. */
	public int shaderIndex() {
		return this.ordinal() - 1;
	}
}
