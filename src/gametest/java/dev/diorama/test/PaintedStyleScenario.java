package dev.diorama.test;

import dev.diorama.*;
import dev.diorama.bake.TileKey;
import dev.diorama.render.MapTile;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * The render styles on the block grid, close up: a village and a coast in each of them, plus the painted village further out, on the minimap and at night. Switching must
 * keep the map. Kept short: each capture is one settled view, and quick with
 * DIORAMA_RENDER_DISTANCE=8, since everything is looked at near the player.
 */
final class PaintedStyleScenario {
    private static final double NEAR = 3.2;
    private static final double MID = 1.4;

    static void run(ClientGameTestContext context, TestSingleplayerContext world) {
        Settings.RenderStyle original = context.computeOnClient(mc -> Settings.get().renderStyle);
        EnvironmentLighting.Mode lighting = context.computeOnClient(mc -> Settings.get().lighting);
        world.getServer().runCommand("time set noon");
        // Daylight for every capture but the night one, however long the bakes take.
        context.runOnClient(mc -> { Settings.get().lighting = EnvironmentLighting.Mode.DAY; Settings.get().save(); });
        try {
            BlockPos village = world.getServer().computeOnServer(server ->
                server.overworld().findNearestMapStructure(StructureTags.VILLAGE, BlockPos.ZERO, 100, false));
            if (village == null) throw new AssertionError("No village near spawn");
            visit(context, world, village);
            for (Settings.RenderStyle style : Settings.RenderStyle.values()) {
                use(context, style);
                capture(context, "village-" + name(style), NEAR);
            }

            // Switching keeps the visible map while the new look is prepared.
            MapTile before = context.computeOnClient(mc -> playerTile());
            use(context, Settings.RenderStyle.OUTLINED);
            use(context, Settings.RenderStyle.PAINTED);
            context.runOnClient(mc -> {
                if (!MapStyle.get().painted()) throw new AssertionError("Painted setting did not reach the map style");
                MapTile after = playerTile();
                if (before != null && (after == null || !after.hasCoverage())) throw new AssertionError("Painted refresh lost coverage");
            });
            capture(context, "village-painted-mid", MID);

            context.setScreen(() -> null);
            context.waitTicks(20);
            context.takeScreenshot("painted-minimap");

            //? if >=26.3 && fabric {
            if (EnvironmentLighting.supported()) {
                context.runOnClient(mc -> { Settings.get().lighting = EnvironmentLighting.Mode.NIGHT; Settings.get().save(); });
                capture(context, "village-painted-night", MID);
                context.runOnClient(mc -> { Settings.get().lighting = EnvironmentLighting.Mode.DAY; Settings.get().save(); });
            }
            //?}

            BlockPos coast = world.getServer().computeOnServer(server -> {
                var found = server.overworld().findClosestBiome3d(biome -> biome.is(BiomeTags.IS_BEACH), village, 4000, 32, 64);
                return found == null ? null : found.getFirst();
            });
            if (coast != null) {
                visit(context, world, coast);
                for (Settings.RenderStyle style : Settings.RenderStyle.values()) {
                    use(context, style);
                    capture(context, "coast-" + name(style), NEAR);
                }
            } else {
                dev.diorama.Diorama.LOGGER.warn("No beach near the village; skipping the coast captures");
            }
        } finally {
            context.runOnClient(mc -> {
                Settings.get().renderStyle = original;
                Settings.get().lighting = lighting;
                Settings.get().save();
            });
            context.setScreen(() -> null);
        }
    }

    private static String name(Settings.RenderStyle style) {
        return style.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static void visit(ClientGameTestContext context, TestSingleplayerContext world, BlockPos at) {
        int surface = world.getServer().computeOnServer(server ->
            server.overworld().getChunk(at).getHeight(Heightmap.Types.MOTION_BLOCKING, at.getX() & 15, at.getZ() & 15));
        world.getServer().runCommand("tp @a " + at.getX() + " " + (surface + 1) + " " + at.getZ());
        context.waitTicks(40);
        context.waitFor(mc -> MapSession.get() != null && MapSession.get().map().chunkCount() > DioramaClientTest.LOADED_CHUNKS, 2400);
        settle(context, 20);
    }

    private static void use(ClientGameTestContext context, Settings.RenderStyle style) {
        context.runOnClient(mc -> { Settings.get().renderStyle = style; Settings.get().save(); });
        context.waitTicks(2);
        settle(context, 10);
    }

    private static void capture(ClientGameTestContext context, String name, double zoom) {
        context.setScreen(MapScreen::new);
        context.runOnClient(mc -> {
            var p = mc.player;
            if (mc.gui.screen() instanceof MapScreen screen && p != null) {
                screen.lookAt(Projection.u(p.getX(), p.getZ()), Projection.worldV(p.getX(), p.getY(), p.getZ()), zoom, TileKey.SURFACE);
            }
        });
        settle(context, 10);
        context.takeScreenshot("painted-" + name);
        context.setScreen(() -> null);
    }

    private static MapTile playerTile() {
        var p = net.minecraft.client.Minecraft.getInstance().player;
        double u = Projection.u(p.getX(), p.getZ());
        double v = Projection.worldV(p.getX(), p.getY(), p.getZ());
        return MapSession.get().map().peek(new TileKey(TileKey.SURFACE, (int) Math.floor(u / Projection.TILE_UNITS), (int) Math.floor(v / Projection.TILE_UNITS)));
    }

    /** Waits until no tile has been baking for a while. */
    private static void settle(ClientGameTestContext context, int minTicks) {
        context.waitTicks(minTicks);
        int quiet = 0;
        for (int i = 0; i < 1200 && quiet < 20; i++) {
            context.waitTick();
            boolean baking = context.computeOnClient(mc -> MapSession.get() != null && MapSession.get().map().bakingCount() > 0);
            quiet = baking ? 0 : quiet + 1;
        }
    }
}
