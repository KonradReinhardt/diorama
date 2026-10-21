package dev.diorama.region;

import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/**
 * A stroke being drawn on the map with the region tools, in map units so it stays put while the
 * map pans and zooms under it. Pure, for tests: the map screen hands it points and zooms, and gets
 * back the closed outline to turn into a region.
 * <ul>
 *   <li>Lasso: a drag traces the outline, a point every {@link #LASSO_STEP} GUI pixels; letting go
 *   closes it.</li>
 *   <li>Polygon: each click adds a corner; a click on the first corner, a double click or
 *   {@link #close()} (Enter) closes it.</li>
 * </ul>
 */
public final class RegionTool {
	/** The tools, in the order of their keys, 1 to 4. */
	public enum Tool { LASSO, POLYGON, EDIT, ERASER }

	/** GUI pixels from a corner, or a side, within which the edit tool takes it. */
	public static final double GRAB_REACH = 4;

	/** GUI pixels the mouse moves before the lasso takes another point. */
	public static final double LASSO_STEP = 2;
	/** GUI pixels from the first corner within which a click closes the polygon. */
	public static final double CLOSE_REACH = 6;
	/** Two clicks this close in time, and in {@link #CLOSE_REACH}, are a double click. */
	public static final long DOUBLE_CLICK_NANOS = 350_000_000L;

	private double[] u = new double[64];
	private double[] v = new double[64];
	private int count;
	private long lastClick;

	/** Whether a stroke is under way. */
	public boolean drawing() {
		return this.count > 0;
	}

	public int count() {
		return this.count;
	}

	public double u(int i) {
		return this.u[i];
	}

	public double v(int i) {
		return this.v[i];
	}

	public void cancel() {
		this.count = 0;
		this.lastClick = 0;
	}

	/** The lasso goes down at a map point. */
	public void begin(double u, double v) {
		this.cancel();
		this.add(u, v);
	}

	/** The lasso is dragged to a map point, at {@code zoom} GUI pixels per map unit. */
	public void extend(double u, double v, double zoom) {
		if (this.count == 0) {
			this.begin(u, v);
		} else if (Math.hypot(u - this.u[this.count - 1], v - this.v[this.count - 1]) * zoom >= LASSO_STEP) {
			this.add(u, v);
		}
	}

	/** The lasso is let go: the outline traced, closed back to its start, or null if it is too short to be one. */
	public double @Nullable [][] finish() {
		return this.take();
	}

	/**
	 * A polygon click at a map point, at {@code zoom} GUI pixels per map unit, {@code nanos} being
	 * when. Returns the outline when the click closes it: on the first corner, or a double click.
	 */
	public double @Nullable [][] click(double u, double v, double zoom, long nanos) {
		boolean onFirst = this.count >= 3 && Math.hypot(u - this.u[0], v - this.v[0]) * zoom <= CLOSE_REACH;
		boolean twice = this.count >= 3 && nanos - this.lastClick <= DOUBLE_CLICK_NANOS
			&& Math.hypot(u - this.u[this.count - 1], v - this.v[this.count - 1]) * zoom <= CLOSE_REACH;
		this.lastClick = nanos;
		if (onFirst || twice) {
			return this.take();
		}
		// A click on the last corner again adds nothing.
		if (this.count == 0 || Math.hypot(u - this.u[this.count - 1], v - this.v[this.count - 1]) * zoom > 1) {
			this.add(u, v);
		}
		return null;
	}

	/** Takes the last polygon corner back (Backspace). */
	public void removeLast() {
		if (this.count > 0) {
			this.count--;
		}
	}

	/** Closes the polygon as it is (Enter), or null if it has fewer than three corners. */
	public double @Nullable [][] close() {
		return this.take();
	}

	private double @Nullable [][] take() {
		int n = this.count;
		this.cancel();
		return n < 3 ? null : new double[][] {Arrays.copyOf(this.u, n), Arrays.copyOf(this.v, n)};
	}

	private void add(double u, double v) {
		if (this.count == this.u.length) {
			this.u = Arrays.copyOf(this.u, this.count * 2);
			this.v = Arrays.copyOf(this.v, this.count * 2);
		}
		this.u[this.count] = u;
		this.v[this.count] = v;
		this.count++;
	}
}
