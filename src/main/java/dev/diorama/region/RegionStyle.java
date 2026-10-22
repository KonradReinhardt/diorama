package dev.diorama.region;

/**
 * How regions' borders and insides are drawn (regions.fsh), picked once for every region in the
 * regions section's options. Named apart from the render styles, which they don't follow.
 */
public enum RegionStyle {
	/** A solid line in the dye and a flat translucent fill, as a felt-tip marker. */
	MARKER,
	/** Dark ink outside a line in the dye, a stronger band along the inside, as a political map. */
	POLITICAL,
	/** A watercolour wash pooling at the edge, with a sepia pen line. */
	WATERCOLOR,
	/** The same wash, no pen. */
	WASH,
	/** An old map's dash-dot border, a band tinted in sepia, the inside hatched. */
	ENGRAVING,
	/** The same border, the band hand-coloured in the dye, the inside almost bare. */
	ILLUMINATED;

	/** The number regions.fsh knows the style by. */
	public int shaderIndex() {
		return this.ordinal();
	}
}
