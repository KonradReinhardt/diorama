package dev.diorama.render;
import dev.diorama.bake.TileKey;
import dev.diorama.bake.TileImage;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class BakeSchedulerTest {
    @Test void separatesFailureFromAnEmptyTile() {
        var key = new TileKey(TileKey.SURFACE, -1, 2);
        var scheduler = new BakeScheduler(Runnable::run);
        scheduler.submit(key, 7, () -> { throw new IllegalStateException("disk unavailable"); });
        var failed = scheduler.poll();
        assertNotNull(failed.failure());
        assertNull(failed.image());
        scheduler.submit(key, 8, () -> TileImage.partial(key, List.of()));
        var empty = scheduler.poll();
        assertNull(empty.failure());
        assertEquals(8, empty.image().generation());
        assertDoesNotThrow(() -> scheduler.idle().join());
    }
    @Test void backsOffAndRecoversAfterSuccess() {
        var key = new TileKey(0, 0, 0);
        var retry = new RetryBackoff();
        retry.failed(key, 0);
        assertFalse(retry.ready(key, 999));
        assertTrue(retry.ready(key, 1000));
        retry.failed(key, 1000);
        assertFalse(retry.ready(key, 2999));
        retry.succeeded(key);
        assertTrue(retry.ready(key, 1000));
    }
}
