package dev.diorama;

import net.minecraft.client.Minecraft;

/** Immutable draw input, read on the client thread. Never invalidates terrain or exploration. */
public record EnvironmentLighting(float daylight, float rain) {
    public static final EnvironmentLighting DAY = new EnvironmentLighting(1, 0);
    public enum Mode { WORLD, DAY, NIGHT }

    public static boolean supported() {
        //? if >=26.3 && fabric {
        return true;
        //?} else {
        /*return false;
        *///?}
    }

    public static EnvironmentLighting current(boolean cave) {
        if (cave || !supported()) return DAY;
        var level = Minecraft.getInstance().level;
        if (level == null || !cycles(false, level.dimensionType().hasCeiling(), level.dimensionType().hasSkyLight(), level.dimensionType().hasFixedTime())) return DAY;
        Mode mode = Settings.get().lighting;
        if (mode == Mode.DAY) return DAY;
        if (mode == Mode.NIGHT) return new EnvironmentLighting(0, 0);
        //? if >=26.3 {
        // Use the dimension's own timeline, including frozen clocks and custom fixed lighting.
        // Vanilla's sky factor ranges from .24 at night to 1 at noon (timeline/day.json).
        float sky = level.environmentAttributes().getDimensionValue(net.minecraft.world.attribute.EnvironmentAttributes.SKY_LIGHT_FACTOR);
        return fromSkyFactor(sky, level.getRainLevel(1), level.getThunderLevel(1));
        //?} else {
        /*return DAY;
        *///?}
    }

    public static EnvironmentLighting fromSkyFactor(float sky, float rain, float thunder) {
        return new EnvironmentLighting(clamp((sky - 0.24F) / 0.76F), clamp(Math.max(rain, thunder)));
    }

    /** Fixed-light dimensions keep their original rendering, even if they expose sky-light data. */
    public static boolean cycles(boolean cave, boolean ceiling, boolean skyLight, boolean fixedTime) {
        return !cave && !ceiling && skyLight && !fixedTime;
    }

    public int params(boolean hasNight) {
        return 0xff000000 | Math.round(clamp(daylight) * 255) << 16
            | Math.round(clamp(rain) * 255) << 8 | (hasNight ? 255 : 0);
    }

    private static float clamp(float x) { return Float.isFinite(x) ? Math.max(0, Math.min(1, x)) : 1; }
}
