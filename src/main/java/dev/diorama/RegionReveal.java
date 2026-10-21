package dev.diorama;

import dev.diorama.data.ChunkSnapshot;
import dev.diorama.data.GeneratedChunks;
import dev.diorama.render.MapLevel;
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/**
 * Draws a stretch of the map the player chose and hasn't explored, for a player in creative or
 * allowed cheats in singleplayer. The integrated server loads each chunk, generating it if it
 * never was (see {@link GeneratedChunks}), and its copy goes into the map like a chunk the player
 * saw: its tiles are baked and saved, so the land counts as explored from then on. Seed previews
 * never come into it. Chunks are asked for a few at a time, in rows across the map, and none
 * while the map has many tiles still to save, so memory stays within the map's usual chunk budget.
 * Leaving the world, losing creative and cheats, or the map's own fog of war stops it.
 */
public final class RegionReveal {
	/** The largest side of a region, in chunks: 1024 blocks. */
	public static final int MAX_SIDE = 64;
	/** Chunks the server is asked for at once. */
	private static final int IN_FLIGHT = 12;
	/** Chunks handed to the map per client tick. */
	private static final int PUTS_PER_TICK = 8;
	/** No more chunks are asked for while this many tiles still wait for their background bake. */
	private static final int UNSAVED_LIMIT = 24;

	private static @Nullable RegionReveal current;
	/** How the last region ended, for the map to say once; null after it said so. */
	private static @Nullable Outcome ended;
	private static long endedAt;
	/** An ending older than this goes unsaid: the map wasn't open to see it. */
	private static final long OUTCOME_MS = 4000;

	public enum Outcome { DONE, CANCELLED, DENIED }

	/** Chunk bounds, inclusive. */
	public record Region(int minX, int minZ, int maxX, int maxZ) {
		/** The region between two chunks, its sides clamped to {@link #MAX_SIDE} from the first. */
		public static Region between(int x0, int z0, int x1, int z1) {
			x1 = x0 + Math.max(-(MAX_SIDE - 1), Math.min(MAX_SIDE - 1, x1 - x0));
			z1 = z0 + Math.max(-(MAX_SIDE - 1), Math.min(MAX_SIDE - 1, z1 - z0));
			return new Region(Math.min(x0, x1), Math.min(z0, z1), Math.max(x0, x1), Math.max(z0, z1));
		}

		public int width() {
			return this.maxX - this.minX + 1;
		}

		public int depth() {
			return this.maxZ - this.minZ + 1;
		}
	}

	/** A chunk back from the server; a null snapshot couldn't be had. */
	private record Arrival(@Nullable ChunkSnapshot snapshot) {
	}

	private final MapSession session;
	private final GeneratedChunks source;
	private final Region region;
	/** Chunk keys in the order they're asked for. */
	private final long[] order;
	private int next;
	private int inFlight;
	private int done;
	/** Chunks the server couldn't hand out. */
	private int failed;
	private final ConcurrentLinkedQueue<Arrival> arrived = new ConcurrentLinkedQueue<>();

	private RegionReveal(MapSession session, GeneratedChunks source, Region region) {
		this.session = session;
		this.source = source;
		this.region = region;
		// Rows across the map as the camera faces, so tiles fill a band at a time and get saved
		// while the next band loads.
		MapDirection direction = Projection.direction();
		Long[] keys = new Long[region.width() * region.depth()];
		int i = 0;
		for (int cz = region.minZ; cz <= region.maxZ; cz++) {
			for (int cx = region.minX; cx <= region.maxX; cx++) {
				keys[i++] = ChunkSnapshot.key(cx, cz);
			}
		}
		Arrays.sort(keys, (a, b) -> {
			int ax = (int) (a >> 32), az = (int) (long) a, bx = (int) (b >> 32), bz = (int) (long) b;
			int byDepth = Double.compare(direction.depth(ax, az), direction.depth(bx, bz));
			return byDepth != 0 ? byDepth : Double.compare(direction.u(ax, az), direction.u(bx, bz));
		});
		this.order = Arrays.stream(keys).mapToLong(Long::longValue).toArray();
	}

	/**
	 * Whether the player may reveal regions of this session's map now: {@link GeneratedChunks#allowed},
	 * a saved map, and no fog of war under a roof, where only seen blocks may show.
	 */
	public static boolean allowed(@Nullable MapSession session) {
		if (session == null || !GeneratedChunks.allowed()) {
			return false;
		}
		MapLevel map = session.map();
		return map.hasOverviews() && !(map.roofless() && map.fogOfWar());
	}

	/** Starts revealing a region, replacing one under way. False if the player may not. Client thread. */
	public static boolean start(Region region) {
		MapSession session = MapSession.get();
		Minecraft mc = Minecraft.getInstance();
		if (!allowed(session) || mc.level == null) {
			return false;
		}
		GeneratedChunks source = GeneratedChunks.open(mc.level);
		if (source == null) {
			return false;
		}
		stop(null);
		current = new RegionReveal(session, source, region);
		ended = null;
		Diorama.LOGGER.info("Revealing chunks {}, {} to {}, {} ({} chunks)", region.minX, region.minZ, region.maxX, region.maxZ, current.order.length);
		return true;
	}

	/** Stops the region under way, if any. */
	public static void cancel() {
		stop(Outcome.CANCELLED);
	}

	private static void stop(@Nullable Outcome outcome) {
		RegionReveal reveal = current;
		if (reveal == null) {
			return;
		}
		reveal.source.close();
		current = null;
		if (outcome != null) {
			ended = outcome;
			endedAt = System.currentTimeMillis();
			Diorama.LOGGER.info("Region reveal {} after {} of {} chunks; {} couldn't be had", outcome.name().toLowerCase(java.util.Locale.ROOT),
				reveal.done, reveal.order.length, reveal.failed);
		}
	}

	/** The region under way, or null. */
	public static @Nullable Region region() {
		return current == null ? null : current.region;
	}

	/** How far the region under way is, from 0 to 1, or -1 when none is. */
	public static float progress() {
		RegionReveal reveal = current;
		return reveal == null ? -1 : (float) reveal.done / reveal.order.length;
	}

	/** How the last region ended, if it just did, once: the next call returns null. */
	public static @Nullable Outcome takeOutcome() {
		Outcome outcome = ended;
		ended = null;
		return outcome != null && System.currentTimeMillis() - endedAt < OUTCOME_MS ? outcome : null;
	}

	/** Hands arrived chunks to the map and asks for more. Call every client tick with the live session. */
	static void tick(MapSession session, int playerChunkX, int playerChunkZ) {
		RegionReveal reveal = current;
		if (reveal == null) {
			return;
		}
		if (reveal.session != session) {
			stop(Outcome.CANCELLED);
			return;
		}
		if (reveal.source.denied() || !allowed(session)) {
			stop(Outcome.DENIED);
			return;
		}
		reveal.step(playerChunkX, playerChunkZ);
		if (reveal.next == reveal.order.length && reveal.inFlight == 0) {
			stop(Outcome.DONE);
		}
	}

	private void step(int playerChunkX, int playerChunkZ) {
		MapLevel map = this.session.map();
		Arrival arrival;
		for (int i = 0; i < PUTS_PER_TICK && (arrival = this.arrived.poll()) != null; i++) {
			this.inFlight--;
			this.done++;
			ChunkSnapshot snapshot = arrival.snapshot();
			if (snapshot == null) {
				this.failed++;
			}
			// A chunk the player loaded meanwhile is already on the map, and newer.
			if (snapshot != null && map.chunk(snapshot.chunkX, snapshot.chunkZ) == null) {
				map.put(snapshot, null, playerChunkX, playerChunkZ);
			}
		}
		while (this.inFlight < IN_FLIGHT && this.next < this.order.length && map.unsavedTiles() < UNSAVED_LIMIT) {
			long key = this.order[this.next++];
			int cx = (int) (key >> 32);
			int cz = (int) key;
			if (map.chunk(cx, cz) != null) {
				this.done++;
				continue;
			}
			this.inFlight++;
			this.source.request(cx, cz).whenComplete((snapshot, failure) -> this.arrived.add(new Arrival(failure == null ? snapshot : null)));
		}
	}
}
