package dev.diorama.ui;

/**
 * The short zoom in as the world map opens: the view starts a little further out and eases to
 * the zoom it keeps, ease-out cubic over {@link #DURATION_NANOS}, by real time. It scales the zoom
 * drawn, never the zoom held, so the wheel's target is untouched. Pure, for tests.
 */
public final class OpeningZoom {
	/** How far out it starts, as a fraction of the zoom it ends at. */
	public static final double FROM = 0.85;
	public static final long DURATION_NANOS = 200_000_000L;

	private long start;
	private boolean running;

	public void start(long nanos) {
		this.start = nanos;
		this.running = true;
	}

	/** Ends it at once, at full zoom: the player scrolled, dragged, or a test placed the view. */
	public void cancel() {
		this.running = false;
	}

	public boolean running(long nanos) {
		if (this.running && nanos - this.start >= DURATION_NANOS) {
			this.running = false;
		}
		return this.running;
	}

	/** What to multiply the zoom by at {@code nanos}: {@link #FROM} at the start, exactly 1 once over. */
	public double factor(long nanos) {
		if (!this.running(nanos)) {
			return 1;
		}
		double t = Math.max(0, (nanos - this.start) / (double) DURATION_NANOS);
		double eased = 1 - Math.pow(1 - t, 3);
		return FROM + (1 - FROM) * eased;
	}
}
