package dev.diorama.bake;

import dev.diorama.MapDirection;
import dev.diorama.MapStyle;
import dev.diorama.Projection;

/** Immutable settings for one job, including jobs finishing after a view changes. */
public record BakeContext(MapStyle style, MapDirection direction, boolean fast) {
    public static BakeContext capture() { return new BakeContext(MapStyle.get(), Projection.direction(), TileBaker.fast()); }
    public double sin() { return Math.sin(Math.toRadians(this.style.pitchDegrees())); }
    public double cos() { return Math.cos(Math.toRadians(this.style.pitchDegrees())); }
    public double zAt(double v, double y) { return (v + y * cos()) / sin(); }
}
