package dev.diorama.test;

import dev.diorama.*;
import dev.diorama.bake.TileKey;
import dev.diorama.render.MapTile;
import dev.diorama.render.MapViewport;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * A look change reaches tiles whose chunks the map no longer holds, as after reopening a world:
 * they're redrawn from the world's saved chunks, and nothing unexplored appears.
 */
final class WholeMapRefreshScenario {
    private record Probe(TileKey key, MapTile tile, BitSet unknown) {}

    static void run(ClientGameTestContext context, TestSingleplayerContext world) {
        boolean outlines = Settings.get().outlines;
        world.getServer().runCommand("tick freeze");
        world.getServer().runCommand("save-all flush");
        context.setScreen(MapScreen::new);
        try {
            context.waitFor(mc -> ((MapScreen) mc.gui.screen()).view() != null
                && MapViewport.ready(MapSession.get().map(), ((MapScreen) mc.gui.screen()).view()), 2400);
            context.waitFor(mc -> MapSession.get().map().bakingCount() == 0, 2400);
            // Tiles reach the disk a few seconds after their bake.
            context.waitTicks(120);
            List<Probe> probes = context.computeOnClient(mc -> {
                var map = MapSession.get().map();
                double u = Projection.u(mc.player.getX(), mc.player.getZ());
                double v = Projection.worldV(mc.player.getX(), mc.player.getY(), mc.player.getZ());
                var key = new TileKey(TileKey.SURFACE, (int) Math.floor(u / Projection.TILE_UNITS), (int) Math.floor(v / Projection.TILE_UNITS));
                MapTile tile = map.peek(key);
                if (tile == null || !tile.hasCoverage() || tile.isEmpty()) throw new AssertionError("No explored tile at the player");
                List<Probe> list = new ArrayList<>();
                list.add(new Probe(key, tile, unknownCells(tile)));
                // As after reopening the world: nothing loaded can redraw these tiles.
                map.forgetSnapshots();
                return list;
            });
            long revision = context.computeOnClient(mc -> MapSession.get().map().appearanceVersion());
            long started = System.nanoTime();
            context.runOnClient(mc -> { Settings.get().outlines = !outlines; Settings.get().save(); });
            // The new look is published as one revision; only then can the probes vouch for it.
            context.waitFor(mc -> MapSession.get().map().appearanceVersion() > revision
                && probes.stream().allMatch(p -> MapSession.get().map().showsCurrentLook(p.key())), 3600);
            dev.diorama.Diorama.LOGGER.info("WHOLE-MAP-REFRESH probe redrawn in {} ms; {} saved tiles still waiting",
                (System.nanoTime() - started) / 1_000_000, context.computeOnClient(mc -> MapSession.get().map().staleTiles()));
            context.runOnClient(mc -> {
                for (Probe probe : probes) {
                    MapTile tile = MapSession.get().map().peek(probe.key());
                    if (tile == null || !tile.hasCoverage()) throw new AssertionError("Redrawing lost the explored tile " + probe.key());
                    BitSet revealed = (BitSet) probe.unknown().clone();
                    revealed.andNot(unknownCells(tile));
                    if (!revealed.isEmpty()) throw new AssertionError("Redrawing from saved chunks revealed " + revealed.cardinality() + " unexplored cells");
                }
            });
            context.waitTicks(20);
            context.takeScreenshot("whole-map-refresh");
        } finally {
            context.runOnClient(mc -> {
                Settings.get().outlines = outlines;
                Settings.get().save();
                MapSession.get().resnapshotLoaded();
            });
            world.getServer().runCommand("tick unfreeze");
            context.setScreen(() -> null);
        }
    }

    private static BitSet unknownCells(MapTile tile) {
        BitSet unknown = new BitSet();
        for (int y = 0; y < Projection.COVERAGE_CELLS; y++) {
            for (int x = 0; x < Projection.COVERAGE_CELLS; x++) {
                if (tile.coverage(x, y) == 0) unknown.set(y * Projection.COVERAGE_CELLS + x);
            }
        }
        return unknown;
    }
}
