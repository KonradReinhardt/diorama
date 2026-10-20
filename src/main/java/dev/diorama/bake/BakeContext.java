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
    // 3: one ray per pixel on the block grid, and outlines from the faces' own geometry.
    private static final int LOOK_REVISION = 3;

    /**
     * Identifies how baked pixels look: tiles saved under another value are redrawn when shown.
     * Quality doesn't count: every pixel shows one face, so both qualities bake the same pixels.
     */
    public int appearance() {
        return java.util.Objects.hash(LOOK_REVISION, this.style.looksHash(), this.direction);
    }

    public double zScale() { return Projection.zScale(this.style.pitchDegrees()); }
    public double yScale() { return Projection.yScale(this.style.pitchDegrees()); }
    public double zAt(double v, double y) { return (v + y * yScale()) / zScale(); }
}
