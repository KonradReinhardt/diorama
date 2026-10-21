package dev.diorama.preview;

import java.nio.file.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
import java.util.zip.CRC32;
import org.jspecify.annotations.Nullable;

/** Approximate biome cartography. Never owns chunks, exploration, map tiles or GPU resources. */
public final class SeedPreview implements AutoCloseable {
	public static final int REGION = 4096, COARSE = 16, FINE = 64, DETAIL = 128;
	private static final int MAX_REGIONS = 960;
	/** Each cache holds at most this much sample data, whatever mix of grid sizes it has. */
	private static final long MAX_BYTES = 4L << 20;
	private static final long DISK_LIMIT = 64L << 20;
	public interface Sampler { byte sample(int blockX, int blockZ); }
	public record Source(Sampler sampler, String fingerprint) {}
	public record Key(int x, int z) {}
	public record Bounds(int minX, int minZ, int maxX, int maxZ, boolean fine, boolean detail) {
		public Bounds(int minX, int minZ, int maxX, int maxZ, boolean fine) {
			this(minX, minZ, maxX, maxZ, fine, false);
		}

		/** Grid sizes to fill, coarsest first: the whole view gets each before any gets the next. */
		int[] edges() {
			return detail ? new int[]{COARSE, FINE, DETAIL} : fine ? new int[]{COARSE, FINE} : new int[]{COARSE};
		}

		boolean contains(Key key) {
			return key.x >= Math.floorDiv(minX, REGION) && key.x <= Math.floorDiv(maxX, REGION)
				&& key.z >= Math.floorDiv(minZ, REGION) && key.z <= Math.floorDiv(maxZ, REGION);
		}
	}
	private record Grid(Key key, byte[] data, int edge) {}
	private final LinkedHashMap<Key, Grid> cache = new LinkedHashMap<>(32, .75f, true);
	private long cacheBytes;
	private final ConcurrentLinkedQueue<Grid> completed = new ConcurrentLinkedQueue<>();
	private volatile @Nullable Bounds wanted;
	private volatile long movedAt;
	private volatile boolean closed;
	private volatile long samples, sampleNanos, worstSampleNanos, initializationNanos;
	private volatile @Nullable String unavailable;
	private long revision;
	private long sliceCpu;
	private final Thread worker;

	public SeedPreview(Supplier<@Nullable Source> initialize, @Nullable Path root) {
		worker = new Thread(() -> run(initialize, root), "diorama-seed-preview");
		worker.setDaemon(true); worker.setPriority(Thread.MIN_PRIORITY); worker.start();
	}

	/** Client only: cache hits contain no biome sampling or disk IO. Null pauses hidden fog work. */
	public void request(@Nullable Bounds bounds) {
		if (!Objects.equals(wanted, bounds)) {
			wanted = bounds; movedAt = System.nanoTime();
			LockSupport.unpark(worker);
		}
		Grid grid;
		while ((grid = completed.poll()) != null) {
			Grid previous = cache.put(grid.key, grid);
			cacheBytes += grid.data.length - (previous == null ? 0 : previous.data.length);
			cacheBytes = trim(cache, cacheBytes);
			revision++;
		}
	}

	public byte sampleCached(int x, int z) {
		Grid grid = cache.get(new Key(Math.floorDiv(x, REGION), Math.floorDiv(z, REGION)));
		if (grid == null) return 0;
		int step = REGION / grid.edge;
		return grid.data[Math.floorMod(z, REGION) / step * grid.edge + Math.floorMod(x, REGION) / step];
	}
	/** One cursor per mask rebuild: a region lookup only when crossing a 4096-block boundary. */
	public final class Cursor {
		private int rx = Integer.MIN_VALUE, rz = Integer.MIN_VALUE;
		private @Nullable Grid grid;
		public byte sample(int x, int z) {
			locate(x, z);
			if (grid == null) return 0;
			int step = REGION / grid.edge;
			return grid.data[Math.floorMod(z, REGION) / step * grid.edge + Math.floorMod(x, REGION) / step];
		}

		/** Blocks between the samples of the grid holding this point, or 0 where nothing is cached. */
		public int step(int x, int z) {
			locate(x, z);
			return grid == null ? 0 : REGION / grid.edge;
		}

		private void locate(int x, int z) {
			int nx = Math.floorDiv(x, REGION), nz = Math.floorDiv(z, REGION);
			if (nx != rx || nz != rz) { rx = nx; rz = nz; grid = cache.get(new Key(nx, nz)); }
		}
	}
	public Cursor cursor() { return new Cursor(); }
	public long revision() { return revision; }
	public long samples() { return samples; }
	public long sampleNanos() { return sampleNanos; }
	public long worstSampleNanos() { return worstSampleNanos; }
	public long initializationNanos() { return initializationNanos; }
	public @Nullable String unavailable() { return unavailable; }

	private void run(Supplier<@Nullable Source> initialize, @Nullable Path root) {
		// Worker owns sampler, disk access and its work set; render owns cache and GPU masks.
		LinkedHashMap<Key, Grid> work = new LinkedHashMap<>(32, .75f, true);
		long workBytes = 0;
		try {
			long start = System.nanoTime();
			Source source = initialize.get();
			initializationNanos = System.nanoTime() - start;
			if (source == null) { unavailable = "No compatible singleplayer biome source"; dev.diorama.Diorama.LOGGER.info("Seed cartography off: {}", unavailable); return; }
			Path folder = root == null ? null : root.resolve(source.fingerprint);
			if (folder != null) { Files.createDirectories(folder); prune(root); }
			int writes = 0;
			Bounds published = null;
			while (!closed) {
				Bounds view = wanted;
				if (view == null) { LockSupport.park(); continue; }
				if (System.nanoTime() - movedAt < 250_000_000L) { pause(50_000_000L); continue; }
				List<Key> keys = regions(view);
				if (published != view) {
					for (Key key : keys) {
						Grid cached = work.get(key);
						if (cached != null) completed.add(cached);
					}
					published = view;
				}
				boolean didWork = false;
				// Coarse coverage for the entire visible area precedes any refinement.
				for (int edge : view.edges()) {
					for (Key key : keys) {
						if (closed || wanted != view) break;
						Grid old = work.get(key);
						if (old == null && folder != null) {
							old = read(folder, key);
							if (old != null) { work.put(key, old); workBytes += old.data.length; completed.add(old); }
						}
						if (old != null && old.edge >= edge) continue;
						Grid grid = generate(source.sampler, key, edge, old, view);
						if (grid == null) break;
						Grid replaced = work.put(key, grid); completed.add(grid); didWork = true;
						workBytes += grid.data.length - (replaced == null ? 0 : replaced.data.length);
						workBytes = trim(work, workBytes);
						if (folder != null) {
							write(folder, grid);
							if (++writes % 16 == 0) prune(root);
						}
					}
				}
				if (!didWork && wanted == view) LockSupport.park();
			}
		} catch (Exception error) {
			unavailable = error.getClass().getSimpleName() + ": " + error.getMessage();
			if (!closed) dev.diorama.Diorama.LOGGER.warn("Seed cartography stopped", error);
		} finally { work.clear(); }
	}

	private @Nullable Grid generate(Sampler sampler, Key key, int edge, @Nullable Grid coarse, Bounds view) {
		byte[] data = new byte[edge * edge];
		int step = REGION / edge;
		for (int z = 0; z < edge; z++) for (int x = 0; x < edge; x++) {
			if (closed || wanted != view) return null;
			// Samples sit at grid vertices so a coarser result is reused exactly during refinement.
			if (coarse != null && coarse.edge < edge && edge % coarse.edge == 0) {
				int ratio = edge / coarse.edge;
				if (x % ratio == 0 && z % ratio == 0) { data[z * edge + x] = coarse.data[z / ratio * coarse.edge + x / ratio]; continue; }
			}
			long start = System.nanoTime();
			data[z * edge + x] = sampler.sample(key.x * REGION + x * step, key.z * REGION + z * step);
			long elapsed = System.nanoTime() - start;
			samples++; sampleNanos += elapsed; worstSampleNanos = Math.max(worstSampleNanos, elapsed);
			// A slow sample is the machine being busy (bakes, chunk generation, GC), not a broken
			// sampler: rest a while and carry on, rather than leaving the map blank for the session.
			if (elapsed > 50_000_000L) { pause(1_000_000_000L); sliceCpu = 0; if (closed || wanted != view) return null; continue; }
			sliceCpu += elapsed;
			if (sliceCpu >= 2_000_000L) {
				// 10% duty cycle of one low-priority thread: roughly 100 ms of sampling CPU per
				// second, including slow calls. A detailed region then fills in about a second.
				pause(Math.min(2_500_000_000L, sliceCpu * 9)); sliceCpu = 0;
			}
		}
		return new Grid(key, data, edge);
	}

	private void pause(long nanos) {
		// Short interruptible sleeps keep cancellation responsive even for an unusually slow sample.
		long until = System.nanoTime() + nanos;
		Bounds before = wanted;
		while (!closed && wanted == before && System.nanoTime() < until)
			LockSupport.parkNanos(Math.min(20_000_000L, until - System.nanoTime()));
	}

	private static List<Key> regions(Bounds b) {
		List<Key> keys = new ArrayList<>();
		// At most 16 visible regions. Huge custom screens keep paper beyond this bounded work set.
		int cx = Math.floorDiv((int) (((long)b.minX + b.maxX) / 2), REGION);
		int cz = Math.floorDiv((int) (((long)b.minZ + b.maxZ) / 2), REGION);
		for (int dz = -2; dz <= 2; dz++) for (int dx = -2; dx <= 2; dx++) {
			Key key = new Key(cx + dx, cz + dz);
			if (b.contains(key)) keys.add(key);
		}
		keys.sort(Comparator.comparingInt(k -> (k.x - cx) * (k.x - cx) + (k.z - cz) * (k.z - cz)));
		return keys.size() > 16 ? keys.subList(0, 16) : keys;
	}

	/** Drops the least recently used grids past the count or byte budget; returns the bytes left. */
	private static long trim(LinkedHashMap<Key, Grid> grids, long bytes) {
		var it = grids.values().iterator();
		while ((grids.size() > MAX_REGIONS || bytes > MAX_BYTES) && it.hasNext()) {
			bytes -= it.next().data.length;
			it.remove();
		}
		return bytes;
	}

	private static Path file(Path folder, Key key) { return folder.resolve(key.x + "_" + key.z + ".biomes"); }
	private static @Nullable Grid read(Path folder, Key key) {
		try {
			Path path = file(folder, key);
			long length = Files.size(path);
			if (length != COARSE * COARSE + 12 && length != FINE * FINE + 12 && length != DETAIL * DETAIL + 12) return null;
			ByteBuffer bytes = ByteBuffer.wrap(Files.readAllBytes(path));
			if (bytes.getInt() != 0x44425031) return null;
			int edge = bytes.getInt(), checksum = bytes.getInt();
			if ((edge != COARSE && edge != FINE && edge != DETAIL) || bytes.remaining() != edge * edge) return null;
			byte[] data = new byte[edge * edge]; bytes.get(data);
			CRC32 crc = new CRC32(); crc.update(data);
			if ((int) crc.getValue() != checksum) return null;
			for (byte kind : data) if (kind < 1 || kind > 7) return null;
			try { Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis())); }
			catch (Exception ignored) {}
			return new Grid(key, data, edge);
		} catch (Exception ignored) { return null; }
	}
	private static void write(Path folder, Grid grid) {
		try {
			CRC32 crc = new CRC32(); crc.update(grid.data);
			ByteBuffer bytes = ByteBuffer.allocate(grid.data.length + 12);
			bytes.putInt(0x44425031).putInt(grid.edge).putInt((int)crc.getValue()).put(grid.data);
			Path target = file(folder, grid.key), temp = target.resolveSibling(target.getFileName() + ".tmp");
			Files.write(temp, bytes.array());
			Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (Exception ignored) { /* Disk cache is optional; the immutable RAM result remains usable. */ }
	}
	private static void prune(Path folder) {
		try (var stream = Files.walk(folder, 2)) {
			List<Path> files = stream.filter(p -> p.toString().endsWith(".biomes"))
				.sorted(Comparator.comparingLong(SeedPreview::modified)).toList();
			long bytes = 0;
			for (Path path : files) bytes += Files.size(path);
			for (Path path : files) {
				if (bytes <= DISK_LIMIT) break;
				long size = Files.size(path); Files.deleteIfExists(path); bytes -= size;
			}
		} catch (Exception ignored) {}
	}
	private static long modified(Path path) {
		try { return Files.getLastModifiedTime(path).toMillis(); } catch (Exception ignored) { return 0; }
	}
	@Override public void close() {
		closed = true; wanted = null; worker.interrupt(); cache.clear(); cacheBytes = 0; completed.clear();
	}
}
