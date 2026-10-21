package dev.diorama.test;

import com.mojang.blaze3d.platform.InputConstants;
import dev.diorama.MapScreen;
import dev.diorama.MapSession;
import dev.diorama.Projection;
import dev.diorama.Settings;
import dev.diorama.bake.TileKey;
import dev.diorama.region.MapRegion;
import dev.diorama.region.RegionStore;
import dev.diorama.region.RegionTool;
import dev.diorama.region.Ring;
import dev.diorama.render.MapViewport;
import dev.diorama.ui.RegionMode;
import dev.diorama.waypoint.BannerDesign;
import dev.diorama.waypoint.Waypoints;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Regions drawn on the map (docs/design/map-regions.md): a few regions laid on the ground around
 * the player (one with a hole, one C-shaped, two that meet, one with no fill) captured at several
 * zooms and on the minimap; then the tools by the mouse and keys as a player would use them: the
 * lasso, undo and redo, the polygon, the eraser. Opt-in with {@code DIORAMA_TESTS=regions}.
 */
final class RegionsScenario {
	private RegionsScenario() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		boolean[] was = context.computeOnClient(mc -> new boolean[] {Settings.get().mapClouds, Settings.get().minimapClouds});
		List<MapRegion> laid = context.computeOnClient(RegionsScenario::lay);
		try {
			context.runOnClient(mc -> {
				Settings.get().mapClouds = false;
				Settings.get().save();
			});
			context.setScreen(MapScreen::new);
			context.waitFor(mc -> mc.gui.screen() instanceof MapScreen screen && screen.view() != null && !screen.opening(), 200);
			double[] player = context.computeOnClient(mc -> new double[] {
				Projection.u(mc.player.getX(), mc.player.getZ()), Projection.worldV(mc.player.getX(), mc.player.getY(), mc.player.getZ())});
			for (double zoom : new double[] {4, 2, 1, 0.25}) {
				look(context, player[0], player[1], zoom);
				context.takeScreenshot("regions-zoom-" + zoom);
			}
			look(context, player[0], player[1], 2);
			MapRegion pointed = context.computeOnClient(mc -> {
				MapScreen screen = (MapScreen) mc.gui.screen();
				return screen.viewport().regionAt(player[0] - 8, player[1] - 8);
			});
			if (pointed == null || !pointed.name.equals("Home")) {
				throw new AssertionError("The map should find Home just north-west of the player, found " + (pointed == null ? "nothing" : pointed.name));
			}
			tools(context, player);
			minimap(context);
		} finally {
			context.runOnClient(mc -> {
				RegionStore store = RegionStore.get();
				for (MapRegion region : RegionMode.here()) store.commit(region.copy(), null);
				Settings.get().mapClouds = was[0];
				Settings.get().minimapClouds = was[1];
				Settings.get().save();
			});
			context.setScreen(() -> null);
		}
		if (laid.size() != 5) {
			throw new AssertionError("Laid " + laid.size() + " regions");
		}
	}

	/** Regions on the ground around the player, their outlines following the terrain's height. */
	private static List<MapRegion> lay(Minecraft mc) {
		RegionStore store = RegionStore.get();
		String dimension = Waypoints.dimension();
		int px = mc.player.getBlockX(), pz = mc.player.getBlockZ();
		List<MapRegion> out = new ArrayList<>();
		// Home: a square north-west of the player, with a courtyard cut out of it.
		out.add(region(store, dimension, "Home", BannerDesign.Dye.RED, MapRegion.Fill.NORMAL,
			ring(mc, new float[] {px - 40, px + 2, px + 2, px - 40}, new float[] {pz - 40, pz - 40, pz + 2, pz + 2}),
			ring(mc, new float[] {px - 28, px - 18, px - 18, px - 28}, new float[] {pz - 28, pz - 28, pz - 18, pz - 18})));
		// Farms: meets Home along its east side.
		out.add(region(store, dimension, "Farms", BannerDesign.Dye.LIME, MapRegion.Fill.LIGHT,
			ring(mc, new float[] {px + 2, px + 30, px + 30, px + 2}, new float[] {pz - 40, pz - 40, pz - 10, pz - 10})));
		// The bay: a C opening east, south of the player.
		out.add(region(store, dimension, "Bay", BannerDesign.Dye.LIGHT_BLUE, MapRegion.Fill.STRONG,
			ring(mc, new float[] {px - 30, px + 30, px + 30, px - 10, px - 10, px + 30, px + 30, px - 30},
				new float[] {pz + 10, pz + 10, pz + 20, pz + 20, pz + 35, pz + 35, pz + 45, pz + 45})));
		// A border only: no fill.
		out.add(region(store, dimension, "Claim", BannerDesign.Dye.BLACK, MapRegion.Fill.NONE,
			ring(mc, new float[] {px + 40, px + 70, px + 60, px + 38}, new float[] {pz - 5, pz + 5, pz + 40, pz + 30})));
		// A small one inside Farms: drawn over it.
		out.add(region(store, dimension, "Well", BannerDesign.Dye.PURPLE, MapRegion.Fill.STRONG,
			ring(mc, new float[] {px + 12, px + 20, px + 20, px + 12}, new float[] {pz - 30, pz - 30, pz - 22, pz - 22})));
		return out;
	}

	private static MapRegion region(RegionStore store, String dimension, String name, BannerDesign.Dye dye, MapRegion.Fill fill, Ring... rings) {
		MapRegion region = new MapRegion();
		region.name = name;
		region.dimension = dimension;
		region.dye = dye;
		region.fill = fill;
		region.rings.addAll(List.of(rings));
		store.commit(null, region);
		return region;
	}

	/** A ring through these corners, a vertex every two blocks along each side at the ground's height. */
	private static Ring ring(Minecraft mc, float[] cx, float[] cz) {
		List<float[]> points = new ArrayList<>();
		for (int i = 0; i < cx.length; i++) {
			float x0 = cx[i], z0 = cz[i], x1 = cx[(i + 1) % cx.length], z1 = cz[(i + 1) % cx.length];
			int steps = Math.max(1, (int) (Math.hypot(x1 - x0, z1 - z0) / 2));
			for (int s = 0; s < steps; s++) {
				points.add(new float[] {x0 + (x1 - x0) * s / steps, z0 + (z1 - z0) * s / steps});
			}
		}
		float[] x = new float[points.size()], z = new float[points.size()];
		short[] y = new short[points.size()];
		for (int i = 0; i < points.size(); i++) {
			x[i] = points.get(i)[0];
			z[i] = points.get(i)[1];
			y[i] = (short) mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(x[i]), (int) Math.floor(z[i]));
		}
		return new Ring(x, z, y);
	}

	/** The lasso, undo and redo, the polygon and the eraser, by mouse and keys. */
	private static void tools(ClientGameTestContext context, double[] player) {
		// Into region mode through the right-click menu.
		double[] east = onScreen(context, player[0] + 60, player[1] - 40);
		context.getInput().setCursorPos(east[0], east[1]);
		context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_RIGHT);
		context.waitTicks(3);
		clickWidget(context, Component.translatable("diorama.menu.draw_region").getString());
		if (!context.computeOnClient(mc -> ((MapScreen) mc.gui.screen()).regions().active())) {
			throw new AssertionError("The menu entry didn't turn the region tools on");
		}
		context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).regions().pick(RegionTool.Tool.LASSO));
		int before = context.computeOnClient(mc -> RegionMode.here().size());

		// A lasso: a circle 15 map units across, east of the player, dragged around.
		double cu = player[0] + 60, cv = player[1] - 40;
		double[] start = onScreen(context, cu + 15, cv);
		context.getInput().setCursorPos(start[0], start[1]);
		context.getInput().holdMouse(InputConstants.MOUSE_BUTTON_LEFT);
		for (int i = 1; i <= 40; i++) {
			double angle = 2 * Math.PI * i / 40;
			double[] at = onScreen(context, cu + 15 * Math.cos(angle), cv + 15 * Math.sin(angle));
			context.getInput().setCursorPos(at[0], at[1]);
			context.waitTick();
			if (i == 30) context.takeScreenshot("regions-lasso-drawing");
		}
		context.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTicks(3);
		int afterLasso = context.computeOnClient(mc -> RegionMode.here().size());
		if (afterLasso != before + 1) {
			throw new AssertionError("The lasso should have drawn a region: " + before + " then " + afterLasso);
		}
		MapRegion lassoed = context.computeOnClient(mc -> RegionMode.here().get(RegionMode.here().size() - 1));
		double expected = Math.PI * 15 * 15 / Projection.zScale();
		if (Math.abs(lassoed.area() - expected) > 0.15 * expected) {
			throw new AssertionError("The lasso's region covers " + lassoed.area() + " blocks², not about " + expected);
		}
		settle(context);
		context.takeScreenshot("regions-lasso-drawn");

		// Ctrl+Z takes it back, Ctrl+Y brings it again.
		context.getInput().holdKey(InputConstants.KEY_LCONTROL);
		context.getInput().pressKey(InputConstants.KEY_Z);
		context.getInput().releaseKey(InputConstants.KEY_LCONTROL);
		context.waitTicks(2);
		if (context.computeOnClient(mc -> RegionMode.here().size()) != before) {
			throw new AssertionError("Ctrl+Z should take the lasso's region back");
		}
		context.getInput().holdKey(InputConstants.KEY_LCONTROL);
		context.getInput().pressKey(InputConstants.KEY_Y);
		context.getInput().releaseKey(InputConstants.KEY_LCONTROL);
		context.waitTicks(2);
		if (context.computeOnClient(mc -> RegionMode.here().size()) != before + 1) {
			throw new AssertionError("Ctrl+Y should bring the lasso's region back");
		}

		// The polygon: key 2, four corners, then the first again.
		context.getInput().pressKey(InputConstants.KEY_2);
		context.waitTicks(1);
		if (context.computeOnClient(mc -> RegionMode.tool()) != RegionTool.Tool.POLYGON) {
			throw new AssertionError("Key 2 should pick the polygon");
		}
		double pu = player[0] - 70, pv = player[1] - 30;
		double[][] corners = {{pu, pv}, {pu + 25, pv - 5}, {pu + 30, pv + 20}, {pu - 5, pv + 25}, {pu, pv}};
		for (int i = 0; i < corners.length; i++) {
			double[] at = onScreen(context, corners[i][0], corners[i][1]);
			context.getInput().setCursorPos(at[0], at[1]);
			context.waitTicks(i == 3 ? 1 : 12);
			if (i == 4) context.takeScreenshot("regions-polygon-closing");
			context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
			context.waitTicks(12);
		}
		if (context.computeOnClient(mc -> RegionMode.here().size()) != before + 2) {
			throw new AssertionError("Clicking the first corner should close the polygon into a region");
		}
		settle(context);
		context.takeScreenshot("regions-polygon-drawn");

		// The eraser: key 3, point at Farms, then click it.
		context.getInput().pressKey(InputConstants.KEY_3);
		double[] farms = onScreen(context, player[0] + 25, player[1] - 15);
		context.getInput().setCursorPos(farms[0], farms[1]);
		settle(context);
		context.takeScreenshot("regions-eraser-pointing");
		context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTicks(3);
		if (context.computeOnClient(mc -> RegionMode.here().stream().anyMatch(r -> r.name.equals("Farms")))) {
			throw new AssertionError("The eraser should have erased Farms");
		}
		settle(context);
		context.takeScreenshot("regions-erased");
		context.getInput().pressKey(InputConstants.KEY_ESCAPE);
		context.waitTicks(2);
		if (context.computeOnClient(mc -> ((MapScreen) mc.gui.screen()).regions().active())) {
			throw new AssertionError("Escape should leave the region tools");
		}
	}

	private static void minimap(ClientGameTestContext context) {
		context.setScreen(() -> null);
		context.waitTicks(40);
		context.takeScreenshot("regions-minimap");
	}

	private static void look(ClientGameTestContext context, double u, double v, double zoom) {
		context.runOnClient(mc -> {
			if (mc.gui.screen() instanceof MapScreen screen) screen.lookAt(u, v, zoom, TileKey.SURFACE);
		});
		context.waitFor(mc -> mc.gui.screen() instanceof MapScreen screen && screen.view() != null
			&& MapViewport.ready(MapSession.get().map(), screen.view()), 2400);
		settle(context);
	}

	private static void settle(ClientGameTestContext context) {
		context.waitTicks(20);
	}

	/** A map point on screen, in window pixels. */
	private static double[] onScreen(ClientGameTestContext context, double u, double v) {
		return context.computeOnClient(mc -> {
			MapViewport.View view = ((MapScreen) mc.gui.screen()).view();
			double scale = mc.getWindow().getGuiScale();
			return new double[] {view.screenX(u) * scale, view.screenY(v) * scale};
		});
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
