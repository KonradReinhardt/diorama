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
import net.minecraft.client.Minecraft;
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
    private @Nullable AppearanceRefresh appearanceRefresh;
    private long appearanceVersion;
    public long appearanceVersion() { return appearanceVersion; }
    public boolean refreshingAppearance() { return appearanceRefresh != null; }
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

	/**
	 * The chunks the game saved, in singleplayer: tiles whose chunks aren't loaded are redrawn from
	 * them when the map's look changes, instead of keeping old pixels. Null where unavailable.
	 */
	private volatile dev.diorama.bake.@Nullable SavedTerrain terrain;
	/** Which saved tiles already show the current look; see {@link dev.diorama.data.AppearanceStamps}. */
	private dev.diorama.data.@Nullable AppearanceStamps stamps;
	/** Saved tiles not drawn with the current look yet, found by a background scan of the store. */
	private final Set<TileKey> staleSaved = ConcurrentHashMap.newKeySet();
	/** Bumped when the stale scan must start over: a new look or another store. */
	private int staleScan;
	private boolean staleScanned;
	/** Redraws of stale tiles in flight. */
	private final Set<TileKey> staleInFlight = new HashSet<>();
	private final List<Map.Entry<TileKey, Double>> staleOrder = new ArrayList<>();
	/** Full bakes that drew their whole tile, by key, with the generation they were submitted under. */
	private final ConcurrentHashMap<TileKey, Integer> completeBakes = new ConcurrentHashMap<>();

	/** Live chunks first, then the game's saved ones; for redrawing tiles with a new look. */
	private final ChunkSource withSaved = new ChunkSource() {
		@Override public @Nullable ChunkSnapshot chunk(int chunkX, int chunkZ) {
			ChunkSnapshot live = MapLevel.this.chunks.get(ChunkSnapshot.key(chunkX, chunkZ));
			if (live != null) return live;
			dev.diorama.bake.SavedTerrain saved = MapLevel.this.terrain;
			return saved == null ? null : saved.load(chunkX, chunkZ);
		}
		@Override public int minY() { return MapLevel.this.minY; }
		@Override public int maxY() { return MapLevel.this.maxY; }
		// Saved terrain can stand above anything loaded now: rays start at the top of the world.
		@Override public int topY() { return MapLevel.this.maxY; }
		@Override public int roof() { return MapLevel.this.roof; }
		@Override public boolean nightLighting() { return MapLevel.this.nightLighting; }
		@Override public boolean isSeen(int x, int y, int z) { return MapLevel.this.seen.isSeen(x, y, z); }
	};

	/** Starts using the game's saved chunks for redrawing; see {@link #terrain}. Render thread. */
	public void setSavedTerrain(dev.diorama.bake.@Nullable SavedTerrain terrain) {
		dev.diorama.bake.SavedTerrain previous = this.terrain;
		this.terrain = terrain;
		if (previous != null && previous != terrain) previous.close();
		this.restartStaleScan();
	}

	/** Saved tiles fresh under the current look, or none known yet. */
	private void restartStaleScan() {
		this.staleScan++;
		this.staleScanned = false;
		this.staleSaved.clear();
		this.staleOrder.clear();
	}

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
        // New overlay data is published as a complete revision too.
        this.colorsChanged();
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
		this.stamps = dev.diorama.data.AppearanceStamps.load(this.stampsFile(), this.bakeContext.appearance());
		this.restartStaleScan();
		return opened;
	}

	/**
	 * Where the sky view's tiles are saved: one folder per kind of view, since their pixels mean
	 * different things and saved pixels are merged into fresh bakes. The camera angle moves where
	 * pixels land. Under a roof, the view is a different picture, and with fog of war it may only
	 * hold what the player has seen. Since the block grid ({@link Projection}) every folder name
	 * says "grid": "surface", "surface-50" and "under-roof-50" hold maps from the projection
	 * before it, and stay as they were.
	 */
	private String storeName() {
		if (this.roofless()) {
			return "under-roof-grid-" + pitchName() + (this.fogOfWar ? "-fog" : "") + directionSuffix();
		}
		return surfaceStoreName();
	}

	/** The folder of the surface map for the current camera, under a world's map folder. */
	public static String surfaceStoreName() {
		return "surface-grid-" + pitchName() + directionSuffix();
	}

	private static long pitchName() {
		return Math.round(dev.diorama.MapStyle.get().pitchDegrees());
	}

    private Path stampsFile() {
        return this.folder.resolve(this.storeName).resolve("appearance.bin");
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
        this.appearanceRefresh = null;
        // Projection changes switch stores below, which bring their own stamps.
        if (this.stamps != null && !reproject) this.stamps.reset(next.appearance());
        this.restartStaleScan();
        if (reproject) {
            this.dropTiles(false);
            this.bakeContext = next;
            this.switchStoreIfNeeded();
        } else {
            // Keep both texture and exploration coverage until their replacements are uploaded.
            // Pending old jobs must not erase these tiles when their generations are rejected.
            Set<TileKey> refresh = new HashSet<>(this.tiles.keySet());
            refresh.addAll(this.inFlight);
            refresh.removeAll(this.backgroundBakes);
            this.appearanceRefresh = new AppearanceRefresh(refresh);
            this.requestAppearanceSources(this.appearanceRefresh);
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
                if (this.appearanceRefresh != null) this.appearanceRefresh.forget(e.getKey());
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
                boolean refreshSource = this.appearanceRefresh != null && this.appearanceRefresh.tracks(key);
                if (refreshSource) this.appearanceRefresh.pending.add(key);
				if (!this.tiles.containsKey(key) && !this.inFlight.contains(key) && !refreshSource) {
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
        if (this.appearanceRefresh != null && this.appearanceRefresh.diskOnly.remove(key)) {
            this.appearanceRefresh.pending.add(key);
            this.dirty.computeIfAbsent(key, k -> new Dirty(0)).blocks.set(0, 256);
        }
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
        while (uploads < 3 && MapUploads.available() && (result = this.scheduler.poll()) != null) {
            TileKey completedKey = result.key();
            TileFreshness.Version version = this.jobVersions.remove(completedKey);
            this.staleInFlight.remove(completedKey);
            Integer completeGeneration = this.completeBakes.remove(completedKey);
            boolean complete = completeGeneration != null && completeGeneration == result.generation();
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
            if (this.appearanceRefresh != null && (!background || this.appearanceRefresh.pending.contains(image.key()))) {
                // Hold the old map intact while this revision is prepared in the background.
                if (image.key().lod() > 0 && (this.appearanceRefresh.overviewBarrier == null
                    || !this.appearanceRefresh.overviewBarrier.isDone())) {
                    this.dirty.computeIfAbsent(image.key(), k -> new Dirty(0)).blocks.set(0);
                } else if (image.isFull()) {
                    boolean diskOnly = this.appearanceRefresh.diskOnly.contains(image.key());
                    this.appearanceRefresh.stage(image, version, !diskOnly);
                    if (complete) this.appearanceRefresh.complete.add(image.key());
                    else this.appearanceRefresh.complete.remove(image.key());
                    if (diskOnly && this.store != null) { this.store.baked(image); this.persisted(image.key(), complete); }
                }
                else this.appearanceRefresh.forget(image.key());
                continue;
            }
			MapTile tile = this.tiles.get(image.key());
			if (background && tile == null) {
				// Baked only to be saved; a map that wants it will bake it again.
				if (this.store != null) {
					this.store.baked(image);
					this.persisted(image.key(), complete && image.isFull());
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
				this.persisted(image.key(), complete && image.isFull());
			}
		}
		if (this.trimStuck && System.currentTimeMillis() - this.lastTrimMs >= TRIM_RETRY_MS) {
			this.trimChunks();
		}

        this.finishAppearanceRefresh();
		BlockLooks looks = BlockLooks.get();
		if (looks != null && this.topY != Integer.MIN_VALUE) {
			long now = System.currentTimeMillis();
			// Enough queued work to keep every baker busy while uploads drain; disk-only tiles are quick.
			int limit = Math.max(6, BAKER_COUNT * 3);
            if (this.appearanceRefresh != null) {
                for (TileKey key : this.appearanceRefresh.pending) this.wanted.putIfAbsent(key, Double.MAX_VALUE);
                if (this.appearanceRefresh.overviewBarrier != null && this.appearanceRefresh.overviewBarrier.isDone()) {
                    for (TileKey key : this.appearanceRefresh.overviews) this.wanted.putIfAbsent(key, Double.MAX_VALUE);
                }
            }
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
                // Staged cache misses are ready for the transaction, even though the old
                // visible cache deliberately has no entry for them yet.
                if (this.appearanceRefresh != null && this.appearanceRefresh.ready(key) && !this.dirty.containsKey(key)) continue;
				boolean missing = !this.tiles.containsKey(key);
				if (key.lod() > 0) {
                    if (this.appearanceRefresh != null && (this.appearanceRefresh.overviewBarrier == null
                        || !this.appearanceRefresh.overviewBarrier.isDone())) continue;
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
                    if (this.appearanceRefresh != null && this.appearanceRefresh.diskOnly.contains(key)) this.backgroundBakes.add(key);
					this.bake(key, looks, this.appearanceRefresh != null || missing || d.blocks.cardinality() > 160 ? null : d.blocks);
				}
			}
			this.bakeUnsaved(looks, now);
			this.redrawStale(looks);
		}
		this.wanted.clear();
		this.evict();
	}

    /** A distant view may have no detailed GPU tiles. Refresh its loaded children on disk too. */
    private void requestAppearanceSources(AppearanceRefresh refresh) {
        if (this.store == null || refresh.overviews.isEmpty()) return;
        for (ChunkSnapshot snapshot : this.chunks.values()) {
            if (snapshot.maxY == Integer.MIN_VALUE) continue;
            int x = snapshot.chunkX << 4, z = snapshot.chunkZ << 4;
            for (var region : TileInvalidation.regions(x, Math.min(snapshot.floorY, this.topY), this.minY,
                z, x + 15, this.topY, z + 15, true, this.roofless(), this.roof,
                Set.of(TileKey.SURFACE), Projection.direction())) {
                for (int u = (int) Math.floor(region.minU() / Projection.TILE_UNITS); u <= (int) Math.floor(region.maxU() / Projection.TILE_UNITS); u++) {
                    for (int v = (int) Math.floor(region.minV() / Projection.TILE_UNITS); v <= (int) Math.floor(region.maxV() / Projection.TILE_UNITS); v++) {
                        var key = new TileKey(TileKey.SURFACE, u, v);
                        for (TileKey overview : refresh.overviews) {
                            if (Math.floorDiv(u, 1 << overview.lod()) == overview.tileU()
                                && Math.floorDiv(v, 1 << overview.lod()) == overview.tileV()) {
                                if (refresh.pending.add(key)) refresh.diskOnly.add(key);
                                this.dirty.computeIfAbsent(key, k -> new Dirty(0)).blocks.set(0, 256);
                                break;
                            }
                        }
                    }
                }
            }
        }
    }

    /** One render-thread commit: a frame can see either the old appearance or the complete new one. */
    private void finishAppearanceRefresh() {
        AppearanceRefresh refresh = this.appearanceRefresh;
        if (refresh == null || !refresh.pending.isEmpty()) return;
        for (TileKey key : new HashSet<>(refresh.versions.keySet())) {
            if (refresh.generations.get(key) != this.generation(key)
                || !this.freshness.accepts(key, refresh.versions.get(key))) {
                refresh.images.remove(key);
                refresh.versions.remove(key);
                refresh.generations.remove(key);
                if (key.lod() == 0) refresh.pending.add(key);
                refresh.overviewBarrier = null;
                for (TileKey overview : refresh.overviews) {
                    refresh.images.remove(overview);
                    this.dirty.computeIfAbsent(overview, k -> new Dirty(0)).blocks.set(0);
                }
                this.dirty.computeIfAbsent(key, k -> new Dirty(0)).blocks.set(0, 256);
            }
        }
        if (!refresh.pending.isEmpty()) return;
        if (!refresh.overviews.isEmpty()) {
            if (refresh.overviewBarrier == null) {
                if (this.inFlight.stream().anyMatch(key -> key.lod() > 0)) return;
                // Publish children to the writer first, then derive every requested overview from them.
                if (this.store != null) {
                    for (TileImage image : refresh.images.values())
                        if (!image.key().isCave() && image.key().lod() == 0) this.store.baked(image);
                    refresh.overviewBarrier = this.store.refreshOverviews();
                } else refresh.overviewBarrier = java.util.concurrent.CompletableFuture.completedFuture(null);
                return;
            }
            if (!refresh.overviewBarrier.isDone()) return;
            if (refresh.overviewBarrier.isCompletedExceptionally()) {
                Diorama.LOGGER.error("Couldn't refresh map overviews; retaining the previous appearance");
                refresh.overviewBarrier = null;
                return;
            }
            for (TileKey key : refresh.overviews) if (!refresh.images.containsKey(key)) return;
        }
        // Updating existing textures avoids a second GPU cache. The bounded resident set is
        // uploaded together here, deliberately outside the incremental terrain-upload budget.
        long start = System.nanoTime();
        for (TileImage image : refresh.images.values()) {
            MapTile tile = this.tiles.get(image.key());
            if (tile == null) { tile = new MapTile(); this.tiles.put(image.key(), tile); }
            tile.upload(image);
            if (this.store != null && !image.key().isCave() && image.key().lod() == 0) {
                this.store.baked(image);
                this.persisted(image.key(), refresh.complete.contains(image.key()));
            }
        }
        this.appearanceRefresh = null;
        this.appearanceVersion++;
        this.coverageVersion++;
        Diorama.LOGGER.info("Committed appearance revision {}: {} tiles together in {} ms",
            this.appearanceVersion, refresh.images.size(), (System.nanoTime() - start) / 1_000_000.0);
    }

	/** Bakes one tile on a baker thread: all of it if {@code blocks} is null, else those blocks. */
	private void bake(TileKey key, BlockLooks looks, @Nullable BitSet blocks) {
		this.bake(key, looks, blocks, false);
	}

	/**
	 * Same; {@code redraw} also redraws, from the game's saved chunks, pixels that loaded chunks
	 * can't, so a new look reaches the whole tile. Appearance refreshes always redraw.
	 */
	private void bake(TileKey key, BlockLooks looks, @Nullable BitSet blocks, boolean redraw) {
		this.inFlight.add(key);
        this.jobVersions.put(key, this.freshness.get(key));
        this.submittedJobs++;
		int generation = this.generation(key);
		boolean fog = this.fogOfWar;
		Overlays overlays = this.overlays;
		TileStore store = key.isCave() ? null : this.store;
        dev.diorama.bake.BakeContext context = dev.diorama.bake.BakeContext.capture();
        ChunkSource fallback = store != null && key.lod() == 0 && this.terrain != null
            && (redraw || this.appearanceRefresh != null) ? this.withSaved : null;
        this.completeBakes.remove(key);
        this.scheduler.submit(key, generation, () -> {
            long start = System.nanoTime();
            var saved = store == null ? null : store.readPixels(key);
            if (blocks == null && fallback == null && !this.chunksReach(key)) {
                // Nothing loaded can change a pixel: the bake would trace a quarter million empty
                // rays only to keep every saved pixel. Reopening a map is then just a disk read.
                if (saved == null) this.completeBakes.put(key, generation);
                return TileBaker.imagePaired(key, saved == null ? new dev.diorama.bake.TilePixels(new int[Projection.TILE_PIXELS * Projection.TILE_PIXELS], null) : saved);
            }
            if (blocks == null) {
                TileBaker.Baked baked = TileBaker.bakeFull(key, this, looks, fog, saved, overlays, context, fallback);
                if (baked.complete()) this.completeBakes.put(key, generation);
                logBakeTime(System.nanoTime() - start, true);
                return baked.image();
            }
            TileImage image = TileBaker.bakePaired(key, this, looks, blocks, fog, saved, overlays, context);
            logBakeTime(System.nanoTime() - start, blocks == null);
            return image;
        });
	}

    /** A tile's new pixels went to the store; {@code complete} ones show the current look throughout. */
    private void persisted(TileKey key, boolean complete) {
        if (complete && this.stamps != null && !key.isCave() && key.lod() == 0) {
            this.stamps.markFresh(key, this.stamps.fingerprint());
            this.staleSaved.remove(key);
        }
    }

    /**
     * Redraws, in the background and nearest the views first, saved tiles that don't show the
     * current look yet: shown tiles, and the tiles under the overviews shown when zoomed out. Waits
     * for an appearance refresh to finish, and leaves bakers free for tiles a view is missing.
     */
    private void redrawStale(BlockLooks looks) {
        TileStore store = this.store;
        var stamps = this.stamps;
        if (this.terrain == null || store == null || stamps == null || this.appearanceRefresh != null || this.closed) {
            return;
        }
        if (!this.staleScanned) {
            this.staleScanned = true;
            int scan = this.staleScan;
            SAVER.execute(() -> {
                Set<TileKey> saved = store.savedTiles();
                saved.removeIf(stamps::isFresh);
                Minecraft.getInstance().execute(() -> {
                    if (scan == this.staleScan && store == this.store) this.staleSaved.addAll(saved);
                });
            });
            return;
        }
        if (this.staleSaved.isEmpty()) {
            return;
        }
        if (this.frame % 15 == 0 || this.staleOrder.isEmpty()) {
            // Rank by the nearest view that shows the tile itself or an overview over it.
            this.staleOrder.clear();
            for (TileKey key : this.staleSaved) {
                double best = Double.MAX_VALUE;
                TileKey at = key;
                for (int lod = 0; lod <= TileKey.MAX_LOD; lod++, at = at.parent()) {
                    Double priority = this.wanted.get(at);
                    if (priority != null) best = Math.min(best, priority);
                }
                if (best < Double.MAX_VALUE) this.staleOrder.add(Map.entry(key, best));
            }
            this.staleOrder.sort(Map.Entry.comparingByValue());
        }
        boolean missing = false;
        for (TileKey key : this.wanted.keySet()) {
            if (key.lod() == 0 && !this.tiles.containsKey(key)) { missing = true; break; }
        }
        int limit = missing ? 1 : Math.max(1, BAKER_COUNT - 1);
        Iterator<Map.Entry<TileKey, Double>> it = this.staleOrder.iterator();
        while (it.hasNext() && this.staleInFlight.size() < limit && this.inFlight.size() < Math.max(6, BAKER_COUNT * 3)) {
            TileKey key = it.next().getKey();
            it.remove();
            if (this.inFlight.contains(key) || stamps.isFresh(key) || !this.staleSaved.remove(key)) {
                continue;
            }
            this.staleInFlight.add(key);
            if (!this.tiles.containsKey(key)) this.backgroundBakes.add(key);
            this.dirty.remove(key);
            this.bake(key, looks, null, true);
        }
    }

    /** Blocks around a tile a bake may read beyond its rays: shadows, sky occlusion, biome blending and outlines. */
    private static final int BAKE_REACH = 64;

    /**
     * Whether any loaded chunk could change a pixel of this level-0 tile. Conservative: covers every
     * height a ray crosses plus {@link #BAKE_REACH}. Safe on bake threads; snapshots are concurrent.
     */
    boolean chunksReach(TileKey key) {
        if (this.chunks.isEmpty()) return false;
        double sin = Projection.zScale(), cos = Projection.yScale();
        double u0 = key.minU() - BAKE_REACH, u1 = key.minU() + Projection.TILE_UNITS + BAKE_REACH;
        double v0 = key.minV(), v1 = key.minV() + Projection.TILE_UNITS;
        double d0 = (v0 + this.minY * cos) / sin - BAKE_REACH, d1 = (v1 + this.maxY * cos) / sin + BAKE_REACH;
        dev.diorama.MapDirection direction = Projection.direction();
        double x0 = Math.min(direction.worldX(u0, d0), direction.worldX(u1, d1)), x1 = Math.max(direction.worldX(u0, d0), direction.worldX(u1, d1));
        double z0 = Math.min(direction.worldZ(u0, d0), direction.worldZ(u1, d1)), z1 = Math.max(direction.worldZ(u0, d0), direction.worldZ(u1, d1));
        int cx0 = (int) Math.floor(x0) >> 4, cx1 = (int) Math.floor(x1) >> 4;
        int cz0 = (int) Math.floor(z0) >> 4, cz1 = (int) Math.floor(z1) >> 4;
        if ((long) (cx1 - cx0 + 1) * (cz1 - cz0 + 1) > this.chunks.size()) {
            for (ChunkSnapshot snapshot : this.chunks.values()) {
                if (snapshot.chunkX >= cx0 && snapshot.chunkX <= cx1 && snapshot.chunkZ >= cz0 && snapshot.chunkZ <= cz1) return true;
            }
            return false;
        }
        for (int cx = cx0; cx <= cx1; cx++) for (int cz = cz0; cz <= cz1; cz++) {
            if (this.chunks.containsKey(ChunkSnapshot.key(cx, cz))) return true;
        }
        return false;
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
            if (this.appearanceRefresh != null) this.appearanceRefresh.evicted(key, image -> {
                if (this.store != null) this.store.baked(image);
            });
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
        if (this.terrain != null) { this.terrain.close(); this.terrain = null; }
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
        if (this.appearanceRefresh != null) for (TileImage image : this.appearanceRefresh.images.values())
            if (!image.key().isCave() && image.key().lod() == 0) completed.add(image);
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
        // Stamps vouch for pixels on disk, so they're written only once the tiles are.
        var stamps = this.stamps;
        Path stampsFile = this.folder == null ? null : this.stampsFile();
        if (stamps != null && stampsFile != null) save = save.thenRun(() -> stamps.save(stampsFile));
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

	/** Whether this saved tile was last drawn entirely with the current look. For tests. */
	public boolean showsCurrentLook(TileKey key) {
		return this.stamps != null && this.stamps.isFresh(key);
	}

	/** Saved tiles still waiting to be redrawn with the current look. For tests. */
	public int staleTiles() {
		return this.staleSaved.size() + this.staleInFlight.size();
	}

	/** Forgets every chunk snapshot, as after reopening the world far from here. For tests. */
	public void forgetSnapshots() {
		this.chunks.clear();
	}
}
