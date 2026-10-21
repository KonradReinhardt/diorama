package dev.diorama.test;

import dev.diorama.MapScreen;
import dev.diorama.MapSession;
import dev.diorama.Projection;
import dev.diorama.Settings;
import dev.diorama.bake.Surface;
import dev.diorama.bake.TileKey;
import dev.diorama.render.MapTile;
import dev.diorama.render.MapViewport;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Foam and ripples along a shore near the player: the bakes must record the water's surface, and
 * the world map is captured close up, at the usual zoom and further out, a few frames apart to
 * see it move, and with the effect off to compare. Clouds are off, so they don't hide the shore.
 * Opt-in with {@code DIORAMA_TESTS=coast}.
 */
final class CoastScenario {
	private CoastScenario() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		boolean[] was = context.computeOnClient(mc -> new boolean[] {Settings.get().mapCoast, Settings.get().mapClouds});
		int[] shore = context.computeOnClient(mc -> shoreNear(mc.player.blockPosition()));
		if (shore == null) {
			throw new AssertionError("No shore near the player to look at");
		}
		try {
			context.runOnClient(mc -> {
				Settings.get().mapCoast = true;
				Settings.get().mapClouds = false;
				Settings.get().save();
			});
			context.setScreen(MapScreen::new);
			// The middle of the water's top face, a block above the water block's own height.
			double u = Projection.u(shore[0] + 0.5, shore[2] + 0.5), v = Projection.worldV(shore[0] + 0.5, shore[1] + 1, shore[2] + 0.5);
			look(context, u, v, 4);
			String recorded = context.computeOnClient(mc -> {
				var key = new TileKey(TileKey.SURFACE, (int) Math.floor(u / Projection.TILE_UNITS), (int) Math.floor(v / Projection.TILE_UNITS));
				MapTile tile = MapSession.get().map().peek(key);
				if (tile == null) return "no tile";
				if (!tile.hasSurface()) return "no surface plane";
				int water = 0;
				for (int py = 0; py < Projection.TILE_PIXELS; py++) for (int px = 0; px < Projection.TILE_PIXELS; px++) if ((tile.surface(px, py) & Surface.WATER) != 0) water++;
				// Within two blocks: land beside the shore can stand in front of the exact spot.
				int px = (int) ((u - key.minU()) * Projection.PIXELS_PER_UNIT), py = (int) ((v - key.minV()) * Projection.PIXELS_PER_UNIT);
				int reach = 2 * Projection.PIXELS_PER_UNIT;
				for (int y = Math.max(0, py - reach); y <= Math.min(Projection.TILE_PIXELS - 1, py + reach); y++) {
					for (int x = Math.max(0, px - reach); x <= Math.min(Projection.TILE_PIXELS - 1, px + reach); x++) {
						if ((tile.surface(x, y) & Surface.WATER) != 0) return null;
					}
				}
				return "bits " + tile.surface(px, py) + ", water pixels in tile " + water;
			});
			if (recorded != null) {
				throw new AssertionError("The bake didn't record water at the shore " + shore[0] + "," + shore[1] + "," + shore[2] + ": " + recorded);
			}
			for (int frame = 0; frame < 4; frame++) {
				context.takeScreenshot("coast-4-" + frame);
				context.waitTicks(10);
			}
			look(context, u, v, 2);
			context.takeScreenshot("coast-2");
			look(context, u, v, 1);
			context.takeScreenshot("coast-1");
			context.runOnClient(mc -> Settings.get().mapCoast = false);
			look(context, u, v, 4);
			context.takeScreenshot("coast-4-off");
			tower(context, world);
		} finally {
			context.runOnClient(mc -> {
				Settings.get().mapCoast = was[0];
				Settings.get().mapClouds = was[1];
				Settings.get().save();
			});
			context.setScreen(() -> null);
		}
	}

	/**
	 * A tower on a small island in a pool, high in the air: its top and upper walls hide the water
	 * north of it from the map, and must not count as shore; only the island's edge does.
	 */
	private static void tower(ClientGameTestContext context, TestSingleplayerContext world) {
		int[] at = context.computeOnClient(mc -> new int[] {mc.player.getBlockX() + 30, mc.player.getBlockZ() - 10});
		int x = at[0], z = at[1], y = 150;
		var server = world.getServer();
		try {
			server.runCommand("fill " + (x - 13) + " " + (y - 1) + " " + (z - 13) + " " + (x + 13) + " " + y + " " + (z + 13) + " stone");
			server.runCommand("fill " + (x - 12) + " " + y + " " + (z - 12) + " " + (x + 12) + " " + y + " " + (z + 12) + " water");
			server.runCommand("fill " + (x - 2) + " " + y + " " + (z - 2) + " " + (x + 2) + " " + y + " " + (z + 2) + " grass_block");
			server.runCommand("fill " + (x - 1) + " " + (y + 1) + " " + (z - 1) + " " + (x + 1) + " " + (y + 24) + " " + (z + 1) + " stone_bricks");
			context.waitTicks(100);
			context.runOnClient(mc -> Settings.get().mapCoast = true);
			double u = Projection.u(x + 0.5, z + 0.5), v = Projection.worldV(x + 0.5, y + 25, z + 0.5);
			look(context, u, v, 4);
			context.takeScreenshot("coast-tower");
			String top = context.computeOnClient(mc -> {
				var key = new TileKey(TileKey.SURFACE, (int) Math.floor(u / Projection.TILE_UNITS), (int) Math.floor(v / Projection.TILE_UNITS));
				MapTile tile = MapSession.get().map().peek(key);
				if (tile == null) return "no tile";
				int bits = tile.surface((int) ((u - key.minU()) * Projection.PIXELS_PER_UNIT), (int) ((v - key.minV()) * Projection.PIXELS_PER_UNIT));
				return bits == Surface.KNOWN ? null : "bits " + bits;
			});
			if (top != null) {
				throw new AssertionError("The tower's top should be known land that isn't shore: " + top);
			}
			context.runOnClient(mc -> Settings.get().mapCoast = false);
			context.waitTicks(1);
			context.takeScreenshot("coast-tower-off");
		} finally {
			server.runCommand("fill " + (x - 13) + " " + (y - 1) + " " + (z - 13) + " " + (x + 13) + " " + (y + 24) + " " + (z + 13) + " air");
		}
	}

	/**
	 * The nearest open water beside land within 48 blocks, as {x, surface y, z}, or null: its top
	 * block is water, nothing south of it stands taller (the map would show that instead), and
	 * land is three blocks east.
	 */
	private static int[] shoreNear(BlockPos centre) {
		var level = net.minecraft.client.Minecraft.getInstance().level;
		for (int r = 0; r < 48; r++) {
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
					int x = centre.getX() + dx, z = centre.getZ() + dz;
					int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
					if (!level.getFluidState(new BlockPos(x, y, z)).is(FluidTags.WATER)) continue;
					boolean open = true;
					for (int south = 1; south <= 3; south++) open &= level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z + south) <= y + 1;
					if (!open) continue;
					int landY = level.getHeight(Heightmap.Types.WORLD_SURFACE, x + 3, z) - 1;
					if (!level.getFluidState(new BlockPos(x + 3, landY, z)).is(FluidTags.WATER)) return new int[] {x, y, z};
				}
			}
		}
		return null;
	}

	private static void look(ClientGameTestContext context, double u, double v, double zoom) {
		context.runOnClient(mc -> {
			if (mc.gui.screen() instanceof MapScreen screen) screen.lookAt(u, v, zoom, TileKey.SURFACE);
		});
		context.waitFor(mc -> mc.gui.screen() instanceof MapScreen screen && screen.view() != null
			&& MapViewport.ready(MapSession.get().map(), screen.view()), 2400);
		context.waitTicks(20);
	}
}
