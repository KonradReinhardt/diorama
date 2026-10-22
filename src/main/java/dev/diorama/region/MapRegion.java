package dev.diorama.region;

import dev.diorama.waypoint.BannerDesign;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A region the player drew on the map: a kingdom, a farm district, a claim. Its outline is a list
 * of {@link Ring}s in world blocks, filled by the even-odd rule, so holes and separate parts need
 * nothing more. Mutable like a waypoint; {@link RegionStore} saves after every change, and history
 * keeps {@link #copy}s.
 */
public final class MapRegion {
	/** How strongly the inside is tinted; the border is drawn whatever the fill. */
	public enum Fill { NONE, LIGHT, NORMAL, STRONG }

	public UUID id = UUID.randomUUID();
	public String name = "";
	/** Dimension id, e.g. {@code minecraft:overworld}. */
	public String dimension = "minecraft:overworld";
	/** One of the banner dyes, so a region and its banner share a palette. */
	public BannerDesign.Dye dye = BannerDesign.Dye.RED;
	public Fill fill = Fill.NORMAL;
	public List<Ring> rings = new ArrayList<>();
	/** The standard planted at the region's label; none draws only its name. */
	public @Nullable BannerDesign banner;
	public boolean visible = true;
	public boolean onMinimap = true;
	public boolean showLabel = true;
	public long created = System.currentTimeMillis();

	/** Area and bounds, worked out when first asked after a change; never saved. */
	private transient double @Nullable [] shape;
	/** Where the label goes, likewise. */
	private transient double @Nullable [] anchor;

	public MapRegion() {
	}

	/** A copy that shares nothing mutable: rings and banners are immutable, the list is new. */
	public MapRegion copy() {
		MapRegion out = new MapRegion();
		out.id = this.id;
		out.name = this.name;
		out.dimension = this.dimension;
		out.dye = this.dye;
		out.fill = this.fill;
		out.rings = new ArrayList<>(this.rings);
		out.banner = this.banner;
		out.visible = this.visible;
		out.onMinimap = this.onMinimap;
		out.showLabel = this.showLabel;
		out.created = this.created;
		return out;
	}

	/** The banner it shows: its own, or a plain one in its dye. */
	public BannerDesign design() {
		return this.banner != null ? this.banner : BannerDesign.plain(this.dye);
	}

	/** Call after changing the rings in place. */
	public void reshaped() {
		this.shape = null;
		this.anchor = null;
	}

	/**
	 * Where its name is written: {x, z} of the point inside farthest from its edges (see
	 * {@link RegionGeometry#label}), and the mean height of its corners, as the ground there.
	 */
	public double[] anchor() {
		double[] a = this.anchor;
		if (a == null) {
			double[] at = RegionGeometry.label(this.rings, 1);
			double height = 0;
			int count = 0;
			for (Ring ring : this.rings) {
				for (int i = 0; i < ring.size(); i++) {
					height += ring.y(i);
					count++;
				}
			}
			a = new double[] {at[0], at[1], count == 0 ? 64 : height / count};
			this.anchor = a;
		}
		return a;
	}

	/** Blocks² inside, holes taken out. */
	public double area() {
		return this.shape()[4];
	}

	/** {minX, minZ, maxX, maxZ} in blocks. */
	public double[] bounds() {
		double[] s = this.shape();
		return new double[] {s[0], s[1], s[2], s[3]};
	}

	public int vertices() {
		int n = 0;
		for (Ring ring : this.rings) {
			n += ring.size();
		}
		return n;
	}

	private double[] shape() {
		double[] s = this.shape;
		if (s == null) {
			double[] b = RegionGeometry.bounds(this.rings);
			s = new double[] {b[0], b[1], b[2], b[3], RegionGeometry.area(this.rings)};
			this.shape = s;
		}
		return s;
	}
}
