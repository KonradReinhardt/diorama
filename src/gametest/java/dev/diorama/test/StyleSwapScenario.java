package dev.diorama.test;

import dev.diorama.MapScreen;
import dev.diorama.MapSession;
import dev.diorama.Projection;
import dev.diorama.Settings;
import dev.diorama.bake.TileKey;
import dev.diorama.render.MapLevel;
import dev.diorama.render.MapTile;
import dev.diorama.render.MapViewport;
import dev.diorama.ui.MapBook;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.network.chat.Component;

/**
 * How long a render style picked in the world map's book takes to reach the screen: from the
 * click to every tile the map shows changing, and to the whole revision (every resident tile and
 * overview). The tiles on screen must change in the same frame, never as a patchwork, and keep
 * their textures and coverage. Logs {@code STYLE-SWAP} lines; opt-in with
 * {@code DIORAMA_TESTS=style-swap}.
 */
final class StyleSwapScenario {
	private record Probe(TileKey key, MapTile tile, long uploads) {
	}

	/** Tiles kept resident around the player, each way: a 5x5 block covers what is loaded. */
	private static final int WARM = 2;
	/** Places explored before the switch, in blocks from the start, so many tiles are resident. */
	private static final int[][] TRIPS = {{400, 0}, {-400, 0}, {0, 400}, {0, -400}};

	private StyleSwapScenario() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		world.getServer().runCommand("time set noon");
		Settings.RenderStyle style = context.computeOnClient(mc -> Settings.get().renderStyle);
		double[] start = context.computeOnClient(mc -> new double[] {mc.player.getX(), mc.player.getY(), mc.player.getZ()});
		// The same place every run, whatever the spawn, so runs time the same view.
		world.getServer().runCommand("tp @a 8.5 200 8.5");
		context.waitTicks(120);
		double[] home = context.computeOnClient(mc -> new double[] {mc.player.getX(), mc.player.getY(), mc.player.getZ()});
		try {
			context.runOnClient(mc -> { Settings.get().renderStyle = Settings.RenderStyle.OUTLINED; Settings.get().save(); });
			// As after a while of play: tiles of places visited stay resident, off screen, and the
			// switch redraws them from the chunks the world saved.
			for (int[] trip : TRIPS) {
				world.getServer().runCommand("tp @a " + (home[0] + trip[0]) + " 200 " + (home[2] + trip[1]));
				context.waitTicks(120);
				warm(context);
			}
			world.getServer().runCommand("tp @a " + home[0] + " " + home[1] + " " + home[2]);
			world.getServer().runCommand("save-all flush");
			context.waitTicks(40);
			world.getServer().runCommand("tick freeze");
			context.setScreen(MapScreen::new);
			context.waitFor(mc -> !((MapScreen) mc.gui.screen()).opening(), 100);
			context.waitFor(mc -> MapViewport.ready(MapSession.get().map(), ((MapScreen) mc.gui.screen()).view()), 2400);
			warm(context);
			context.runOnClient(mc -> BookMapScenario.book(mc).show(MapBook.Section.LOOK, 0));
			context.waitTicks(10);
			BookMapScenario.settle(context);
			// There and back: the second switch is to a look the map showed a moment ago.
			swap(context, Settings.RenderStyle.PAINTED, "painted");
			BookMapScenario.settle(context);
			swap(context, Settings.RenderStyle.OUTLINED, "outlined");
		} finally {
			context.runOnClient(mc -> { Settings.get().renderStyle = style; Settings.get().save(); });
			world.getServer().runCommand("tick unfreeze");
			world.getServer().runCommand("tp @a " + start[0] + " " + start[1] + " " + start[2]);
			context.setScreen(() -> null);
		}
	}

	/** Asks for the tiles around the player until all are baked, then lets the bakers finish. */
	private static void warm(ClientGameTestContext context) {
		context.waitFor(mc -> {
			var map = MapSession.get().map();
			var p = mc.player;
			int u = (int) Math.floor(Projection.u(p.getX(), p.getZ()) / Projection.TILE_UNITS);
			int v = (int) Math.floor(Projection.worldV(p.getX(), p.getY(), p.getZ()) / Projection.TILE_UNITS);
			boolean all = true;
			for (int du = -WARM; du <= WARM; du++) for (int dv = -WARM; dv <= WARM; dv++)
				all &= map.want(new TileKey(TileKey.SURFACE, u + du, v + dv), MapLevel.OFF_SCREEN + 1) != null;
			return all;
		}, 6000);
		BookMapScenario.settle(context);
	}

	private static void swap(ClientGameTestContext context, Settings.RenderStyle to, String name) {
		List<Probe> probes = context.computeOnClient(mc -> {
			var map = MapSession.get().map();
			List<Probe> list = new ArrayList<>();
			for (TileKey key : MapViewport.tilesIn(map, ((MapScreen) mc.gui.screen()).view())) {
				MapTile tile = map.peek(key);
				if (tile != null && !tile.isEmpty()) list.add(new Probe(key, tile, tile.uploadCount()));
			}
			if (list.size() < 2) throw new AssertionError("Need several tiles on screen to time a style switch");
			return list;
		});
		long revision = context.computeOnClient(mc -> MapSession.get().map().appearanceVersion());
		long gpu = context.computeOnClient(mc -> MapSession.get().map().gpuBytes());
		int resident = context.computeOnClient(mc -> MapSession.get().map().residentTiles());
		long started = System.nanoTime();
		BookMapScenario.clickWidget(context, Component.translatable("diorama.settings.render_style." + name).getString());
		long visible = -1, done = -1;
		for (int tick = 0; tick < 2400 && (visible < 0 || done < 0); tick++) {
			int changed = context.computeOnClient(mc -> {
				var map = MapSession.get().map();
				int n = 0;
				for (Probe p : probes) {
					if (map.peek(p.key()) != p.tile() || !p.tile().hasCoverage())
						throw new AssertionError("Switching the style lost the tile on screen " + p.key());
					if (p.tile().uploadCount() > p.uploads()) n++;
				}
				return n;
			});
			if (changed > 0 && changed < probes.size())
				throw new AssertionError("Only " + changed + " of the " + probes.size() + " tiles on screen changed style");
			long ms = (System.nanoTime() - started) / 1_000_000;
			if (visible < 0 && changed == probes.size()) visible = ms;
			if (done < 0 && context.computeOnClient(mc -> MapSession.get().map().appearanceVersion() > revision
				&& !MapSession.get().map().refreshingAppearance())) done = ms;
			if (visible < 0 || done < 0) context.waitTick();
		}
		if (visible < 0 || done < 0) throw new AssertionError("The " + name + " look never reached the map");
		if (context.computeOnClient(mc -> Settings.get().renderStyle) != to)
			throw new AssertionError("Clicking " + name + " in the book didn't pick it");
		dev.diorama.Diorama.LOGGER.info("STYLE-SWAP {}: {} tiles on screen changed in {} ms; whole revision of {} resident tiles in {} ms ({} MiB on the GPU before)",
			name, probes.size(), visible, resident, done, gpu >> 20);
		context.takeScreenshot("style-swap-" + name);
	}
}
