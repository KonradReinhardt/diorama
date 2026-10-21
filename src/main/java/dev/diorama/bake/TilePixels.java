package dev.diorama.bake;

import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/**
 * One indivisible terrain result. Night alpha records availability, never exploration.
 * {@code surface} holds {@link Surface} bits per pixel, or is null where none were recorded.
 */
public record TilePixels(int[] day, int @Nullable [] night, byte @Nullable [] surface) {
    public TilePixels {
        if (night != null && night.length != day.length) throw new IllegalArgumentException("Mismatched lighting planes");
        if (surface != null && surface.length != day.length) throw new IllegalArgumentException("Mismatched surface plane");
    }

    public TilePixels(int[] day, int @Nullable [] night) {
        this(day, night, null);
    }

    /** The same pixels with this surface plane. */
    public TilePixels withSurface(byte @Nullable [] surface) {
        return new TilePixels(this.day, this.night, surface);
    }

    /** What the lighting planes take; the surface plane is saved apart (see TileStore). */
    public long bytes() { return (long) day.length * Integer.BYTES * (night == null ? 1 : 2); }

    public boolean samePixels(@Nullable TilePixels other) {
        return other != null && Arrays.equals(day, other.day) && Arrays.equals(night, other.night) && Arrays.equals(surface, other.surface);
    }
}
