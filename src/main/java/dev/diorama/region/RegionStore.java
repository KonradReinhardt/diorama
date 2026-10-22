package dev.diorama.region;

import com.google.gson.JsonParser;
import dev.diorama.Diorama;
import dev.diorama.WorldKey;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The regions of one world, saved as JSON in {@code regions.json} beside the waypoints (see
 * {@link WorldKey#folder}), with the history of the player's changes for undo and redo. Every
 * change goes through {@link #commit}, which saves at once. A file written by a newer version is
 * read but never written over: the regions show, and changing them is refused. Client thread only.
 */
public final class RegionStore {
	/** How many changes undo reaches back. */
	public static final int HISTORY = 100;

	private static @Nullable RegionStore current;
	/** Counts every change of every store, so a store loaded again never repeats a revision. */
	private static long changes;

	/** One change: a region as it was and as it became; null before for an added one, null after for a removed one. */
	public record Change(@Nullable MapRegion before, @Nullable MapRegion after) {
		UUID id() {
			return this.before != null ? this.before.id : this.after.id;
		}

		String dimension() {
			return this.before != null ? this.before.dimension : this.after.dimension;
		}
	}

	private final @Nullable String worldKey;
	private final Path file;
	private final List<MapRegion> regions = new ArrayList<>();
	private boolean readOnly;
	private long revision;
	private final Deque<Change> undo = new ArrayDeque<>();
	private final Deque<Change> redo = new ArrayDeque<>();
	/** The dimension the history is about; changing another starts it over. */
	private @Nullable String historyDimension;

	private RegionStore(@Nullable String worldKey, Path file) {
		this.worldKey = worldKey;
		this.file = file;
		this.load();
		this.revision = ++changes;
	}

	/** The store for the world the client is in, loading it when the world changes. */
	public static @Nullable RegionStore get() {
		String key = WorldKey.current();
		if (key == null) {
			current = null;
			return null;
		}
		if (current == null || !key.equals(current.worldKey)) {
			current = new RegionStore(key, WorldKey.folder(key).resolve("regions.json"));
		}
		return current;
	}

	/** A store over any file, outside the current-world lookup. For tests and tools. */
	public static RegionStore at(Path file) {
		return new RegionStore(null, file);
	}

	public List<MapRegion> all() {
		return this.regions;
	}

	public List<MapRegion> in(String dimension) {
		List<MapRegion> out = new ArrayList<>();
		for (MapRegion region : this.regions) {
			if (region.dimension.equals(dimension)) {
				out.add(region);
			}
		}
		return out;
	}

	/**
	 * The region shown on maps that a world point is in, the smallest where several are, or null.
	 * With {@code minimap}, only those shown on the minimap.
	 */
	public @Nullable MapRegion containing(String dimension, double x, double z, boolean minimap) {
		MapRegion best = null;
		for (MapRegion region : this.regions) {
			if (!region.dimension.equals(dimension) || !region.visible || minimap && !region.onMinimap) {
				continue;
			}
			double[] b = region.bounds();
			if (x < b[0] || z < b[1] || x > b[2] || z > b[3] || best != null && region.area() >= best.area()) {
				continue;
			}
			if (RegionGeometry.contains(region.rings, x, z)) {
				best = region;
			}
		}
		return best;
	}

	public @Nullable MapRegion find(UUID id) {
		for (MapRegion region : this.regions) {
			if (region.id.equals(id)) {
				return region;
			}
		}
		return null;
	}

	/** Whether the file came from a newer version, so changes are refused rather than lose what it holds. */
	public boolean readOnly() {
		return this.readOnly;
	}

	/** Goes up with every change, and differs between stores, so drawings know to redo their masks. */
	public long revision() {
		return this.revision;
	}

	/**
	 * Applies a change and keeps it for undo: {@code before} is the region as it was (a
	 * {@link MapRegion#copy}) or null to add {@code after}; {@code after} null removes it. False
	 * when the store is read-only.
	 */
	public boolean commit(@Nullable MapRegion before, @Nullable MapRegion after) {
		if (this.readOnly || before == null && after == null) {
			return false;
		}
		Change change = new Change(before == null ? null : before.copy(), after == null ? null : after.copy());
		if (!change.dimension().equals(this.historyDimension)) {
			this.undo.clear();
			this.redo.clear();
			this.historyDimension = change.dimension();
		}
		this.undo.push(change);
		while (this.undo.size() > HISTORY) {
			this.undo.removeLast();
		}
		this.redo.clear();
		this.apply(change.id(), change.after());
		return true;
	}

	public boolean canUndo(String dimension) {
		return !this.readOnly && dimension.equals(this.historyDimension) && !this.undo.isEmpty();
	}

	public boolean canRedo(String dimension) {
		return !this.readOnly && dimension.equals(this.historyDimension) && !this.redo.isEmpty();
	}

	/** Takes back the last change made in this dimension; false when there is none. */
	public boolean undo(String dimension) {
		if (!this.canUndo(dimension)) {
			return false;
		}
		Change change = this.undo.pop();
		this.redo.push(change);
		this.apply(change.id(), change.before());
		return true;
	}

	/** Makes again the last change undone in this dimension; false when there is none. */
	public boolean redo(String dimension) {
		if (!this.canRedo(dimension)) {
			return false;
		}
		Change change = this.redo.pop();
		this.undo.push(change);
		this.apply(change.id(), change.after());
		return true;
	}

	/** Puts a copy of {@code state} where the region with this id is (or at the end), or removes it for null. */
	private void apply(UUID id, @Nullable MapRegion state) {
		int at = -1;
		for (int i = 0; i < this.regions.size(); i++) {
			if (this.regions.get(i).id.equals(id)) {
				at = i;
				break;
			}
		}
		if (state == null) {
			if (at >= 0) this.regions.remove(at);
		} else if (at >= 0) {
			this.regions.set(at, state.copy());
		} else {
			this.regions.add(state.copy());
		}
		this.revision = ++changes;
		this.save();
	}

	/**
	 * Reads the file one region at a time, so a single bad entry costs only itself. If anything
	 * couldn't be read, the file is copied aside first: the next save rewrites it, and the copy
	 * keeps whatever was lost.
	 */
	private void load() {
		if (!Files.isRegularFile(this.file)) {
			return;
		}
		boolean damaged;
		try (Reader reader = Files.newBufferedReader(this.file, StandardCharsets.UTF_8)) {
			RegionJson.Contents contents = RegionJson.read(JsonParser.parseReader(reader));
			this.regions.addAll(contents.regions());
			damaged = contents.damaged();
			if (contents.version() > RegionJson.VERSION) {
				this.readOnly = true;
				Diorama.LOGGER.warn("{} was written by a newer version (format {}); its regions are shown but not changed", this.file, contents.version());
			}
		} catch (Exception e) {
			damaged = true;
			Diorama.LOGGER.error("Couldn't read regions from {}", this.file, e);
		}
		if (damaged && !this.readOnly) {
			Path backup = this.file.resolveSibling("regions.json.unreadable-" + System.currentTimeMillis());
			try {
				Files.copy(this.file, backup);
				Diorama.LOGGER.warn("Kept a copy of the unreadable regions file at {}", backup);
			} catch (IOException e) {
				Diorama.LOGGER.error("Couldn't back up {}", this.file, e);
			}
		}
	}

	/** Writes to a temporary file and moves it over the old one, so a crash can't leave half a file. */
	private void save() {
		if (this.readOnly) {
			return;
		}
		try {
			Files.createDirectories(this.file.getParent());
			Path temp = this.file.resolveSibling("regions.json.tmp");
			try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
				writer.write(RegionJson.write(this.regions));
			}
			Files.move(temp, this.file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException | RuntimeException e) {
			Diorama.LOGGER.error("Couldn't save regions to {}", this.file, e);
		}
	}
}
