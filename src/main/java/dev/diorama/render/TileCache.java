package dev.diorama.render;
import dev.diorama.bake.TileKey;
import java.util.*;
import java.util.function.Consumer;

/** Render-thread ownership and eviction of GPU tile resources. */
final class TileCache extends AbstractMap<TileKey, MapTile> {
    private final Map<TileKey, MapTile> entries = new HashMap<>();
    @Override public Set<Entry<TileKey, MapTile>> entrySet() { return this.entries.entrySet(); }
    @Override public MapTile put(TileKey key, MapTile value) { return this.entries.put(key, value); }
    @Override public MapTile get(Object key) { return this.entries.get(key); }
    @Override public boolean containsKey(Object key) { return this.entries.containsKey(key); }
    @Override public MapTile remove(Object key) { return this.entries.remove(key); }
    /**
     * Frees the least recently drawn tiles once over {@code maxTiles} or the GPU budget, down to
     * three quarters of either. Tiles a view drew at or after {@code keepSince} stay whatever the
     * budget says: dropping a tile on screen only makes it load again the next frame, over and over,
     * with the fog lifting from it each time. Views pick a level of detail whose tiles fit the
     * budget ({@code MapViewport.lodFor}), so what they draw stays bounded.
     */
    void evict(long frame, long keepSince, int maxTiles, Consumer<TileKey> evicted) {
		int resident = 0;
        long bytes = this.values().stream().mapToLong(MapTile::gpuBytes).sum();
		for (MapTile tile : this.values()) {
			if (!tile.isEmpty()) {
				resident++;
			}
		}
		if (resident <= maxTiles && bytes <= TextureMemory.BUDGET && frame % 600 != 0) {
			return;
		}
		List<Map.Entry<TileKey, MapTile>> entries = new ArrayList<>(this.entrySet());
		entries.sort(Comparator.comparingLong(e -> e.getValue().lastDrawn));
		Iterator<Map.Entry<TileKey, MapTile>> it = entries.iterator();
		while (it.hasNext()) {
			Map.Entry<TileKey, MapTile> e = it.next();
			if (e.getValue().lastDrawn >= keepSince) {
				break;
			}
			boolean stale = frame - e.getValue().lastDrawn > 3600;
			if (resident <= maxTiles * 3 / 4 && bytes <= TextureMemory.BUDGET * 3 / 4 && !stale) {
				break;
			}
			if (!e.getValue().isEmpty()) {
				resident--;
			}
			bytes -= e.getValue().gpuBytes();
            e.getValue().close();
			this.remove(e.getKey());
            evicted.accept(e.getKey());
		}
    }
}
