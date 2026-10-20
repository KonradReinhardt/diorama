package dev.diorama.test;

import dev.diorama.*;
import dev.diorama.bake.TileKey;
import dev.diorama.render.MapViewport;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/** Real shaders, private vanilla sampler, draw-only settings, cursor easing and cache stability. */
final class AntiqueFogScenario {
    static void run(ClientGameTestContext context, TestSingleplayerContext world) {
        var original = Settings.get().fogStyle;
        boolean seed = Settings.get().seedPreview;
        world.getServer().runCommand("tick freeze");
        context.setScreen(MapScreen::new);
        try {
            context.waitFor(mc -> ((MapScreen)mc.gui.screen()).view() != null
                && MapViewport.ready(MapSession.get().map(), ((MapScreen)mc.gui.screen()).view()), 2400);
            context.waitTicks(160);
            context.waitFor(mc -> MapSession.get().map().bakingCount() == 0, 2400);
            long jobs = context.computeOnClient(mc -> MapSession.get().map().submittedJobs());
            context.runOnClient(mc -> {
                Settings.get().fogStyle = Settings.FogStyle.ANCIENT_MAP;
                Settings.get().seedPreview = true;
                Settings.get().save();
            });
            context.waitTicks(30);
            context.runOnClient(mc -> {
                if (MapSession.get().map().submittedJobs() != jobs) throw new AssertionError("Fog style rebaked terrain");
            });
            context.takeScreenshot("antique-explored-parchment");

            // Stay inside one preview region, well outside the explored world, to test prediction alone.
            context.runOnClient(mc -> ((MapScreen)mc.gui.screen()).lookAt(Projection.u(6000, 6000),
                Projection.worldV(6000, 64, 6000), .5, TileKey.SURFACE));
            //? if >=26.3 {
            context.waitFor(mc -> {
                var preview = MapSession.get().seedPreview();
                if (preview == null) throw new AssertionError("Vanilla singleplayer preview source unavailable");
                if (preview.unavailable() != null) throw new AssertionError(preview.unavailable());
                return preview.sampleCached(6000, 6000) != 0;
            }, 2400);
            context.waitTicks(40);
            context.takeScreenshot("antique-unexplored-seed-grid");
            context.runOnClient(mc -> {
                var preview = MapSession.get().seedPreview();
                if (MapSession.get().map().isSeen(6000, 64, 6000)) throw new AssertionError("Seed preview marked exploration");
                dev.diorama.Diorama.LOGGER.info("PREVIEW samples={}, total={} ms, worst={} ms, initialization={} ms",
                    preview.samples(), preview.sampleNanos() / 1e6, preview.worstSampleNanos() / 1e6, preview.initializationNanos() / 1e6);
            });
            //?} else {
            /*context.waitTicks(30);
            context.takeScreenshot("antique-unexplored-paper-fallback");
            *///?}

            // Move back to the player and exercise wheel easing through the actual screen frame path.
            context.runOnClient(mc -> {
                var p = mc.player;
                var screen = (MapScreen)mc.gui.screen();
                screen.lookAt(Projection.u(p.getX(), p.getZ()), Projection.worldV(p.getX(), p.getY(), p.getZ()), 2, TileKey.SURFACE);
            });
            context.waitTicks(3);
            var before = context.computeOnClient(mc -> ((MapScreen)mc.gui.screen()).view());
            context.runOnClient(mc -> ((MapScreen)mc.gui.screen()).mouseScrolled(before.width() / 2.0 + 70, before.height() / 2.0 - 40, 0, 3));
            context.waitTicks(1);
            context.runOnClient(mc -> {
                var view = ((MapScreen)mc.gui.screen()).view();
                if (!(view.zoom() > 2 && view.zoom() < 2 * Math.pow(1.25, 3))) throw new AssertionError("Wheel zoom jumped to its final value");
                if (Math.abs((view.centerU() + 70 / view.zoom()) - (before.centerU() + 35)) > .001
                    || Math.abs((view.centerV() - 40 / view.zoom()) - (before.centerV() - 20)) > .001)
                    throw new AssertionError("Smooth zoom lost the cursor anchor");
            });
            context.waitTicks(25);
            context.takeScreenshot("antique-smooth-zoom");
            context.runOnClient(mc -> { Settings.get().fogStyle = Settings.FogStyle.CLOUDS; Settings.get().save(); });
            context.waitTicks(25);
            context.takeScreenshot("clouds-retained-style");
        } finally {
            context.runOnClient(mc -> { Settings.get().fogStyle = original; Settings.get().seedPreview = seed; Settings.get().save(); });
            world.getServer().runCommand("tick unfreeze");
        }
    }
}
