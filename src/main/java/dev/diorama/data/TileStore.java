package dev.diorama.data;

import dev.diorama.Diorama;
import dev.diorama.Projection;
import dev.diorama.bake.Mipmaps;
import dev.diorama.bake.TileImage;
import dev.diorama.bake.TileKey;
import dev.diorama.bake.TilePixels;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.zip.InflaterInputStream;
import org.jspecify.annotations.Nullable;

/**
 * Baked surface tiles saved on disk, one file per tile, so the map remembers places after their
 * chunks unload or the game restarts.
 *
 * <p>Saved tiles are merged in when baking: wherever a fresh bake has no data for a pixel, the
 * saved pixel is used. Opening a part of the map whose chunks aren't loaded is then just a bake
 * where everything is unknown, and re-baking near the edge of loaded chunks never erases what was
 * saved. Writes happen a few seconds after a tile first changes, on one background thread.
 * Closing waits until everything is on disk, like Minecraft saving the world when you leave, so a
 * store reopened on the same folder always reads what the last one wrote.
 *
 * <p>Overview tiles for zooming far out ({@link TileKey#lod()} above 0) live in {@code lod<n>}
 * folders. Each is its four children shrunk to a quarter. A couple of seconds after tiles are
 * written, the writer rebuilds the overview tiles above them, each once however many of its
 * children changed, so zooming out shows everything explored without baking anything. Only the
 * writer builds them: a missing overview tile means nothing under it was ever mapped.
 *
 * <p>Reads legacy {@code OMT1} and writes atomic, checksummed {@code OMT2} day/night bundles.
 * Legacy files remain untouched for rollback. Night alpha measures light-data availability.
 */
public final class TileStore {
	private static final int MAGIC = 0x4F4D5431;
	private static final int CACHED_TILES = 24;
	private static final long WRITE_DELAY_SECONDS = 3;
	/** After a failed write (a full disk, say), try again this much later. */
	private static final long RETRY_DELAY_SECONDS = 30;
	/** Overview tiles are rebuilt this long after the first tile under them is written. */
	private static final long OVERVIEW_DELAY_SECONDS = 2;

	/** The only thread that writes tiles. */
	private static final ScheduledThreadPoolExecutor WRITER = new ScheduledThreadPoolExecutor(1, task -> {
		Thread thread = new Thread(task, "diorama-tile-writer");
		thread.setDaemon(true);
		thread.setPriority(Thread.MIN_PRIORITY + 1);
		return thread;
	});

	private static final ConcurrentHashMap<Path, CompletableFuture<Void>> CLOSING = new ConcurrentHashMap<>();
    private final CompletableFuture<Void> previousClose;
    private CompletableFuture<Void> closeFuture;
    private final Path folder;
	/** Set by close(), whose flush writes everything; writes still scheduled then do nothing. */
	private volatile boolean closed;
	/**
	 * Held while reading a tile from disk or merging bakes into it, so those happen one at a time
	 * and in order. The render thread never takes it: it only hands bakes over. Take before
	 * {@code this}, never the other way round.
	 */
	private final Object io = new Object();
	/**
	 * Bakes handed over by {@link #baked} and not yet merged into their tile, oldest first. Guarded
	 * by {@code this}.
	 */
	private final Map<TileKey, ArrayDeque<TileImage>> handedOver = new HashMap<>();
	/** Tiles with a merge waiting on the writer thread, so a run of bakes queues one. Guarded by {@code this}. */
	private final Set<TileKey> mergeQueued = new HashSet<>();
	/** Recently used tiles, decompressed. Guarded by {@code this}. */
	private final TileReadCache cache = new TileReadCache(CACHED_TILES, (long) CACHED_TILES * Projection.TILE_PIXELS * Projection.TILE_PIXELS * Integer.BYTES);
	/**
	 * Tiles changed since they were last written, kept until a write of exactly these pixels
	 * succeeds. They're never evicted from the cache meanwhile. Guarded by {@code this}.
	 */
	private final Map<TileKey, TilePixels> unwritten = new HashMap<>();

	/** Level-1 overview tiles above tiles written since the last rebuild. Guarded by {@code this}. */
	private final Set<TileKey> staleOverviews = new HashSet<>();
	private boolean rebuildScheduled;
	/** Told about overview tiles the writer rebuilt. Called on the writer thread. */
	private volatile Consumer<TileKey> overviewChanged = key -> {
	};

	public TileStore(Path folder) {
		this.folder = folder.toAbsolutePath().normalize();
        this.previousClose = CLOSING.getOrDefault(this.folder, CompletableFuture.completedFuture(null));
	}

	public void onOverviewChanged(Consumer<TileKey> listener) {
		this.overviewChanged = listener;
	}

	/** One level up from four children, each shrunk to a quarter; null if none exist. */
	private @Nullable TilePixels shrinkChildren(TileKey key) {
		int size = Projection.TILE_PIXELS;
		int half = size / 2;
		int[] out = null;
        int[] night = new int[size * size];
        boolean hasNight = false;
		for (int dy = 0; dy < 2; dy++) {
			for (int dx = 0; dx < 2; dx++) {
				TileKey child = new TileKey(key.layer(), key.tileU() * 2 + dx, key.tileV() * 2 + dy, key.lod() - 1);
				TilePixels childPixels = this.readPixels(child);
                int[] c = childPixels == null ? null : childPixels.day();
				if (c == null) {
					continue;
				}
				if (out == null) {
					out = new int[size * size];
				}
				for (int y = 0; y < half; y++) {
					int row = (2 * y) * size;
					for (int x = 0; x < half; x++) {
						int i = row + 2 * x;
						out[(dy * half + y) * size + dx * half + x] = Mipmaps.average(c[i], c[i + 1], c[i + size], c[i + size + 1]);
                        if (childPixels.night() != null) {
                            int[] n = childPixels.night(); hasNight = true;
                            night[(dy * half + y) * size + dx * half + x] = Mipmaps.average(n[i], n[i + 1], n[i + size], n[i + size + 1]);
                        }
					}
				}
			}
		}
		return out == null ? null : new TilePixels(out, hasNight ? night : null);
	}

	/** A tile was written: the overview tiles above it are now out of date. */
	private synchronized void overviewsStale(TileKey key) {
		if (key.lod() >= TileKey.MAX_LOD) {
			return;
		}
		this.staleOverviews.add(key.parent());
		if (!this.rebuildScheduled && !this.closed) {
			this.rebuildScheduled = true;
			WRITER.schedule(this::rebuildOverviews, OVERVIEW_DELAY_SECONDS, TimeUnit.SECONDS);
		}
	}

	/** Flush pending detail revisions, then derive their overview hierarchy on the writer. */
	public CompletableFuture<Void> refreshOverviews() {
        return this.previousClose.thenRunAsync(() -> {
            Set<TileKey> keys;
            synchronized (this) { keys = new HashSet<>(this.handedOver.keySet()); }
            for (TileKey key : keys) this.merge(key);
            synchronized (this) { keys = new HashSet<>(this.unwritten.keySet()); }
            for (TileKey key : keys) this.write(key);
            this.rebuildOverviews();
        }, WRITER);
    }

	/** Rebuilds and writes every out-of-date overview tile, one level at a time. Writer thread. */
	private void rebuildOverviews() {
		Set<TileKey> level;
		synchronized (this) {
			level = new HashSet<>(this.staleOverviews);
			this.staleOverviews.clear();
			this.rebuildScheduled = false;
		}
		while (!level.isEmpty()) {
			Set<TileKey> above = new HashSet<>();
			for (TileKey key : level) {
				TilePixels pixels = this.shrinkChildren(key);
				if (pixels == null) {
					continue;
				}
				// Under io too, so a read loading this tile from disk can't cache the old pixels over these.
				synchronized (this.io) {
					synchronized (this) {
						this.cache.put(key, pixels);
						// Pending until written, like a bake.
						this.unwritten.put(key, pixels);
					}
				}
				boolean saved = this.store(key, pixels);
				synchronized (this) {
					if (this.unwritten.get(key) == pixels) {
						if (saved) {
							this.unwritten.remove(key);
						} else if (!this.closed) {
							WRITER.schedule(() -> this.write(key), RETRY_DELAY_SECONDS, TimeUnit.SECONDS);
						}
					}
				}
				this.overviewChanged.accept(key);
				if (key.lod() < TileKey.MAX_LOD) {
					above.add(key.parent());
				}
				synchronized (this) {
					this.trimCache();
				}
			}
			level = above;
		}
	}

	/**
	 * The saved pixels of a tile, or null if there are none, including every bake handed over so
	 * far. Not on the render thread: it can wait for the disk. Don't modify the array.
	 */
	public int @Nullable [] read(TileKey key) {
        TilePixels pixels = readPixels(key);
        return pixels == null ? null : pixels.day();
    }

    public @Nullable TilePixels readPixels(TileKey key) {
        this.previousClose.join();
		synchronized (this.io) {
			this.merge(key);
			return this.saved(key);
		}
	}

	/** The tile's pixels in memory, else on disk, leaving out bakes not merged yet. Hold {@link #io}. */
	private @Nullable TilePixels saved(TileKey key) {
		synchronized (this) {
			TilePixels pending = this.unwritten.get(key);
			if (pending != null) {
				return pending;
			}
			if (this.cache.contains(key)) {
				return this.cache.get(key);
			}
		}
		TilePixels pixels = this.load(key);
		synchronized (this) {
			this.cache.put(key, pixels);
		}
		return pixels;
	}

	/**
	 * Records a fresh bake, full or partial. The writer thread merges it into the saved pixels and
	 * schedules the write. Returns without waiting for the disk or for another thread's read, so
	 * the render thread can call it.
	 */
	public void baked(TileImage image) {
		TileKey key = image.key();
		synchronized (this) {
			this.handedOver.computeIfAbsent(key, k -> new ArrayDeque<>()).add(image);
			if (!this.mergeQueued.add(key)) {
				return;
			}
		}
		this.previousClose.thenRunAsync(() -> {
			// Cleared first, so a bake handed over during this merge queues another.
			synchronized (this) {
				this.mergeQueued.remove(key);
			}
			this.merge(key);
		}, WRITER);
	}

	/**
	 * Merges the bakes handed over for a tile into its pixels, oldest first, and schedules the
	 * write, unless the pixels come out the same as what's saved already: most re-bakes after a
	 * light change or a block that looks the same change nothing.
	 */
	private void merge(TileKey key) {
		synchronized (this.io) {
			List<TileImage> images;
			synchronized (this) {
				ArrayDeque<TileImage> queue = this.handedOver.get(key);
				if (queue == null) {
					return;
				}
				images = List.copyOf(queue);
			}
			int size = Projection.TILE_PIXELS;
            TilePixels saved = this.saved(key);
            boolean changed = false;
            for (TileImage image : images) {
                int[] day;
                int[] night;
                byte[] surface;
                if (image.isFull()) {
                    day = image.mips()[0].clone();
                    night = image.nightMips() == null ? null : image.nightMips()[0].clone();
                    surface = image.surface() == null ? null : image.surface().clone();
                } else {
                    day = saved == null ? new int[size * size] : saved.day().clone();
                    boolean hasNight = saved != null && saved.night() != null || image.patches().stream().anyMatch(p -> p.nightMips() != null);
                    night = !hasNight ? null : saved != null && saved.night() != null ? saved.night().clone() : new int[size * size];
                    boolean hasSurface = saved != null && saved.surface() != null || image.patches().stream().anyMatch(p -> p.surface() != null);
                    surface = !hasSurface ? null : saved != null && saved.surface() != null ? saved.surface().clone() : new byte[size * size];
                    for (TileImage.Patch patch : image.patches()) {
                        int edge = TileImage.BLOCK;
                        for (int y = 0; y < edge; y++) {
                            int offset = (patch.blockY() * edge + y) * size + patch.blockX() * edge;
                            System.arraycopy(patch.mips()[0], y * edge, day, offset, edge);
                            if (night != null) {
                                if (patch.nightMips() == null) java.util.Arrays.fill(night, offset, offset + edge, 0);
                                else System.arraycopy(patch.nightMips()[0], y * edge, night, offset, edge);
                            }
                            if (surface != null) {
                                if (patch.surface() == null) java.util.Arrays.fill(surface, offset, offset + edge, (byte) 0);
                                else System.arraycopy(patch.surface(), y * edge, surface, offset, edge);
                            }
                        }
                    }
                }
                TilePixels pixels = new TilePixels(day, night, surface);
                if (saved != null ? saved.samePixels(pixels) : nothingKnown(day)) {
                    count(true);
                    continue;
                }
                count(false);
                saved = pixels;
                changed = true;
            }
			synchronized (this) {
				// Bakes handed over meanwhile stay queued; their own merge comes next.
				ArrayDeque<TileImage> queue = this.handedOver.get(key);
				for (int i = 0; i < images.size(); i++) {
					queue.poll();
				}
				if (queue.isEmpty()) {
					this.handedOver.remove(key);
				}
				if (changed) {
					this.cache.put(key, saved);
					boolean scheduled = this.unwritten.containsKey(key);
					this.unwritten.put(key, saved);
					if (!scheduled) {
						WRITER.schedule(() -> this.write(key), WRITE_DELAY_SECONDS, TimeUnit.SECONDS);
					}
				}
			}
		}
	}

	/** Writes everything pending and waits until it's on disk. Call when leaving the world. */
    /** Publish retirement before a view can reopen this folder, including its pending bake time. */
    public void finishing(java.util.concurrent.CompletableFuture<Void> completion) {
        CLOSING.put(this.folder, completion);
        completion.whenComplete((ignored, failure) -> CLOSING.remove(this.folder, completion));
    }

    public void close() { this.closeAsync().join(); }

    /** Schedule the final flush without blocking a render or client thread. */
    public synchronized CompletableFuture<Void> closeAsync() {
        if (this.closeFuture != null) return this.closeFuture;
        this.closed = true;
        this.closeFuture = this.previousClose.thenRunAsync(() -> {
			Set<TileKey> handed;
			synchronized (this) {
				handed = new HashSet<>(this.handedOver.keySet());
			}
			for (TileKey key : handed) {
				this.merge(key);
			}
			Map<TileKey, TilePixels> pending;
			synchronized (this) {
				pending = new HashMap<>(this.unwritten);
			}
			for (Map.Entry<TileKey, TilePixels> entry : pending.entrySet()) {
				// Nothing to retry with once the world is closed; store() has logged the failure.
				if (this.store(entry.getKey(), entry.getValue()) && entry.getKey().lod() == 0) {
					synchronized (this) {
						this.staleOverviews.add(entry.getKey().parent());
					}
				}
			}
			this.rebuildOverviews();
			synchronized (this) {
				this.unwritten.clear();
				this.cache.clear();
			}
        }, WRITER);
        CLOSING.put(this.folder, this.closeFuture);
        this.closeFuture.whenComplete((ignored, failure) -> CLOSING.remove(this.folder, this.closeFuture));
        return this.closeFuture;
    }

	/** Writes the newest pixels of a tile. Writer thread. */
	void write(TileKey key) {
		if (this.closed) {
			return;
		}
		TilePixels pixels;
		synchronized (this) {
			pixels = this.unwritten.get(key);
		}
		if (pixels == null) {
			return;
		}
		boolean saved = this.store(key, pixels);
		if (saved && key.lod() == 0) {
			this.overviewsStale(key);
		}
		synchronized (this) {
			if (this.unwritten.get(key) != pixels) {
				// A newer bake arrived while this one was being written: write that too.
				WRITER.schedule(() -> this.write(key), WRITE_DELAY_SECONDS, TimeUnit.SECONDS);
			} else if (saved) {
				this.unwritten.remove(key);
			} else {
				// A full disk, say: keep it pending and try again.
				WRITER.schedule(() -> this.write(key), RETRY_DELAY_SECONDS, TimeUnit.SECONDS);
			}
			this.trimCache();
		}
	}

	private static final java.util.concurrent.atomic.AtomicInteger CHANGED = new java.util.concurrent.atomic.AtomicInteger();
	private static final java.util.concurrent.atomic.AtomicInteger UNCHANGED = new java.util.concurrent.atomic.AtomicInteger();

	private static void count(boolean unchanged) {
		if (!Diorama.DEBUG) {
			return;
		}
		int changed = unchanged ? CHANGED.get() : CHANGED.incrementAndGet();
		int same = unchanged ? UNCHANGED.incrementAndGet() : UNCHANGED.get();
		if ((changed + same) % 100 == 0) {
			Diorama.LOGGER.info("Bakes saved: {} changed, {} unchanged and skipped", changed, same);
		}
	}

	private static boolean nothingKnown(int[] pixels) {
		for (int pixel : pixels) {
			if ((pixel >>> 24) != 0) {
				return false;
			}
		}
		return true;
	}

	/** Drops the least recently used written tiles until the cache is back under its limit. */
	private void trimCache() {
		// Pending pixels live in unwritten, independently of the bounded read cache.
	}

	/**
	 * Every detailed surface tile saved here, including ones still waiting to be written. Lists the
	 * folder, so call off the render thread.
	 */
	public Set<TileKey> savedTiles() {
		Set<TileKey> keys = new HashSet<>();
		try (var files = Files.list(this.folder)) {
			files.forEach(path -> {
				String name = path.getFileName().toString();
				int dot = name.indexOf('.'), split = name.indexOf('_', 1);
				if (split < 0 || dot < split || !(name.endsWith(".omt") || name.endsWith(".omt2"))) return;
				try {
					keys.add(new TileKey(TileKey.SURFACE, Integer.parseInt(name.substring(0, split)), Integer.parseInt(name.substring(split + 1, dot))));
				} catch (NumberFormatException ignored) {
					// Not a tile file.
				}
			});
		} catch (IOException ignored) {
			// No folder yet: nothing saved.
		}
		synchronized (this) {
			for (TileKey key : this.unwritten.keySet()) if (key.lod() == 0) keys.add(key);
		}
		return keys;
	}

	/** The tile's surface plane, beside its bundle; see {@link SurfaceFile}. */
	private Path surfaceFile(TileKey key) {
		return this.file(key).resolveSibling(key.tileU() + "_" + key.tileV() + ".omts");
	}

	private Path file(TileKey key) {
		Path dir = key.lod() == 0 ? this.folder : this.folder.resolve("lod" + key.lod());
		return dir.resolve(key.tileU() + "_" + key.tileV() + ".omt");
	}

	private @Nullable TilePixels load(TileKey key) {
        Path bundle = this.file(key).resolveSibling(key.tileU() + "_" + key.tileV() + ".omt2");
        if (Files.isRegularFile(bundle)) {
            try {
                TilePixels pixels = TileBundle.read(bundle, key);
                return key.lod() == 0 ? pixels.withSurface(SurfaceFile.read(this.surfaceFile(key), key, pixels.day())) : pixels;
            }
            catch (IOException | RuntimeException failure) { Diorama.LOGGER.warn("Invalid lighting bundle {}; trying legacy map", bundle, failure); }
        }
		Path file = this.file(key);
		if (!Files.isRegularFile(file)) {
			return null;
		}
		try (InputStream in = Files.newInputStream(file); DataInputStream data = new DataInputStream(in)) {
			if (data.readInt() != MAGIC || data.readInt() != Projection.TILE_PIXELS) {
				return null;
			}
			int size = Projection.TILE_PIXELS;
			byte[] bytes = new InflaterInputStream(in).readNBytes(size * size * 4);
			if (bytes.length != size * size * 4) {
				return null;
			}
			int[] pixels = new int[size * size];
			ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(pixels);
			return new TilePixels(pixels, null);
		} catch (IOException e) {
			Diorama.LOGGER.warn("Couldn't read saved tile {}", file, e);
			return null;
		}
	}

	/**
	 * Writes to a temporary file and moves it over the old one, so a crash can't leave half a tile.
	 * Returns whether it worked.
	 */
    private boolean store(TileKey key, TilePixels pixels) {
        Path file = this.file(key).resolveSibling(key.tileU() + "_" + key.tileV() + ".omt2");
        try {
            this.writtenBytes.addAndGet(TileBundle.write(file, key, pixels));
            // After the bundle, which a crash between the two leaves without a matching plane.
            if (key.lod() == 0) SurfaceFile.write(this.surfaceFile(key), key, pixels.day(), pixels.surface());
            this.writes.incrementAndGet();
            return true;
        } catch (IOException | RuntimeException e) {
            Diorama.LOGGER.error("Couldn't save tile {}", file, e);
            return false;
        }
    }

    private final java.util.concurrent.atomic.AtomicLong writes = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong writtenBytes = new java.util.concurrent.atomic.AtomicLong();
    public long writes() { return this.writes.get(); }
    public long writtenBytes() { return this.writtenBytes.get(); }
}
