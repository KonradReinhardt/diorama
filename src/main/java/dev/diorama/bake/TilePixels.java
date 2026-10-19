package dev.diorama.bake;

import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/** One indivisible terrain result. Night alpha records availability, never exploration. */
public record TilePixels(int[] day, int @Nullable [] night) {
    public TilePixels {
        if (night != null && night.length != day.length) throw new IllegalArgumentException("Mismatched lighting planes");
    }

    public long bytes() { return (long) day.length * Integer.BYTES * (night == null ? 1 : 2); }

    public boolean samePixels(@Nullable TilePixels other) {
        return other != null && Arrays.equals(day, other.day) && Arrays.equals(night, other.night);
    }
}
