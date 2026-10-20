package dev.diorama.test;

import dev.diorama.*;
import dev.diorama.bake.TileKey;
import dev.diorama.render.*;
import java.util.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/** No frame may expose only part of an outline/depth revision, including a resident overview. */
final class AtomicAppearanceScenario {
    record Probe(TileKey key, MapTile tile, long uploads) {}
    static void run(ClientGameTestContext context, TestSingleplayerContext world) {
        var renderStyle = Settings.get().renderStyle;
        boolean shading = Settings.get().depthShading, grid = Settings.get().chunkGrid;
        world.getServer().runCommand("tick freeze");
        context.setScreen(MapScreen::new);
        try {
            context.waitFor(mc -> {
                var p = mc.player;
                return MapViewport.ready(MapSession.get().map(), new MapViewport.View(0, 0, 384, 256,
                    Projection.u(p.getX(), p.getZ()), Projection.worldV(p.getX(), p.getY(), p.getZ()), 2, TileKey.SURFACE));
            }, 2400);
            context.waitTicks(120);
            context.waitFor(mc -> MapSession.get().map().bakingCount() == 0, 2400);
            List<Probe> probes = context.computeOnClient(mc -> {
                var p = mc.player;
                int u = (int) Math.floor(Projection.u(p.getX(), p.getZ()) / Projection.TILE_UNITS);
                int v = (int) Math.floor(Projection.worldV(p.getX(), p.getY(), p.getZ()) / Projection.TILE_UNITS);
                List<Probe> result = new ArrayList<>();
                for (int du = -1; du <= 1; du++) for (int dv = -1; dv <= 1; dv++) {
                    TileKey key = new TileKey(TileKey.SURFACE, u + du, v + dv);
                    var tile = MapSession.get().map().peek(key);
                    if (tile != null && !tile.isEmpty()) result.add(new Probe(key, tile, tile.uploadCount()));
                }
                if (result.size() < 2) throw new AssertionError("Need multiple visible tiles to detect a partial refresh");
                return result;
            });
            TileKey overview = probes.getFirst().key().parent().parent().parent();
            context.waitTicks(80);
            context.waitFor(mc -> MapSession.get().map().want(overview, 0) != null, 2400);
            context.waitTicks(20);
            for (int change : new int[]{0, 1, 2}) {
                List<Probe> before = context.computeOnClient(mc -> probes.stream()
                    .map(p -> new Probe(p.key(), p.tile(), p.tile().uploadCount())).toList());
                long revision = context.computeOnClient(mc -> MapSession.get().map().appearanceVersion());
                long jobs = context.computeOnClient(mc -> MapSession.get().map().submittedJobs());
                context.runOnClient(mc -> {
                    if (change == 1) Settings.get().depthShading = !Settings.get().depthShading;
                    else if (change == 2) Settings.get().chunkGrid = !Settings.get().chunkGrid;
                    else Settings.get().renderStyle = Settings.get().renderStyle == Settings.RenderStyle.OUTLINED ? Settings.RenderStyle.PLAIN : Settings.RenderStyle.OUTLINED;
                    Settings.get().save();
                });
                context.waitTicks(1);
                context.waitFor(mc -> {
                    var map = MapSession.get().map();
                    if (map.refreshingAppearance()) {
                        // A view can request a cache miss while the old map stays visible.
                        map.want(new TileKey(TileKey.SURFACE, 1000, -1000), 0);
                        if (map.submittedJobs() - jobs > 256) throw new AssertionError("Staged cache misses were baked repeatedly");
                        for (Probe p : before) if (p.tile().uploadCount() != p.uploads())
                            throw new AssertionError("A partial appearance revision reached the GPU: " + p.key());
                        return false;
                    }
                    if (map.appearanceVersion() == revision) return false;
                    if (map.appearanceVersion() != revision + 1) throw new AssertionError("Multiple commits for one setting");
                    for (Probe p : before) {
                        if (map.peek(p.key()) != p.tile() || p.tile().uploadCount() != p.uploads() + 1 || !p.tile().hasCoverage())
                            throw new AssertionError("Revision did not replace every tile together: " + p.key());
                    }
                    return true;
                }, 4800);
            }
            context.takeScreenshot("atomic-appearance-outline-and-shading");
        } finally {
            context.runOnClient(mc -> {
                Settings.get().renderStyle = renderStyle;
                Settings.get().depthShading = shading;
                Settings.get().chunkGrid = grid;
                Settings.get().save();
            });
            world.getServer().runCommand("tick unfreeze");
            context.setScreen(() -> null);
        }
    }
}
