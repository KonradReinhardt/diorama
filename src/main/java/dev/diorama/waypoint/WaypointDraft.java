package dev.diorama.waypoint;

import org.jspecify.annotations.Nullable;

/**
 * A waypoint's name and position as typed, before they are known to read: the world map's book
 * edits waypoints in place with these. Pure, for tests.
 */
public final class WaypointDraft {
	public String name;
	public String x;
	public String y;
	public String z;

	public WaypointDraft(Waypoint waypoint) {
		this.name = waypoint.name;
		this.x = Integer.toString(waypoint.x);
		this.y = Integer.toString(waypoint.y);
		this.z = Integer.toString(waypoint.z);
	}

	/** A whole number, or null where the text isn't one. */
	public static @Nullable Integer parse(String text) {
		try {
			return Integer.parseInt(text.trim());
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** Whether every coordinate reads. */
	public boolean valid() {
		return parse(this.x) != null && parse(this.y) != null && parse(this.z) != null;
	}

	/**
	 * Writes the draft into the waypoint, if every coordinate reads; an empty name keeps the old one.
	 * True if anything changed.
	 */
	public boolean applyTo(Waypoint waypoint) {
		Integer px = parse(this.x), py = parse(this.y), pz = parse(this.z);
		if (px == null || py == null || pz == null) {
			return false;
		}
		String trimmed = this.name.trim();
		String name = trimmed.isEmpty() ? waypoint.name : trimmed;
		boolean changed = !name.equals(waypoint.name) || px != waypoint.x || py != waypoint.y || pz != waypoint.z;
		if (!name.equals(waypoint.name)) {
			waypoint.name = name;
			// Banners replaced initials: the ones kept for sharing follow the name from now on.
			waypoint.initials = "";
		}
		waypoint.x = px;
		waypoint.y = py;
		waypoint.z = pz;
		return changed;
	}
}
