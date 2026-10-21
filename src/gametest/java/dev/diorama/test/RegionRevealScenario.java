package dev.diorama.test;

import com.mojang.blaze3d.platform.InputConstants;
import dev.diorama.MapScreen;
import dev.diorama.MapSession;
import dev.diorama.Projection;
import dev.diorama.RegionReveal;
import dev.diorama.bake.TileKey;
import dev.diorama.render.MapTile;
import dev.diorama.render.MapViewport;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

/**
 * A region far from anything explored, picked on the world map through its right-click menu, is
 * loaded (generated) by the integrated server and drawn: the region picked is the one between the
 * two corners clicked, and its middle goes from unexplored to covered. Opt-in with
 * {@code DIORAMA_TESTS=reveal-region}; 26.3 only.
 */
final class RegionRevealScenario {
	/** How far east of the player the region starts, in chunks: well past the render distance. */
	private static final int AWAY = 48;
	private static final int SIDE = 4;

	private RegionRevealScenario() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		RegionReveal.Region expected = context.computeOnClient(mc -> {
			int cx = (mc.player.getBlockX() >> 4) + AWAY, cz = mc.player.getBlockZ() >> 4;
			return new RegionReveal.Region(cx, cz, cx + SIDE - 1, cz + SIDE - 1);
		});
		context.setScreen(MapScreen::new);
		try {
			context.waitFor(mc -> ((MapScreen) mc.gui.screen()).view() != null && !((MapScreen) mc.gui.screen()).opening(), 200);
			context.runOnClient(mc -> {
				if (!RegionReveal.allowed(MapSession.get())) {
					throw new AssertionError("Revealing isn't offered in a creative singleplayer world");
				}
				double[] middle = middle(mc, expected);
				((MapScreen) mc.gui.screen()).lookAt(middle[0], middle[1], 2.0, TileKey.SURFACE);
			});
			waitReady(context);
			if (context.computeOnClient(mc -> coverage(mc, expected)) > 0) {
				throw new AssertionError("The region was explored before it was revealed");
			}
			context.takeScreenshot("reveal-01-unexplored");

			// Right-click one corner, pick the entry, then click the opposite corner.
			double[] first = cornerOnScreen(context, expected.minX(), expected.minZ());
			context.getInput().setCursorPos(first[0], first[1]);
			context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_RIGHT);
			context.waitTicks(3);
			clickWidget(context, Component.translatable("diorama.menu.reveal").getString());
			if (!context.computeOnClient(mc -> ((MapScreen) mc.gui.screen()).pickingReveal())) {
				throw new AssertionError("The menu entry didn't start picking a region");
			}
			double[] second = cornerOnScreen(context, expected.maxX(), expected.maxZ());
			context.getInput().setCursorPos(second[0], second[1]);
			context.waitTicks(3);
			context.takeScreenshot("reveal-02-picking");
			context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
			context.waitTicks(2);
			RegionReveal.Region started = context.computeOnClient(mc -> RegionReveal.region());
			if (!expected.equals(started)) {
				throw new AssertionError("Revealing " + started + ", not the region clicked, " + expected);
			}
			long start = System.nanoTime();
			context.waitFor(mc -> RegionReveal.region() == null, 2400);
			dev.diorama.Diorama.LOGGER.info("REVEAL-REGION {} chunks loaded in {} ms", SIDE * SIDE, (System.nanoTime() - start) / 1_000_000);
			context.runOnClient(mc -> {
				for (int cx = expected.minX(); cx <= expected.maxX(); cx++) {
					for (int cz = expected.minZ(); cz <= expected.maxZ(); cz++) {
						if (MapSession.get().map().chunk(cx, cz) == null) throw new AssertionError("Chunk " + cx + ", " + cz + " never reached the map");
					}
				}
			});
			context.waitFor(mc -> coverage(mc, expected) > 0, 1200);
			BookMapScenario.settle(context);
			context.takeScreenshot("reveal-03-revealed");
		} finally {
			context.runOnClient(mc -> RegionReveal.cancel());
			context.setScreen(() -> null);
		}
	}

	/** The map point {u, v} at the middle of the region, on the player's height. */
	private static double[] middle(net.minecraft.client.Minecraft mc, RegionReveal.Region region) {
		double x = (region.minX() + region.maxX() + 1) * 8.0, z = (region.minZ() + region.maxZ() + 1) * 8.0;
		return new double[] {Projection.u(x, z), Projection.worldV(x, mc.player.getBlockY(), z)};
	}

	/** How explored the coverage cell at the region's middle is, from 0 to 1; 0 while its tile isn't baked. */
	private static float coverage(net.minecraft.client.Minecraft mc, RegionReveal.Region region) {
		double[] middle = middle(mc, region);
		int tu = (int) Math.floor(middle[0] / Projection.TILE_UNITS), tv = (int) Math.floor(middle[1] / Projection.TILE_UNITS);
		MapTile tile = MapSession.get().map().peek(new TileKey(TileKey.SURFACE, tu, tv));
		if (tile == null) {
			return 0;
		}
		double cell = (double) Projection.TILE_UNITS / Projection.COVERAGE_CELLS;
		return tile.coverage((int) ((middle[0] - tu * Projection.TILE_UNITS) / cell), (int) ((middle[1] - tv * Projection.TILE_UNITS) / cell));
	}

	/** The middle of a chunk on screen, in window pixels, on the player's height. */
	private static double[] cornerOnScreen(ClientGameTestContext context, int chunkX, int chunkZ) {
		return context.computeOnClient(mc -> {
			MapViewport.View view = ((MapScreen) mc.gui.screen()).view();
			double x = chunkX * 16 + 8, z = chunkZ * 16 + 8;
			double scale = mc.getWindow().getGuiScale();
			return new double[] {view.screenX(Projection.u(x, z)) * scale, view.screenY(Projection.worldV(x, mc.player.getBlockY(), z)) * scale};
		});
	}

	private static void waitReady(ClientGameTestContext context) {
		context.waitFor(mc -> MapViewport.ready(MapSession.get().map(), ((MapScreen) mc.gui.screen()).view()), 2400);
		BookMapScenario.settle(context);
	}

	private static void clickWidget(ClientGameTestContext context, String label) {
		double[] at = context.computeOnClient(mc -> {
			double scale = mc.getWindow().getGuiScale();
			for (var child : mc.gui.screen().children()) {
				if (child instanceof AbstractWidget widget && widget.visible && widget.getMessage().getString().equals(label)) {
					return new double[] {(widget.getX() + widget.getWidth() / 2.0) * scale, (widget.getY() + widget.getHeight() / 2.0) * scale};
				}
			}
			throw new AssertionError("No widget " + label);
		});
		context.getInput().setCursorPos(at[0], at[1]);
		context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTicks(2);
	}
}
