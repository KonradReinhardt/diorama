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
    public static final int REGION = 4096, COARSE = 16, FINE = 64;
    private static final int MAX_REGIONS = 960; // <4 MiB of sample arrays, including coarse grids
    private static final long DISK_LIMIT = 64L << 20;
    public interface Sampler { byte sample(int blockX, int blockZ); }
    public record Source(Sampler sampler, String fingerprint) {}
    public record Key(int x, int z) {}
    public record Bounds(int minX, int minZ, int maxX, int maxZ, boolean fine) {
        boolean contains(Key key) {
            return key.x >= Math.floorDiv(minX, REGION) && key.x <= Math.floorDiv(maxX, REGION)
                && key.z >= Math.floorDiv(minZ, REGION) && key.z <= Math.floorDiv(maxZ, REGION);
        }
    }
    private record Grid(Key key, byte[] data, int edge) {}
    private final LinkedHashMap<Key, Grid> cache = new LinkedHashMap<>(32, .75f, true);
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
            cache.put(grid.key, grid);
            while (cache.size() > MAX_REGIONS) cache.remove(cache.keySet().iterator().next());
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
            int nx = Math.floorDiv(x, REGION), nz = Math.floorDiv(z, REGION);
            if (nx != rx || nz != rz) { rx = nx; rz = nz; grid = cache.get(new Key(nx, nz)); }
            if (grid == null) return 0;
            int step = REGION / grid.edge;
            return grid.data[Math.floorMod(z, REGION) / step * grid.edge + Math.floorMod(x, REGION) / step];
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
        try {
            long start = System.nanoTime();
            Source source = initialize.get();
            initializationNanos = System.nanoTime() - start;
            if (source == null) { unavailable = "No compatible singleplayer biome source"; return; }
            Path folder = root == null ? null : root.resolve(source.fingerprint);
            if (folder != null) { Files.createDirectories(folder); prune(root); }
            int writes = 0;
            Bounds published = null;
            while (!closed) {
                Bounds view = wanted;
                if (view == null || System.nanoTime() - movedAt < 250_000_000L) { pause(50_000_000L); continue; }
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
                for (int edge : view.fine ? new int[]{COARSE, FINE} : new int[]{COARSE}) {
                    for (Key key : keys) {
                        if (closed || wanted != view) break;
                        Grid old = work.get(key);
                        if (old == null && folder != null) {
                            old = read(folder, key);
                            if (old != null) { work.put(key, old); completed.add(old); }
                        }
                        if (old != null && old.edge >= edge) continue;
                        Grid grid = generate(source.sampler, key, edge, old, view);
                        if (grid == null) break;
                        work.put(key, grid); completed.add(grid); didWork = true;
                        while (work.size() > MAX_REGIONS) work.remove(work.keySet().iterator().next());
                        if (folder != null) {
                            write(folder, grid);
                            if (++writes % 16 == 0) prune(root);
                        }
                    }
                }
                if (!didWork) pause(100_000_000L);
            }
        } catch (Exception error) {
            unavailable = error.getClass().getSimpleName() + ": " + error.getMessage();
        } finally { work.clear(); }
    }

    private @Nullable Grid generate(Sampler sampler, Key key, int edge, @Nullable Grid coarse, Bounds view) {
        byte[] data = new byte[edge * edge];
        int step = REGION / edge;
        for (int z = 0; z < edge; z++) for (int x = 0; x < edge; x++) {
            if (closed || wanted != view) return null;
            // Samples sit at grid vertices so the coarse result is reused exactly during refinement.
            if (coarse != null && edge == FINE && coarse.edge == COARSE && x % 4 == 0 && z % 4 == 0) {
                data[z * edge + x] = coarse.data[z / 4 * COARSE + x / 4]; continue;
            }
            long start = System.nanoTime();
            data[z * edge + x] = sampler.sample(key.x * REGION + x * step, key.z * REGION + z * step);
            long elapsed = System.nanoTime() - start;
            samples++; sampleNanos += elapsed; worstSampleNanos = Math.max(worstSampleNanos, elapsed);
            if (elapsed > 50_000_000L) { unavailable = "Biome sampling exceeded 50 ms; preview paused"; closed = true; return null; }
            sliceCpu += elapsed;
            if (sliceCpu >= 2_000_000L) {
                // 2% duty cycle: roughly 20 ms of sampling CPU per second, including slow calls.
                pause(Math.min(2_500_000_000L, sliceCpu * 49)); sliceCpu = 0;
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

    private static Path file(Path folder, Key key) { return folder.resolve(key.x + "_" + key.z + ".biomes"); }
    private static @Nullable Grid read(Path folder, Key key) {
        try {
            Path path = file(folder, key);
            long length = Files.size(path);
            if (length != COARSE * COARSE + 12 && length != FINE * FINE + 12) return null;
            ByteBuffer bytes = ByteBuffer.wrap(Files.readAllBytes(path));
            if (bytes.getInt() != 0x44425031) return null;
            int edge = bytes.getInt(), checksum = bytes.getInt();
            if ((edge != COARSE && edge != FINE) || bytes.remaining() != edge * edge) return null;
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
        closed = true; wanted = null; worker.interrupt(); cache.clear(); completed.clear();
    }
}
