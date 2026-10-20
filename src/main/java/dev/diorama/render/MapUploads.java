package dev.diorama.render;

/** Shared across catch-up client ticks: at most two paired tile uploads between GUI frames. */
public final class MapUploads {
    private static int remaining = 2;
    private MapUploads() {}
    public static void beginFrame() { remaining = 2; }
    static boolean available() { return !dev.diorama.EnvironmentLighting.supported() || remaining > 0; }
    static void take() { remaining--; }
}
