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
    void evict(long frame, int maxTiles, Consumer<TileKey> evicted) {
		int resident = 0;
		for (MapTile tile : this.values()) {
			if (!tile.isEmpty()) {
				resident++;
			}
		}
		if (resident <= maxTiles && frame % 600 != 0) {
			return;
		}
		List<Map.Entry<TileKey, MapTile>> entries = new ArrayList<>(this.entrySet());
		entries.sort(Comparator.comparingLong(e -> e.getValue().lastDrawn));
		Iterator<Map.Entry<TileKey, MapTile>> it = entries.iterator();
		while (it.hasNext()) {
			Map.Entry<TileKey, MapTile> e = it.next();
			boolean stale = frame - e.getValue().lastDrawn > 3600;
			if (resident <= maxTiles * 3 / 4 && !stale) {
				break;
			}
			if (!e.getValue().isEmpty()) {
				resident--;
			}
			e.getValue().close();
			this.remove(e.getKey());
            evicted.accept(e.getKey());
		}
    }
}
