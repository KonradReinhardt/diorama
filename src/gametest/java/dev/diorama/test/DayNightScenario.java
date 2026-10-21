package dev.diorama.test;

import dev.diorama.*;
import dev.diorama.bake.*;
import dev.diorama.data.*;
import dev.diorama.render.*;
import java.util.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/** A real light-engine fixture, plus time-only work and saved-map regression gates. */
final class DayNightScenario {
	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		double[] origin = context.computeOnClient(mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()});
		var originalBlocks = world.getServer().computeOnServer(server -> {
			var blocks = new HashMap<net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState>();
			for (var pos : net.minecraft.core.BlockPos.betweenClosed(-48, 199, -48, 16, 206, 16))
				blocks.put(pos.immutable(), server.overworld().getBlockState(pos));
			return blocks;
		});
		world.getServer().runCommand("fill -48 200 -48 16 200 16 stone");
		world.getServer().runCommand("fill -12 201 -24 -12 205 -8 stone");
		world.getServer().runCommand("fill -32 206 -32 -24 206 -24 oak_leaves[persistent=true]");
		world.getServer().runCommand("fill -30 204 -12 -20 204 -6 stone");
		world.getServer().runCommand("fill -8 199 -32 0 199 -24 stone");
		world.getServer().runCommand("fill -8 200 -32 0 200 -24 water");
		world.getServer().runCommand("fill -4 201 -8 0 204 -4 glass");
		world.getServer().runCommand("setblock -24 201 -16 sea_lantern");
		world.getServer().runCommand("setblock -16 201 -16 torch");
		world.getServer().runCommand("tp @a -20.5 201 -14.5");
		context.waitTicks(100);
		context.setScreen(MapScreen::new);
		ready(context);

		try {
			context.runOnClient(mc -> Settings.get().lighting = EnvironmentLighting.Mode.WORLD);
			for (String time : List.of("noon", "12000", "midnight", "23000")) {
				world.getServer().runCommand("time set " + time);
				context.waitTicks(10);
				context.takeScreenshot("lighting-" + time);
				context.runOnClient(mc -> {
					float daylight = EnvironmentLighting.current(false).daylight();
					if (time.equals("noon") && daylight < .99 || time.equals("midnight") && daylight > .01)
						throw new AssertionError("Wrong timeline weight at " + time + ": " + daylight);
				});
			}

			// Compare the same light-engine scene in both qualities and all four projections.
			List<Long> fullTimes = new ArrayList<>(), patchTimes = new ArrayList<>();
			for (MapDirection direction : MapDirection.values()) {
				for (boolean fast : new boolean[]{true, false}) {
					var scene = snapshot(context, direction, fast);
					var lit = bake(scene, fullTimes);
					verifyPair(lit);
					world.getServer().runCommand("setblock -16 201 -16 air");
					context.waitTicks(30);
					var dark = bake(snapshot(context, direction, fast), fullTimes);
					verifyTorch(scene, lit, dark);
					BitSet blocks = new BitSet();
					double u = direction.u(-16, -16), v = Projection.v(201, direction.depth(-16, -16));
					int bx = (int) ((u - scene.key.minU()) * Projection.PIXELS_PER_UNIT) / TileImage.BLOCK;
					int by = (int) ((v - scene.key.minV()) * Projection.PIXELS_PER_UNIT) / TileImage.BLOCK;
					blocks.set(by * (Projection.TILE_PIXELS / TileImage.BLOCK) + bx);
					long start = System.nanoTime();
					TileImage patch = TileBaker.bakePaired(scene.key, scene.source, scene.looks, blocks, false, null, null, scene.context);
					patchTimes.add(System.nanoTime() - start);
					if (patch.patches().getFirst().nightMips() == null) throw new AssertionError("Patch lost night data");
					if (Arrays.stream(patch.patches().getFirst().mips()[0]).noneMatch(p -> p >>> 24 != 0))
						throw new AssertionError("Lighting benchmark patch contains no terrain");
					world.getServer().runCommand("setblock -16 201 -16 torch");
					context.waitTicks(30);
				}
			}
			Diorama.LOGGER.info("LIGHTING METRICS full p50/p95={} / {} ms; 32px patch p50/p95={} / {} ms",
				percentile(fullTimes, .5), percentile(fullTimes, .95), percentile(patchTimes, .5), percentile(patchTimes, .95));
			ready(context);
			context.waitTicks(200);
			world.getServer().runCommand("tick freeze");
			context.waitTicks(100);
			// Wait for map snapshots, light propagation, overview writes and preload jobs to drain.
			context.waitFor(mc -> MapSession.get().map().bakingCount() == 0
				&& !MapSession.get().map().refreshingAppearance(), 2400);
			context.waitTicks(160);
			var before = counters(context);
			List<Long> frameTimes = new ArrayList<>();
			for (int time = 0; time <= 24000; time += 1000) {
				world.getServer().runCommand("time set " + time);
				context.waitTicks(3);
				context.runOnClient(mc -> frameTimes.add(mc.getFrameTimeNs()));
			}
			var after = counters(context);
			if (before.jobs != after.jobs || before.writes != after.writes || before.coverage != after.coverage
				|| before.snapshots != after.snapshots || before.masks != after.masks)
				throw new AssertionError("Clock created map work: " + before + " -> " + after);
			if (after.gpuBytes > TextureMemory.BUDGET) throw new AssertionError("GPU budget exceeded: " + after.gpuBytes);
			Diorama.LOGGER.info("LIGHTING TIME-ONLY PASS: zero jobs/writes/snapshots/mask/coverage changes; GPU={} bytes; frame p50/p95={} / {} ms",
				after.gpuBytes, percentile(frameTimes, .5), percentile(frameTimes, .95));
			context.runOnClient(mc -> {
				List<Long> snapshots = new ArrayList<>();
				for (long nanos : MapSession.get().snapshotTimes()) snapshots.add(nanos);
				Diorama.LOGGER.info("LIGHTING CAPTURE snapshot p50/p95={} / {} ms; queued tiles={}; bytes written={}",
					percentile(snapshots, .5), percentile(snapshots, .95), MapSession.get().map().queuedTiles(), MapSession.get().map().writtenBytes());
			});
			context.setScreen(() -> new SettingsScreen(null));
			context.waitTicks(2);
			context.takeScreenshot("lighting-settings");
			context.setScreen(MapScreen::new);
			world.getServer().runCommand("time set midnight");
			var reload = context.computeOnClient(mc -> mc.reloadResourcePacks());
			context.waitFor(mc -> reload.isDone(), 2400);
			reload.join();
			ready(context);
			context.waitTicks(40);
			context.takeScreenshot("lighting-after-resource-reload");
			context.setScreen(() -> null);
			world.getServer().runCommand("tick unfreeze");
			for (String dimension : List.of("the_nether", "the_end")) {
				world.getServer().runCommand("execute in minecraft:" + dimension + " run tp @a 0 70 0");
				context.waitFor(mc -> mc.level != null && mc.level.dimension().identifier().getPath().equals(dimension), 2400);
				context.runOnClient(mc -> {
					if (!EnvironmentLighting.DAY.equals(EnvironmentLighting.current(false)))
						throw new AssertionError("Overworld clock affected " + dimension);
				});
			}
			world.getServer().runCommand("execute in minecraft:overworld run tp @a -20.5 201 -14.5");
			context.waitFor(mc -> mc.level != null && mc.level.dimension() == net.minecraft.world.level.Level.OVERWORLD
				&& mc.gui.screen() == null && MapSession.get() != null, 2400);

		} finally {
			world.getServer().runCommand("tick unfreeze");
			world.getServer().runCommand("time set noon");
			world.getServer().computeOnServer(server -> {
				originalBlocks.forEach((pos, state) -> server.overworld().setBlock(pos, state, 3));
				return null;
			});
			world.getServer().runCommand("execute in minecraft:overworld run tp @a " + origin[0] + " " + origin[1] + " " + origin[2]);
			context.runOnClient(mc -> { Settings.get().lighting = EnvironmentLighting.Mode.WORLD; Settings.get().save(); });
			context.setScreen(() -> null);
			context.waitTicks(80);
		}
	}

	private static void ready(ClientGameTestContext context) {
		context.waitFor(mc -> MapViewport.ready(MapSession.get().map(), new MapViewport.View(0, 0, 512, 512,
			Projection.u(-20.5, -14.5), Projection.worldV(-20.5, 201, -14.5), 2, TileKey.SURFACE)), 2400);
	}

	private record Scene(TileKey key, FrozenChunks source, BlockLooks looks, BakeContext context) {}
	private static Scene snapshot(ClientGameTestContext context, MapDirection direction, boolean fast) {
		return context.computeOnClient(mc -> {
			Map<Long, ChunkSnapshot> chunks = new HashMap<>();
			for (int x = -5; x <= 3; x++) for (int z = -5; z <= 3; z++) {
				var snapshot = ChunkSnapshot.take(mc.level.getChunk(x, z), mc.level);
				chunks.put(snapshot.key(), snapshot);
			}
			var bake = new BakeContext(MapStyle.get(), direction, fast);
			double u = direction.u(-16, -16), v = Projection.v(201, direction.depth(-16, -16));
			var key = new TileKey(TileKey.SURFACE, (int) Math.floor(u / Projection.TILE_UNITS), (int) Math.floor(v / Projection.TILE_UNITS));
			return new Scene(key, new FrozenChunks(chunks, mc.level.getMinY(), mc.level.getMaxY() + 1, 207,
				ChunkSource.NO_ROOF, new SeenBlocks()), BlockLooks.get(), bake);
		});
	}

	private static TileImage bake(Scene scene, List<Long> times) {
		long start = System.nanoTime();
		TileImage image = TileBaker.bakePaired(scene.key, scene.source, scene.looks, null, false, null, null, scene.context);
		times.add(System.nanoTime() - start);
		return image;
	}
	private static void verifyPair(TileImage image) {
		if (image.nightMips() == null) throw new AssertionError("Missing night endpoint");
		int known = 0;
		for (int i = 0; i < image.mips()[0].length; i++) {
			if ((image.mips()[0][i] >>> 24) != (image.nightMips()[0][i] >>> 24)) throw new AssertionError("Night changed exploration coverage");
			if (image.mips()[0][i] >>> 24 != 0) known++;
		}
		if (known < 1000) throw new AssertionError("Fixture missing terrain");
	}
	private static void verifyTorch(Scene scene, TileImage lit, TileImage dark) {
		int changed = 0;
		for (int i = 0; i < lit.nightMips()[0].length; i++) {
			int a = lit.nightMips()[0][i], b = dark.nightMips()[0][i];
			if ((a >>> 24) != (b >>> 24)) throw new AssertionError("Torch changed exploration");
			if (brightness(a) > brightness(b) + 5) changed++;
		}
		if (changed < 16) throw new AssertionError("Torch did not illuminate neighboring surface pixels: " + changed);
		if (scene.context.direction() == MapDirection.NORTH) {
			int neighbor = pixelAt(scene, -16.5, 201, -16.5);
			if (brightness(lit.nightMips()[0][neighbor]) <= brightness(dark.nightMips()[0][neighbor]) + 30)
				throw new AssertionError("Torch light failed to cross the diagonal chunk corner");
			int wall = pixelAt(scene, -10.5, 201, -16.5);
			if (brightness(lit.nightMips()[0][wall]) > brightness(dark.nightMips()[0][wall]) + 3)
				throw new AssertionError("Torch light leaked through the tall wall");
		}
	}
	private static int pixelAt(Scene scene, double x, double y, double z) {
		double u = scene.context.direction().u(x, z), v = Projection.v(y, scene.context.direction().depth(x, z));
		return (int) ((v - scene.key.minV()) * Projection.PIXELS_PER_UNIT) * Projection.TILE_PIXELS
			+ (int) ((u - scene.key.minU()) * Projection.PIXELS_PER_UNIT);
	}
	private static int brightness(int abgr) { return (abgr & 255) + (abgr >> 8 & 255) + (abgr >> 16 & 255); }
	private record Counters(long jobs, long writes, long coverage, long gpuBytes, long snapshots, long masks) {}
	private static Counters counters(ClientGameTestContext context) {
		return context.computeOnClient(mc -> {
			var map = MapSession.get().map();
			return new Counters(map.submittedJobs(), map.tileWrites(), map.coverageVersion(), map.gpuBytes(), MapSession.get().snapshotCount(), MapViewport.maskBuilds());
		});
	}
	private static double percentile(List<Long> values, double fraction) {
		values.sort(Long::compare);
		return values.get(Math.min(values.size() - 1, (int) (values.size() * fraction))) / 1_000_000.0;
	}
}
