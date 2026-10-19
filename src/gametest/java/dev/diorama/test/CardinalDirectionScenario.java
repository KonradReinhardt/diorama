package dev.diorama.test;

import dev.diorama.*;
import dev.diorama.bake.*;
import dev.diorama.render.MapViewport;
import dev.diorama.waypoint.Waypoint;
import dev.diorama.waypoint.WaypointOverlay;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;

/** End-to-end coverage of rotated terrain, picking, markers, settings and view retirement. */
final class CardinalDirectionScenario {
    static void run(ClientGameTestContext context, TestSingleplayerContext world) {
        double[] origin = context.computeOnClient(mc -> new double[] {mc.player.getX(), mc.player.getY(), mc.player.getZ()});
        world.getServer().runCommand("fill -32 200 -32 32 200 32 stone");
        world.getServer().runCommand("fill 2 201 2 4 206 4 gold_block");
        world.getServer().runCommand("tp @a -12.5 201 -9.5");
        context.waitTicks(80);
        MapDirection original = context.computeOnClient(mc -> Settings.get().mapDirection);
        try {
            for (MapDirection direction : MapDirection.values()) {
                context.runOnClient(mc -> {
                    Settings.get().mapDirection = direction;
                    Settings.get().save();
                });
                context.waitTicks(2);
                context.setScreen(MapScreen::new);
                context.waitFor(mc -> {
                    double u = Projection.u(-12.5, -9.5), v = Projection.worldV(-12.5, 201, -9.5);
                    return MapViewport.ready(MapSession.get().map(), new MapViewport.View(0, 0, 256, 256, u, v, 2, TileKey.SURFACE));
                }, 2400);
                context.runOnClient(mc -> {
                    double u = Projection.u(-12.5, -9.5), v = Projection.worldV(-12.5, 201, -9.5);
                    BlockPos picked = MapPick.standingSpot(MapSession.get().map(), BlockLooks.get(), u, v, TileKey.SURFACE, false);
                    if (!new BlockPos(-13, 201, -10).equals(picked))
                        throw new AssertionError(direction + " picked " + picked + " instead of platform");
                    var view = new MapViewport.View(0, 0, 256, 256, u, v, 2, TileKey.SURFACE);
                    var waypoint = new Waypoint("Rotation", -13, 201, -10, mc.level.dimension().identifier().toString(), 0xFFFFFFFF);
                    var pin = WaypointOverlay.layout(view, List.of(waypoint), true).getFirst();
                    if (Math.abs(pin.x() - 128) > .01 || Math.abs(pin.y() - 128) > .01)
                        throw new AssertionError(direction + " waypoint is displaced: " + pin);
                });
                context.waitTicks(20);
                context.takeScreenshot("direction-" + direction.name().toLowerCase(java.util.Locale.ROOT));
            }
            verifyImmediateSave(context);
        } finally {
            context.runOnClient(mc -> { Settings.get().mapDirection = original; Settings.get().save(); });
            context.setScreen(() -> null);
            world.getServer().runCommand("fill -32 200 -32 32 200 32 air");
            world.getServer().runCommand("fill 2 201 2 4 206 4 air");
            world.getServer().runCommand("tp @a " + origin[0] + " " + origin[1] + " " + origin[2]);
            context.waitTicks(40);
        }
    }
    /** Closing before the debounce must still save chunks no viewport ever requested. */
    private static void verifyImmediateSave(ClientGameTestContext context) {
        java.nio.file.Path folder = context.computeOnClient(mc -> {
            try {
                var root = java.nio.file.Files.createTempDirectory(mc.gameDirectory.toPath(), "pending-save-");
                var level = mc.level;
                var map = new dev.diorama.render.MapLevel(level.getMinY(), level.getMaxY() + 1, ChunkSource.NO_ROOF, root);
                for (int x = -1; x <= 0; x++) for (int z = -1; z <= 0; z++)
                    map.put(dev.diorama.data.ChunkSnapshot.take(level.getChunk(x, z), level), null, -1, -1);
                map.close();
                return root;
            } catch (java.io.IOException failure) { throw new RuntimeException(failure); }
        });
        dev.diorama.render.MapLevel.awaitSaves();
        long pitch = Math.round(MapStyle.get().pitchDegrees());
        String name = (pitch == 60 ? "surface" : "surface-" + pitch)
            + (Projection.direction() == MapDirection.NORTH ? "" : "-" + Projection.direction().name().toLowerCase(java.util.Locale.ROOT));
        var store = new dev.diorama.data.TileStore(folder.resolve(name));
        double u = Projection.u(-12.5, -9.5), v = Projection.worldV(-12.5, 201, -9.5);
        var key = new TileKey(TileKey.SURFACE, (int) Math.floor(u / Projection.TILE_UNITS), (int) Math.floor(v / Projection.TILE_UNITS));
        int[] pixels = store.read(key);
        int px = (int) ((u - key.minU()) * Projection.PIXELS_PER_UNIT);
        int py = (int) ((v - key.minV()) * Projection.PIXELS_PER_UNIT);
        if (pixels == null || pixels[py * Projection.TILE_PIXELS + px] >>> 24 == 0)
            throw new AssertionError("Closing immediately lost a pending surface tile");
        store.close();
    }

}
