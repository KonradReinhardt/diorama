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
        } finally {
            context.runOnClient(mc -> { Settings.get().mapDirection = original; Settings.get().save(); });
            context.setScreen(() -> null);
        }
    }
}
