package dev.diorama.render;

/** Shared across catch-up client ticks: at most three paired tile uploads between GUI frames. */
public final class MapUploads {
    private static int remaining = 3;
    private MapUploads() {}
    public static void beginFrame() { remaining = 3; }
    static boolean available() { return !dev.diorama.EnvironmentLighting.supported() || remaining > 0; }
    static void take() { remaining--; }
}
