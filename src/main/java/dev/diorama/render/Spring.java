package dev.diorama.render;

/**
 * A value that follows its target like a damped spring, by real time: it eases out toward the
 * target, overshooting a little when underdamped, and stays smooth when the target flips midway,
 * which fixed-length eases can't. Pure, for tests; one per animated thing.
 */
public final class Spring {
	/** Settles in about a fifth of a second without overshoot: fades. */
	public static final double SOFT = 1;
	/** Overshoots by about a tenth before settling: a playful grow. */
	public static final double BOUNCY = 0.55;

	private static final double STEP = 1 / 240.0;
	/** A long frame (a pause, a hitch) counts as this, so the spring never jumps. */
	private static final double LONGEST = 0.1;

	private final double stiffness;
	private final double damping;
	private double value;
	private double velocity;
	private long last = Long.MIN_VALUE;
	private double target;

	/** @param ratio 1 for critical damping, lower to overshoot */
	public Spring(double ratio) {
		this(ratio, 260);
	}

	public Spring(double ratio, double stiffness) {
		this.stiffness = stiffness;
		this.damping = 2 * ratio * Math.sqrt(stiffness);
	}

	/** Moves toward {@code target} by the time since the last call and returns the new value. */
	public double update(double target, long nanos) {
		double dt = this.last == Long.MIN_VALUE ? 0 : Math.min(LONGEST, (nanos - this.last) / 1e9);
		this.last = nanos;
		this.target = target;
		for (; dt > 0; dt -= STEP) {
			double h = Math.min(STEP, dt);
			this.velocity += (this.stiffness * (target - this.value) - this.damping * this.velocity) * h;
			this.value += this.velocity * h;
		}
		if (Math.abs(target - this.value) < 1e-3 && Math.abs(this.velocity) < 1e-2) {
			this.value = target;
			this.velocity = 0;
		}
		return this.value;
	}

	public double value() {
		return this.value;
	}

	/** At rest at {@code target}, and last asked to stay there. */
	public boolean settled(double target) {
		return this.target == target && this.value == target && this.velocity == 0;
	}
}
