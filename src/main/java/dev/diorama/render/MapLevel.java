package dev.diorama.render;

import dev.diorama.Diorama;
import dev.diorama.Projection;
import dev.diorama.bake.BlockLooks;
import dev.diorama.bake.ChunkSource;
import dev.diorama.bake.Overlays;
import dev.diorama.bake.TileBaker;
import dev.diorama.bake.TileImage;
import dev.diorama.bake.TileKey;
import dev.diorama.data.ChunkSnapshot;
import dev.diorama.data.ChangeBounds;
import dev.diorama.data.SeenBlocks;
import dev.diorama.data.TileStore;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;

/**
 * Everything the map knows about one dimension: chunk snapshots, baked tiles, and which tiles
 * need baking. Tiles are baked on demand when a view asks for them, and re-baked shortly after
 * the chunks under them change.
 */
public final class MapLevel implements ChunkSource {
	private static final int MAX_CHUNKS = 4096;
	private static final int MAX_GPU_TILES = 192;
	/** A changed tile waits this long for more changes before re-baking... */
	private static final long QUIET_MS = 350;
	/** ...but never longer than this. */
	private static final long MAX_WAIT_MS = 1500;

	private static final AtomicInteger THREAD_ID = new AtomicInteger();
	private static final int BAKER_COUNT = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 3));
	private static final ExecutorService BAKERS = Executors.newFixedThreadPool(
		BAKER_COUNT,
		task -> {
			Thread thread = new Thread(task, "diorama-baker-" + THREAD_ID.incrementAndGet());
			thread.setDaemon(true);
			thread.setPriority(Thread.MIN_PRIORITY + 1);
			return thread;
		}
	);

	private final int minY;
	private final int maxY;
	private final ConcurrentHashMap<Long, ChunkSnapshot> chunks = new ConcurrentHashMap<>();
	private volatile int topY = Integer.MIN_VALUE;
	private final SeenBlocks seen;
	/** Where this dimension's map is saved, or null when there's no world to save it for. */
	private final @Nullable Path folder;
	/** Saved tiles for the current camera angle, or null when nothing is saved. Render thread. */
	private @Nullable TileStore store;
	/** The folder {@link #store} saves into; see {@link #storeName()}. */
	private String storeName = "";
	private volatile boolean fogOfWar;
	/** What bakes record for the map overlays, or null when no overlay is on. */
	private volatile @Nullable Overlays overlays;
	/**
	 * Bumped whenever tiles baked before stop being valid: new block colours, or cave slices
	 * switching to or from fog of war. Bakes that started under an older value are dropped.
	 */
	private int surfaceGeneration;
	private int caveGeneration;

	// Render thread only below here.
	private final TileCache tiles = new TileCache();
	/** Goes up whenever a tile's coverage changes or a tile comes or goes; see {@link #coverageVersion()}. */
	private long coverageVersion;
	private final Map<TileKey, Dirty> dirty = new HashMap<>();
	private final Map<TileKey, Double> wanted = new HashMap<>();
	private final Set<TileKey> inFlight = new HashSet<>();
	private final BakeScheduler scheduler = new BakeScheduler(BAKERS);
	private final TileFreshness freshness = new TileFreshness();
    private final Map<TileKey, TileFreshness.Version> jobVersions = new HashMap<>();
    private long submittedJobs;
    public long submittedJobs() { return this.submittedJobs; }
    public long gpuBytes() { return this.tiles.values().stream().mapToLong(MapTile::gpuBytes).sum(); }
    public long tileWrites() { return this.store == null ? 0 : this.store.writes(); }
    public long writtenBytes() { return this.store == null ? 0 : this.store.writtenBytes(); }
    public int queuedTiles() {
        Set<TileKey> pending = new HashSet<>(this.dirty.keySet());
        pending.addAll(this.unsaved.keySet());
        for (TileKey key : this.wanted.keySet()) if (!this.tiles.containsKey(key)) pending.add(key);
        pending.removeAll(this.inFlight);
        return pending.size();
    }
    private final RetryBackoff retries = new RetryBackoff();
	/** Overview tiles the writer rebuilt; reloaded when next wanted. */
	private final ConcurrentLinkedQueue<TileKey> overviewChanged = new ConcurrentLinkedQueue<>();
	/** Overview tiles with nothing saved under them; not asked for again until that changes. */
	private final Set<TileKey> emptyOverviews = new HashSet<>();
	/**
	 * Surface tiles whose chunks changed since the tile was last baked, and when. They're baked in
	 * the background and saved, so the saved map covers everywhere the player has been, not only
	 * the parts some map happened to show.
	 */
	private final Map<TileKey, Long> unsaved = new HashMap<>();
	/** Background bakes in flight: saved when done, but not kept on the GPU. */
	private final Set<TileKey> backgroundBakes = new HashSet<>();
	/** A changed tile waits this long for more changes before a background bake. */
	private static final long BACKGROUND_QUIET_MS = 2000;
	/**
	 * Set when a trim couldn't get the snapshots down because their tiles still wait to be saved.
	 * New snapshots then don't start another try each; the next one comes {@link #TRIM_RETRY_MS}
	 * later, since a trim looks at every snapshot.
	 */
	private boolean trimStuck;
	private long lastTrimMs;
	private static final long TRIM_RETRY_MS = 1000;
	/** Where the player was at the last {@link #put}; trims keep the snapshots nearest it. */
	private int playerChunkX;
	private int playerChunkZ;
	private long frame;
	private volatile boolean closed;
    private dev.diorama.bake.BakeContext bakeContext = dev.diorama.bake.BakeContext.capture();
    private static final java.util.concurrent.ConcurrentLinkedQueue<java.util.concurrent.CompletableFuture<Void>> SAVES = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static final java.util.concurrent.ExecutorService SAVER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "diorama-session-saver");
        thread.setDaemon(true);
        return thread;
    });

	/** See {@link ChunkSource#roof()}. */
	private final int roof;
    private final boolean nightLighting;
    @Override public boolean nightLighting() { return this.nightLighting; }

	public MapLevel(int minY, int maxY, int roof, @Nullable Path folder) {
        this(minY, maxY, roof, folder, true);
    }

    public MapLevel(int minY, int maxY, int roof, @Nullable Path folder, boolean nightLighting) {
		this.minY = minY;
		this.maxY = maxY;
		this.roof = roof;
        this.nightLighting = nightLighting;
		this.folder = folder;
		this.store = this.openStore();
		this.seen = folder == null ? new SeenBlocks() : SeenBlocks.load(folder.resolve("seen.bin"));
	}

	@Override
	public @Nullable ChunkSnapshot chunk(int chunkX, int chunkZ) {
		return this.chunks.get(ChunkSnapshot.key(chunkX, chunkZ));
	}

	@Override
	public int minY() {
		return this.minY;
	}

	@Override
	public int maxY() {
		return this.maxY;
	}

	@Override
	public int topY() {
		return this.topY;
	}

	@Override
	public int roof() {
		return this.roof;
	}

	/** Whether the sky view is drawn from under a roof, lit like a cave. */
	public boolean roofless() {
		return this.roof != NO_ROOF;
	}

	public boolean fogOfWar() {
		return this.fogOfWar;
	}

	@Override
	public boolean isSeen(int x, int y, int z) {
		return this.seen.isSeen(x, y, z);
	}

	public SeenBlocks seen() {
		return this.seen;
	}

	/** Switches cave slices between full and fog of war, dropping cave tiles baked the other way. */
	public void setFogOfWar(boolean value) {
		if (this.fogOfWar == value) {
			return;
		}
		this.fogOfWar = value;
		this.caveGeneration++;
		this.dropTiles(true);
		// Under a roof the sky view follows fog of war too, and keeps its own saved tiles for it.
		this.switchStoreIfNeeded();
	}

	/**
	 * What bakes record for the overlays. Turning overlays on, turning spawn spots on, or a new
	 * slime seed bakes the tiles in view again, including any bake already under way, so they
	 * record current bits. Switching between overlays a bake already recorded needs no bake.
	 */
	public void setOverlays(@Nullable Overlays overlays) {
		if (java.util.Objects.equals(overlays, this.overlays)) {
			return;
		}
		this.overlays = overlays;
		if (overlays == null) {
			return;
		}
		int blocks = (Projection.TILE_PIXELS / TileImage.BLOCK) * (Projection.TILE_PIXELS / TileImage.BLOCK);
		Set<TileKey> keys = new HashSet<>(this.tiles.keySet());
		keys.addAll(this.inFlight);
		for (TileKey key : keys) {
			if (key.lod() == 0) {
				this.dirty.computeIfAbsent(key, k -> new Dirty(0)).blocks.set(0, blocks);
			}
		}
	}

	/** Whether spawn spots are recorded, so light changes have to refresh the surface. */
	public boolean spawnSpots() {
		Overlays o = this.overlays;
		return o != null && o.spawnSpots();
	}

	/**
	 * The saved tiles for the current camera angle. Where a tile's pixels land depends on the
	 * angle, so tiles saved at one angle would merge into the wrong places at another.
	 */
	private @Nullable TileStore openStore() {
		if (this.folder == null) {
			return null;
		}
		this.storeName = this.storeName();
		TileStore opened = new TileStore(this.folder.resolve(this.storeName));
		opened.onOverviewChanged(this.overviewChanged::add);
		return opened;
	}

	/**
	 * Where the sky view's tiles are saved: one folder per kind of view, since their pixels mean
	 * different things and saved pixels are merged into fresh bakes. The camera angle moves where
	 * pixels land. Under a roof, the view is a different picture, and with fog of war it may only
	 * hold what the player has seen. 60 degrees was the only angle before others existed, and its
	 * sky view tiles are in "surface".
	 */
	private String storeName() {
		long pitch = Math.round(dev.diorama.MapStyle.get().pitchDegrees());
		if (this.roofless()) {
			return "under-roof-" + pitch + (this.fogOfWar ? "-fog" : "") + directionSuffix();
		}
		return (pitch == 60 ? "surface" : "surface-" + pitch) + directionSuffix();
	}

    private static String directionSuffix() {
        return Projection.direction() == dev.diorama.MapDirection.NORTH ? "" : "-" + Projection.direction().name().toLowerCase(java.util.Locale.ROOT);
    }

	/** Moves to another folder if the kind of view changed, and queues everything loaded to be saved there. */
	private void switchStoreIfNeeded() {
		if (this.store == null || this.storeName().equals(this.storeName)) {
			return;
		}
		this.retireStore(this.store, false);
		this.store = this.openStore();
		this.unsaved.clear();
		this.emptyOverviews.clear();
		for (ChunkSnapshot snapshot : this.chunks.values()) {
			int x0 = snapshot.chunkX << 4;
			int z0 = snapshot.chunkZ << 4;
			this.invalidate(x0, Math.min(snapshot.floorY, this.topY), this.minY, z0, x0 + 15, this.topY, z0 + 15, true, true);
		}
	}

	/** Refresh pixels in place; only a different camera requires throwing away their geometry. */
    public void colorsChanged() {
        var next = dev.diorama.bake.BakeContext.capture();
        boolean reproject = !this.bakeContext.sameProjection(next);
        this.surfaceGeneration++;
        this.caveGeneration++;
        if (reproject) {
            this.dropTiles(false);
            this.switchStoreIfNeeded();
        } else {
            // Keep both texture and exploration coverage until their replacements are uploaded.
            // Pending old jobs must not erase these tiles when their generations are rejected.
            Set<TileKey> refresh = new HashSet<>(this.tiles.keySet());
            refresh.addAll(this.inFlight);
            int blocks = (Projection.TILE_PIXELS / TileImage.BLOCK) * (Projection.TILE_PIXELS / TileImage.BLOCK);
            for (TileKey key : refresh) {
                this.dirty.computeIfAbsent(key, k -> new Dirty(0)).blocks.set(0, blocks);
            }
        }
        this.bakeContext = next;
    }

	private void dropTiles(boolean cavesOnly) {
		Iterator<Map.Entry<TileKey, MapTile>> it = this.tiles.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<TileKey, MapTile> e = it.next();
			if (!cavesOnly || e.getKey().isCave() || this.roofless()) {
				e.getValue().close();
				it.remove();
				this.dirty.remove(e.getKey());
				this.coverageVersion++;
			}
		}
	}

	/**
	 * A number that changes whenever what {@link #peek} returns could show different coverage,
	 * so a view can keep its fog mask until it does.
	 */
	public long coverageVersion() {
		return this.coverageVersion;
	}

	private int generation(TileKey key) {
		return key.isCave() || this.roofless() ? this.caveGeneration : this.surfaceGeneration;
	}

	/** Newly seen blocks: cave slices around them can show more now. */
	public void revealed(int x0, int y0, int z0, int x1, int y1, int z1) {
		if (this.fogOfWar) {
			this.invalidate(x0, y0, y0, z0, x1, y1, z1, false, true);
		}
	}

	/** Light changed in these sections; refresh the affected map regions. */
	public void lightChanged(int chunkX, int chunkZ, int y0, int y1) {
		int x0 = chunkX << 4;
		int z0 = chunkZ << 4;
		this.invalidate(x0, y0, y0, z0, x0 + 15, y1, z0 + 15, false, true);
	}

	public int chunkCount() {
		return this.chunks.size();
	}

	/**
	 * Stores a fresh snapshot and marks the parts of tiles that can look different because of it.
	 *
	 * @param changed inclusive world-space bounds with {@link #CHANGED_SURFACE} and
	 *     {@link #CHANGED_SURFACE_LIGHT} flags; null when
	 *     the whole chunk is new
	 */
	public void put(ChunkSnapshot snapshot, @Nullable ChangeBounds changed, int playerChunkX, int playerChunkZ) {
        ChunkSnapshot previous = this.chunks.put(snapshot.key(), snapshot);
        if (previous != null && changed != null && changed.flags() == CHANGED_SURFACE_LIGHT && snapshot.sameLighting(previous)) return;
		if (snapshot.maxY != Integer.MIN_VALUE && snapshot.maxY + 1 > this.topY) {
			this.topY = Math.min(this.maxY, snapshot.maxY + 1);
		}
		if (changed == null) {
			int x0 = snapshot.chunkX << 4;
			int z0 = snapshot.chunkZ << 4;
			// Rays from any height could have stopped at this chunk while it was unknown. The sky
			// view only reaches down to the surface; cave slices can be anywhere below it.
			this.invalidate(x0, Math.min(snapshot.floorY, this.topY), this.minY, z0, x0 + 15, this.topY, z0 + 15, true, true);
		} else {
			// Both surface lighting endpoints are saved, including light-only changes.
			boolean terrain = (changed.flags() & (CHANGED_SURFACE | CHANGED_SURFACE_LIGHT)) != 0;
			boolean surface = terrain || (changed.flags() & CHANGED_SURFACE_LIGHT) != 0;
            this.invalidate(changed.minX(), changed.minY(), changed.minY(), changed.minZ(), changed.maxX(), changed.maxY(), changed.maxZ(), surface, terrain, (changed.flags() & (CHANGED_SURFACE | CHANGED_BLOCKS)) == 0);
		}
		this.playerChunkX = playerChunkX;
		this.playerChunkZ = playerChunkZ;
		if (this.chunks.size() > MAX_CHUNKS && !this.trimStuck) {
			this.trimChunks();
		}
	}

	/** Change flag: blocks the sky view can show changed, so its tiles re-bake and get saved. */
	public static final int CHANGED_SURFACE = 1;
	/** Change flag: block or sky light changed, including the surface night endpoint. */
	public static final int CHANGED_SURFACE_LIGHT = 2;
    /** A block changed even when hidden from the sky; prevents deduplicating a mixed cave edit. */
    public static final int CHANGED_BLOCKS = 4;

	/** Light from a changed block reaches this far, which matters for cave slices. */
	private static final int LIGHT_REACH = 15;

	/**
	 * Marks tile blocks that can look different after blocks in this box changed. {@code y0} is the
	 * bottom of the box for the sky view and {@code caveY0} for cave slices. {@code terrain} is
	 * false when the change only reveals cave visibility and has no surface data to save.
	 */
	private void invalidate(int x0, int y0, int caveY0, int z0, int x1, int y1, int z1, boolean visibleFromSky, boolean terrain) {
        this.invalidate(x0, y0, caveY0, z0, x1, y1, z1, visibleFromSky, terrain, false);
    }

    private void invalidate(int x0, int y0, int caveY0, int z0, int x1, int y1, int z1, boolean visibleFromSky, boolean terrain, boolean lightOnly) {
        Set<Integer> layers = new HashSet<>();
        for (TileKey key : this.tiles.keySet()) layers.add(key.layer());
        for (TileKey key : this.inFlight) layers.add(key.layer());
        if (this.store != null) layers.add(TileKey.SURFACE);
        long now = System.currentTimeMillis();
        for (var region : TileInvalidation.regions(x0, y0, caveY0, z0, x1, y1, z1, visibleFromSky, this.roofless(), this.roof, layers, Projection.direction(), lightOnly))
            this.markDirty(region.layer(), region.minU(), region.minV(), region.maxU(), region.maxV(), now, terrain, lightOnly);
	}

	private void markDirty(int layer, double u0, double v0, double u1, double v1, long now, boolean terrain, boolean lightOnly) {
		double units = Projection.TILE_UNITS;
		double blockUnits = (double) TileImage.BLOCK / Projection.PIXELS_PER_UNIT;
		int perRow = Projection.TILE_PIXELS / TileImage.BLOCK;
		int tu0 = (int) Math.floor(u0 / units);
		int tu1 = (int) Math.floor(u1 / units);
		int tv0 = (int) Math.floor(v0 / units);
		int tv1 = (int) Math.floor(v1 / units);
		for (int tu = tu0; tu <= tu1; tu++) {
			for (int tv = tv0; tv <= tv1; tv++) {
				TileKey key = new TileKey(layer, tu, tv);
				if (layer == TileKey.SURFACE && this.store != null && terrain) {
					this.unsaved.put(key, now);
				}
				if (!this.tiles.containsKey(key) && !this.inFlight.contains(key)) {
					continue;
				}
				int bx0 = Math.max(0, (int) Math.floor((u0 - tu * units) / blockUnits));
				int bx1 = Math.min(perRow - 1, (int) Math.floor((u1 - tu * units) / blockUnits));
				int by0 = Math.max(0, (int) Math.floor((v0 - tv * units) / blockUnits));
				int by1 = Math.min(perRow - 1, (int) Math.floor((v1 - tv * units) / blockUnits));
				this.freshness.changed(key, lightOnly);
                Dirty d = this.dirty.computeIfAbsent(key, k -> new Dirty(now));
				d.last = now;
				for (int by = by0; by <= by1; by++) {
					d.blocks.set(by * perRow + bx0, by * perRow + bx1 + 1);
				}
			}
		}
	}

	/** Blocks of a tile waiting to be re-baked, and since when. */
	private static final class Dirty {
		final BitSet blocks = new BitSet();
		final long first;
		long last;

		Dirty(long now) {
			this.first = now;
			this.last = now;
		}
	}

	private void trimChunks() {
		int playerChunkX = this.playerChunkX;
		int playerChunkZ = this.playerChunkZ;
		List<ChunkSnapshot> all = new ArrayList<>(this.chunks.values());
		all.sort(Comparator.comparingLong(s -> -(sq(s.chunkX - playerChunkX) + sq(s.chunkZ - playerChunkZ))));
		int excess = all.size() - MAX_CHUNKS * 3 / 4;
		for (int i = 0; i < all.size() && excess > 0; i++) {
			// A chunk under a tile still waiting for its background bake stays until it's saved.
			if (!this.waitingToSave(all.get(i))) {
				this.chunks.remove(all.get(i).key());
				excess--;
			}
		}
		this.trimStuck = excess > 0;
		this.lastTrimMs = System.currentTimeMillis();
	}

	/**
	 * Whether a surface tile this chunk shows in still has to be baked and saved: it changed since
	 * its last bake, or its bake is running and may still read this chunk.
	 */
	private boolean waitingToSave(ChunkSnapshot snapshot) {
		if (this.unsaved.isEmpty() && this.inFlight.isEmpty() || snapshot.maxY == Integer.MIN_VALUE) {
			return false;
		}
		double units = Projection.TILE_UNITS;
		int x0 = snapshot.chunkX << 4;
		int z0 = snapshot.chunkZ << 4;
		dev.diorama.MapDirection direction = Projection.direction();
        double u0 = Math.min(direction.u(x0, z0), direction.u(x0 + 16, z0 + 16));
        double u1 = Math.max(direction.u(x0, z0), direction.u(x0 + 16, z0 + 16));
        double d0 = Math.min(direction.depth(x0, z0), direction.depth(x0 + 16, z0 + 16));
        double d1 = Math.max(direction.depth(x0, z0), direction.depth(x0 + 16, z0 + 16));
        int tu0 = (int) Math.floor(u0 / units), tu1 = (int) Math.floor(u1 / units);
        int tv0 = (int) Math.floor(Projection.v(snapshot.maxY + 1, d0) / units);
        int tv1 = (int) Math.floor(Projection.v(snapshot.floorY, d1) / units);
		for (int tu = tu0; tu <= tu1; tu++) {
			for (int tv = tv0; tv <= tv1; tv++) {
				TileKey key = new TileKey(TileKey.SURFACE, tu, tv);
				if (this.unsaved.containsKey(key) || this.inFlight.contains(key)) {
					return true;
				}
			}
		}
		return false;
	}

	private static long sq(long v) {
		return v * v;
	}

	/**
	 * Returns the tile for drawing if one has been baked, and records that a view wants it.
	 * {@code priority} is lower for tiles that matter more, such as those near the view center.
	 */
	public @Nullable MapTile want(TileKey key, double priority) {
		this.wanted.merge(key, priority, Math::min);
		MapTile tile = this.tiles.get(key);
		if (tile != null) {
			tile.lastDrawn = this.frame;
		}
		return tile;
	}

	/** The baked tile, if any, without asking for it to be baked. */
	public @Nullable MapTile peek(TileKey key) {
		return this.tiles.get(key);
	}

	/** Whether overview tiles exist for zooming far out: only when the map is saved. */
	public boolean hasOverviews() {
		return this.store != null;
	}

	/** Uploads finished bakes and starts new ones. Call regularly on the render thread. */
	public void update() {
		this.frame++;
		TileKey changedOverview;
		while ((changedOverview = this.overviewChanged.poll()) != null) {
			this.emptyOverviews.remove(changedOverview);
			// A load still in flight may have read the old pixels: load it again after.
			if (this.tiles.containsKey(changedOverview) || this.inFlight.contains(changedOverview)) {
				this.dirty.computeIfAbsent(changedOverview, k -> new Dirty(0)).blocks.set(0);
			}
		}
        BakeScheduler.Result result;
        int uploads = 0;
        while (uploads < 2 && MapUploads.available() && (result = this.scheduler.poll()) != null) {
            TileKey completedKey = result.key();
            TileFreshness.Version version = this.jobVersions.remove(completedKey);
            if (result.failure() != null) {
                this.inFlight.remove(completedKey);
                boolean wasBackground = this.backgroundBakes.remove(completedKey);
                if (result.generation() == this.generation(completedKey)) {
                    Diorama.LOGGER.error("Failed to process tile {}; retrying with backoff", completedKey, result.failure());
                    this.retries.failed(completedKey, System.currentTimeMillis());
                    this.dirty.computeIfAbsent(completedKey, k -> new Dirty(0)).blocks.set(0, 256);
                    if (wasBackground) this.unsaved.put(completedKey, 0L);
                }
                continue;
            }
            TileImage image = result.image();
            this.retries.succeeded(completedKey);
			this.inFlight.remove(image.key());
			boolean background = this.backgroundBakes.remove(image.key());
			if (this.closed) {
				continue;
			}
			if (image.generation() != this.generation(image.key()) || !this.freshness.accepts(image.key(), version)) {
                // Requeue every part of a rejected result, including patches dirtied before that job.
                this.dirty.computeIfAbsent(image.key(), k -> new Dirty(0)).blocks.set(0, 256);
				// Baked under old colours or the other cave mode; bake it again.
				if (background) {
					this.unsaved.put(image.key(), 0L);
				}
				continue;
			}
			MapTile tile = this.tiles.get(image.key());
			if (background && tile == null) {
				// Baked only to be saved; a map that wants it will bake it again.
				if (this.store != null) {
					this.store.baked(image);
				}
				continue;
			}
			if (tile == null) {
				if (image.key().lod() > 0 && !image.isFull()) {
					if (!this.dirty.containsKey(image.key())) {
						this.emptyOverviews.add(image.key());
					}
				}
				if (!image.isFull()) {
					// The tile was evicted while its patch baked.
					continue;
				}
				tile = new MapTile();
				this.tiles.put(image.key(), tile);
			}
			tile.upload(image);
            MapUploads.take();
            uploads++;
			this.coverageVersion++;
			if (this.store != null && !image.key().isCave() && image.key().lod() == 0) {
				this.store.baked(image);
			}
		}
		if (this.trimStuck && System.currentTimeMillis() - this.lastTrimMs >= TRIM_RETRY_MS) {
			this.trimChunks();
		}

		BlockLooks looks = BlockLooks.get();
		if (looks != null && this.topY != Integer.MIN_VALUE) {
			long now = System.currentTimeMillis();
			int limit = 6;
			List<Map.Entry<TileKey, Double>> order = new ArrayList<>(this.wanted.entrySet());
			order.sort(Map.Entry.comparingByValue());
			for (Map.Entry<TileKey, Double> entry : order) {
				if (this.inFlight.size() >= limit) {
					break;
				}
				TileKey key = entry.getKey();
				if (this.inFlight.contains(key) || !this.retries.ready(key, now)) {
					continue;
				}
				boolean missing = !this.tiles.containsKey(key);
				if (key.lod() > 0) {
					// Overview tiles come from disk, shrunk from saved tiles; nothing to bake.
					if ((missing || this.dirty.containsKey(key)) && this.store != null && !this.emptyOverviews.contains(key)) {
						this.dirty.remove(key);
						this.loadOverview(key, this.store);
					}
					continue;
				}
				Dirty d = this.dirty.get(key);
				boolean ready = d != null && (now - d.last > QUIET_MS || now - d.first > MAX_WAIT_MS);
				if (missing || ready) {
					this.dirty.remove(key);
					this.unsaved.remove(key);
					this.bake(key, looks, missing || d.blocks.cardinality() > 160 ? null : d.blocks);
				}
			}
			this.bakeUnsaved(looks, now);
		}
		this.wanted.clear();
		this.evict();
	}

	/** Bakes one tile on a baker thread: all of it if {@code blocks} is null, else those blocks. */
	private void bake(TileKey key, BlockLooks looks, @Nullable BitSet blocks) {
		this.inFlight.add(key);
        this.jobVersions.put(key, this.freshness.get(key));
        this.submittedJobs++;
		int generation = this.generation(key);
		boolean fog = this.fogOfWar;
		Overlays overlays = this.overlays;
		TileStore store = key.isCave() ? null : this.store;
        dev.diorama.bake.BakeContext context = dev.diorama.bake.BakeContext.capture();
        this.scheduler.submit(key, generation, () -> {
            long start = System.nanoTime();
            var saved = store == null ? null : store.readPixels(key);
            TileImage image = TileBaker.bakePaired(key, this, looks, blocks, fog, saved, overlays, context);
            logBakeTime(System.nanoTime() - start, blocks == null);
            return image;
        });
	}

	private void loadOverview(TileKey key, TileStore store) {
		this.inFlight.add(key);
        this.jobVersions.put(key, this.freshness.get(key));
        this.submittedJobs++;
		int generation = this.generation(key);
        this.scheduler.submit(key, generation, () -> {
            var pixels = store.readPixels(key);
            return pixels == null ? TileImage.partial(key, List.of()) : TileBaker.imagePaired(key, pixels);
        });
	}

	/**
	 * Bakes changed surface tiles no map is showing, so they get saved. Leaves one baker free, so
	 * tiles a map is waiting for still come first.
	 */
	private void bakeUnsaved(BlockLooks looks, long now) {
		if (this.unsaved.isEmpty() || this.inFlight.size() >= Math.max(1, BAKER_COUNT - 1)) {
			return;
		}
		Iterator<Map.Entry<TileKey, Long>> it = this.unsaved.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<TileKey, Long> e = it.next();
			TileKey key = e.getKey();
			if (now - e.getValue() < BACKGROUND_QUIET_MS || this.inFlight.contains(key) || !this.retries.ready(key, now)) {
				continue;
			}
			it.remove();
			this.dirty.remove(key);
			if (!this.tiles.containsKey(key)) {
				this.backgroundBakes.add(key);
			}
			this.bake(key, looks, null);
			return;
		}
	}

	private static final java.util.concurrent.atomic.AtomicLong BAKE_NANOS = new java.util.concurrent.atomic.AtomicLong();
	private static final AtomicInteger BAKE_COUNT = new AtomicInteger();

	private static final AtomicInteger PARTIAL_COUNT = new AtomicInteger();

	private static void logBakeTime(long nanos, boolean full) {
		if (!full) {
			if (PARTIAL_COUNT.incrementAndGet() % 50 == 0) {
				Diorama.LOGGER.info("Re-baked {} partial tiles", PARTIAL_COUNT.get());
			}
			return;
		}
		long total = BAKE_NANOS.addAndGet(nanos);
		int count = BAKE_COUNT.incrementAndGet();
		if (count % 25 == 0) {
			Diorama.LOGGER.info("Baked {} full tiles, {} ms average", count, total / count / 1_000_000);
		}
	}

    private void evict() {
        this.tiles.evict(this.frame, MAX_GPU_TILES, key -> {
            this.coverageVersion++;
            if (!this.inFlight.contains(key)) this.freshness.remove(key);
            if (this.dirty.remove(key) != null && this.store != null && !key.isCave() && key.lod() == 0)
                this.unsaved.put(key, 0L);
        });
    }

	/** Saves fog-of-war progress if it changed. Call now and then; tiles save themselves. */
	public void saveSeen() {
		if (this.folder != null) {
			this.seen.saveAsync(this.folder.resolve("seen.bin"));
		}
	}

    public void close() {
        if (this.closed) return;
        this.closed = true;
        if (this.store != null) this.retireStore(this.store, true);
        else this.saveSeen();
        for (MapTile tile : this.tiles.values()) tile.close();
        this.tiles.clear();
        this.coverageVersion++;
        this.scheduler.idle().whenComplete((ignored, failure) -> this.chunks.clear());
    }

    /** Freeze ownership now; finish all pending surface work away from the client thread. */
    private void retireStore(TileStore retiring, boolean saveVisibility) {
        Set<TileKey> pending = new HashSet<>(this.unsaved.keySet());
        for (TileKey key : this.inFlight) if (!key.isCave() && key.lod() == 0) pending.add(key);
        // Dirty visible tiles also need a final full bake, even if their debounce hasn't elapsed.
        for (TileKey key : this.dirty.keySet()) if (!key.isCave() && key.lod() == 0) pending.add(key);
        if (saveVisibility && this.folder != null) this.seen.saveFinalAsync(this.folder.resolve("seen.bin"));
        var visibility = this.seen.snapshot();
        var source = new dev.diorama.bake.FrozenChunks(this.chunks, this.minY, this.maxY, this.topY, this.roof, visibility, this.nightLighting);
        var context = this.bakeContext;
        BlockLooks looks = BlockLooks.get();
        boolean fog = this.roofless() ? this.storeName.contains("-fog") : this.fogOfWar;
        Overlays overlays = this.overlays;
        java.util.List<TileImage> completed = new ArrayList<>();
        BakeScheduler.Result result;
        while ((result = this.scheduler.poll()) != null) {
            this.inFlight.remove(result.key());
            this.backgroundBakes.remove(result.key());
            if (result.failure() == null && !result.key().isCave() && result.key().lod() == 0
                && result.generation() == this.generation(result.key())) completed.add(result.image());
        }
        var save = java.util.concurrent.CompletableFuture.allOf(this.scheduler.idle(), this.seen.saving()).handle((ignored, failure) -> null).thenRunAsync(() -> {
            for (TileImage image : completed) retiring.baked(image);
            for (TileKey key : pending) {
                if (looks == null) break;
                try {
                    var saved = retiring.readPixels(key);
                    retiring.baked(TileBaker.bakePaired(key, source, looks, null, fog, saved, overlays, context));
                } catch (RuntimeException failure) {
                    Diorama.LOGGER.error("Couldn't finish pending map tile {}", key, failure);
                }
            }
        }, SAVER).thenCompose(ignored -> retiring.closeAsync());
        retiring.finishing(save);
        SAVES.removeIf(java.util.concurrent.CompletableFuture::isDone);
        SAVES.add(save);
        save.exceptionally(failure -> { Diorama.LOGGER.error("Couldn't finish saving map", failure); return null; });
    }

    /** Only the application shutdown hook waits; changing worlds and settings stays asynchronous. */
    public static void awaitSaves() {
        for (var save : SAVES) {
            try { save.join(); }
            catch (java.util.concurrent.CompletionException failure) { Diorama.LOGGER.error("Map save failed", failure); }
        }
        SAVES.removeIf(java.util.concurrent.CompletableFuture::isDone);
    }

	public int bakingCount() {
		return this.inFlight.size();
	}
}
