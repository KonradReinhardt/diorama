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

	/** This ring with vertex {@code i} moved to (x, z) on ground {@code y} high. */
	public Ring with(int i, float x, float z, int y) {
		Ring out = new Ring(this.x, this.z, this.y);
		out.x[i] = x;
		out.z[i] = z;
		out.y[i] = clamp(y);
		return out;
	}

	/** This ring with a vertex put in after vertex {@code i}, on the side to the next one. */
	public Ring inserting(int i, float x, float z, int y) {
		int n = this.size();
		float[] nx = new float[n + 1], nz = new float[n + 1];
		short[] ny = new short[n + 1];
		System.arraycopy(this.x, 0, nx, 0, i + 1);
		System.arraycopy(this.z, 0, nz, 0, i + 1);
		System.arraycopy(this.y, 0, ny, 0, i + 1);
		nx[i + 1] = x;
		nz[i + 1] = z;
		ny[i + 1] = clamp(y);
		System.arraycopy(this.x, i + 1, nx, i + 2, n - i - 1);
		System.arraycopy(this.z, i + 1, nz, i + 2, n - i - 1);
		System.arraycopy(this.y, i + 1, ny, i + 2, n - i - 1);
		return new Ring(nx, nz, ny);
	}

	/** This ring without vertex {@code i}, or null if fewer than three would be left. */
	public @org.jspecify.annotations.Nullable Ring without(int i) {
		int n = this.size();
		if (n <= 3) {
			return null;
		}
		float[] nx = new float[n - 1], nz = new float[n - 1];
		short[] ny = new short[n - 1];
		for (int j = 0, k = 0; j < n; j++) {
			if (j == i) continue;
			nx[k] = this.x[j];
			nz[k] = this.z[j];
			ny[k] = this.y[j];
			k++;
		}
		return new Ring(nx, nz, ny);
	}

	/** This ring moved {@code dx}, {@code dz} blocks, on the heights {@code heights} gives each moved vertex. */
	public Ring moved(float dx, float dz, java.util.function.IntUnaryOperator heights) {
		Ring out = new Ring(this.x, this.z, this.y);
		for (int i = 0; i < out.size(); i++) {
			out.x[i] += dx;
			out.z[i] += dz;
			out.y[i] = clamp(heights.applyAsInt(i));
		}
		return out;
	}

	private static short clamp(int y) {
		return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, y));
	}
}
