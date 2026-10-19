package dev.diorama.render;

/** RGBA8 allocation including every mip; shared by eviction and measurements. */
public final class TextureMemory {
    public static final long BUDGET = 256L * 1024 * 1024;
    private TextureMemory() {}
    public static long bytes(int edge, int levels) {
        long bytes = 0;
        for (int i = 0; i < levels; i++) bytes += (long) (edge >> i) * (edge >> i) * 4;
        return bytes;
    }
}
