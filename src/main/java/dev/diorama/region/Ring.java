package dev.diorama.region;

/**
 * One closed outline of a region, in world blocks: {@code x} and {@code z} of each vertex, and the
 * height of the ground under it when it was drawn, so the outline lies on the terrain the player
 * pointed at (the map is oblique; see docs/design/map-regions.md). The last vertex joins the first.
 * Immutable: the arrays are copied in and never handed out.
 */
public final class Ring {
	private final float[] x;
	private final float[] z;
	private final short[] y;

	public Ring(float[] x, float[] z, short[] y) {
		if (x.length != z.length || x.length != y.length) {
			throw new IllegalArgumentException("A ring needs as many x, z and y as vertices");
		}
		this.x = x.clone();
		this.z = z.clone();
		this.y = y.clone();
	}

	public int size() {
		return this.x.length;
	}

	public float x(int i) {
		return this.x[i];
	}

	public float z(int i) {
		return this.z[i];
	}

	public int y(int i) {
		return this.y[i];
	}
}
