package dev.diorama.bake;

import dev.diorama.MapDirection;
import dev.diorama.MapStyle;
import dev.diorama.Projection;

/** Immutable settings for one job, including jobs finishing after a view changes. */
public record BakeContext(MapStyle style, MapDirection direction, boolean fast) {
    public static BakeContext capture() { return new BakeContext(MapStyle.get(), Projection.direction(), TileBaker.fast()); }
    public boolean sameProjection(BakeContext other) {
        return this.direction == other.direction && this.style.pitchDegrees() == other.style.pitchDegrees();
    }
    /** Bump when bakes start drawing the same settings differently, so saved tiles are redrawn. */
    // 2: tiles baked before deep-ocean pixels were refreshed when their neighbours loaded.
    private static final int LOOK_REVISION = 2;

    /** Identifies how baked pixels look: tiles saved under another value are redrawn when shown. */
    public int appearance() {
        return java.util.Objects.hash(LOOK_REVISION, this.style.looksHash(), this.direction, this.fast);
    }

    public double sin() { return Math.sin(Math.toRadians(this.style.pitchDegrees())); }
    public double cos() { return Math.cos(Math.toRadians(this.style.pitchDegrees())); }
    public double zAt(double v, double y) { return (v + y * cos()) / sin(); }
}
