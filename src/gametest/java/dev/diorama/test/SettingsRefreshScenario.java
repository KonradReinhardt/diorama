package dev.diorama.test;

import dev.diorama.*;
import dev.diorama.bake.TileKey;
import dev.diorama.render.MapTile;
import dev.diorama.render.MapViewport;
import java.util.List;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/** Appearance changes replace pixels in place and never blank exploration coverage. */
final class SettingsRefreshScenario {
    static void run(ClientGameTestContext context) {
        context.setScreen(MapScreen::new);
        context.waitFor(mc -> {
            var p = mc.player;
            var view = new MapViewport.View(0, 0, 128, 128, Projection.u(p.getX(), p.getZ()),
                Projection.worldV(p.getX(), p.getY(), p.getZ()), 2, TileKey.SURFACE);
            return MapViewport.ready(MapSession.get().map(), view);
        }, 2400);
        List<Consumer<Settings>> changes = List.of(
            s -> s.compass = !s.compass,
            s -> s.radarPlayers = !s.radarPlayers,
            s -> s.roundedFog = !s.roundedFog,
            s -> s.outlines = !s.outlines,
            s -> s.quality = s.quality == Settings.Quality.FANCY ? Settings.Quality.FAST : Settings.Quality.FANCY,
            s -> s.depthShading = !s.depthShading
        );
        for (var change : changes) {
            var probe = context.computeOnClient(mc -> {
                double u = Projection.u(mc.player.getX(), mc.player.getZ());
                double v = Projection.worldV(mc.player.getX(), mc.player.getY(), mc.player.getZ());
                var key = new TileKey(TileKey.SURFACE, (int) Math.floor(u / Projection.TILE_UNITS), (int) Math.floor(v / Projection.TILE_UNITS));
                MapTile tile = MapSession.get().map().peek(key);
                if (tile == null || !tile.hasCoverage()) throw new AssertionError("No coverage before settings change");
                MapSession.get().map().seen().markSeen(-120, -40, -120);
                return new Probe(key, tile);
            });
            context.runOnClient(mc -> { change.accept(Settings.get()); Settings.get().save(); });
            context.waitTicks(2);
            context.runOnClient(mc -> {
                if (MapSession.get().map().peek(probe.key()) != probe.tile() || !probe.tile().hasCoverage())
                    throw new AssertionError("A settings change discarded the visible map or fog coverage");
                if (!MapSession.get().map().seen().isSeen(-120, -40, -120))
                    throw new AssertionError("A settings change reset exploration progress");
            });
            context.runOnClient(mc -> { change.accept(Settings.get()); Settings.get().save(); });
            context.waitTicks(2);
        }
        context.takeScreenshot("settings-refresh-preserved-map");
        context.setScreen(() -> null);
    }
    private record Probe(TileKey key, MapTile tile) {}
}
