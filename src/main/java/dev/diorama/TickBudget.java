package dev.diorama;
import java.util.function.LongSupplier;

/** Bounds expensive client-thread work by both elapsed time and count. Always allows one job. */
final class TickBudget {
	private final int limit;
	private final long nanos, start;
	private final LongSupplier clock;
	private int jobs;
	TickBudget(int limit, long nanos, LongSupplier clock) {
		this.limit = limit; this.nanos = nanos; this.clock = clock; this.start = clock.getAsLong();
	}
	boolean available() { return this.jobs < this.limit && (this.jobs == 0 || this.clock.getAsLong() - this.start < this.nanos); }
	void completed() { this.jobs++; }
}
