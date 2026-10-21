package dev.diorama.test;

import dev.diorama.MapScreen;
import dev.diorama.MapSession;
import dev.diorama.Projection;
import dev.diorama.Settings;
import dev.diorama.bake.TileKey;
import dev.diorama.render.MapViewport;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * The clouds over the world map, for looking at: up close, where their pixels lie on the blocks'
 * faces, at the usual zoom, and zoomed out, in clear weather and in rain, which spreads them over
 * most of the map; then with them off. Opt-in with {@code DIORAMA_TESTS=clouds}.
 */
final class MapCloudsScenario {
	private MapCloudsScenario() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		boolean was = context.computeOnClient(mc -> Settings.get().mapClouds);
		try {
			context.runOnClient(mc -> {
				Settings.get().mapClouds = true;
				Settings.get().save();
			});
			context.setScreen(MapScreen::new);
			for (String weather : new String[] {"clear", "rain"}) {
				world.getServer().runCommand("weather " + weather);
				context.waitTicks(weather.equals("rain") ? 200 : 20);
				for (double zoom : new double[] {4, 1, 0.25}) {
					look(context, zoom);
					context.takeScreenshot("clouds-" + weather + "-" + zoom);
				}
				if (weather.equals("clear")) {
					// The same view a moment later without them, to compare against.
					look(context, 1);
					context.runOnClient(mc -> Settings.get().mapClouds = false);
					context.waitTicks(1);
					context.takeScreenshot("clouds-clear-off");
					context.runOnClient(mc -> Settings.get().mapClouds = true);
					context.waitTicks(1);
					context.takeScreenshot("clouds-clear-on");
				}
			}
			context.runOnClient(mc -> {
				Settings.get().mapClouds = false;
				Settings.get().save();
			});
			look(context, 1);
			context.takeScreenshot("clouds-off");
		} finally {
			world.getServer().runCommand("weather clear");
			context.runOnClient(mc -> {
				Settings.get().mapClouds = was;
				Settings.get().save();
			});
			context.setScreen(() -> null);
		}
	}

	/** Centres the map on the player at this zoom and waits for the tiles in view. */
	private static void look(ClientGameTestContext context, double zoom) {
		context.runOnClient(mc -> {
			if (mc.gui.screen() instanceof MapScreen screen) {
				var p = mc.player;
				screen.lookAt(Projection.u(p.getX(), p.getZ()), Projection.worldV(p.getX(), p.getY(), p.getZ()), zoom, TileKey.SURFACE);
			}
		});
		context.waitFor(mc -> mc.gui.screen() instanceof MapScreen screen && screen.view() != null
			&& MapViewport.ready(MapSession.get().map(), screen.view()), 2400);
		context.waitTicks(20);
	}
}
