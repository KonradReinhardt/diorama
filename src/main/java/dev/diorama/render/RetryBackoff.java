package dev.diorama.render;

import java.util.HashMap;
import java.util.Map;
import dev.diorama.bake.TileKey;

/** Client-thread retry state. A new world gets a new state, and success forgets failures. */
final class RetryBackoff {
    private record Failure(int count, long due) {}
    private final Map<TileKey, Failure> failures = new HashMap<>();
    boolean ready(TileKey key, long now) {
        Failure failure = this.failures.get(key);
        return failure == null || now >= failure.due;
    }
    void failed(TileKey key, long now) {
        Failure previous = this.failures.get(key);
        int count = previous == null ? 1 : Math.min(6, previous.count + 1);
        this.failures.put(key, new Failure(count, now + Math.min(30_000, 1000L << (count - 1))));
    }
    void succeeded(TileKey key) { this.failures.remove(key); }
}
