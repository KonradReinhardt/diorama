package dev.diorama.data;

import dev.diorama.Projection;
import dev.diorama.bake.TileImage;
import dev.diorama.bake.TileKey;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class TileStoreTest {
    @TempDir Path folder;
    private static TileImage image(TileKey key, int color) {
        int[] pixels = new int[Projection.TILE_PIXELS * Projection.TILE_PIXELS];
        Arrays.fill(pixels, color);
        return TileImage.full(key, new int[][] {pixels}, new byte[0]);
    }
    @Test void appearanceBarrierRebuildsParentsFromTheNewChildrenBeforeCompleting() {
        var key = new TileKey(TileKey.SURFACE, 0, 0);
        TileStore store = new TileStore(folder);
        try {
            store.baked(image(key, 0xFF0000FF));
            store.refreshOverviews().join();
            assertEquals(0xFF0000FF, store.read(key.parent())[0]);
            store.baked(image(key, 0xFFFF0000));
            store.refreshOverviews().join();
            assertEquals(0xFFFF0000, store.read(key.parent())[0]);
            assertEquals(0xFFFF0000, store.read(key.parent().parent())[0]);
        } finally { store.close(); }
    }
    @Test void closingFlushesHandedOverImagesAndKeepsPartialPatches() {
        var key = new TileKey(TileKey.SURFACE, -1, -2);
        TileStore store = new TileStore(folder);
        store.baked(image(key, 0xFF123456));
        int[] patch = new int[TileImage.BLOCK * TileImage.BLOCK];
        Arrays.fill(patch, 0xFFABCDEF);
        store.baked(TileImage.partial(key, List.of(new TileImage.Patch(1, 1, new int[][] {patch}, new byte[0], null))));
        store.close();
        TileStore reopened = new TileStore(folder);
        int[] pixels = reopened.read(key);
        assertEquals(0xFF123456, pixels[0]);
        assertEquals(0xFFABCDEF, pixels[TileImage.BLOCK * Projection.TILE_PIXELS + TileImage.BLOCK]);
        reopened.close();
    }
    @Test void reopeningWaitsForRetiringProducerWithoutBlockingConstruction() {
        var key = new TileKey(TileKey.SURFACE, 0, 0);
        TileStore old = new TileStore(folder);
        var producer = new CompletableFuture<Void>();
        var completion = producer.thenRun(() -> old.baked(image(key, 0xFF123456))).thenCompose(ignored -> old.closeAsync());
        old.finishing(completion);
        TileStore next = new TileStore(folder);
        var read = CompletableFuture.supplyAsync(() -> next.read(key));
        assertFalse(read.isDone());
        producer.complete(null);
        assertEquals(0xFF123456, read.join()[0]);
        next.close();
    }
    @Test void failedWriteRetainsPixelsForTheNextFlush() throws Exception {
        var key = new TileKey(TileKey.SURFACE, 0, 0);
        TileStore store = new TileStore(folder);
        store.baked(image(key, 0xFF654321));
        assertNotNull(store.read(key));
        Path blocker = folder.resolve("0_0.omt2.tmp");
        Files.createDirectory(blocker);
        store.write(key);
        assertEquals(0xFF654321, store.read(key)[0]);
        Files.delete(blocker);
        store.close();
        TileStore reopened = new TileStore(folder);
        assertEquals(0xFF654321, reopened.read(key)[0]);
        reopened.close();
    }
    @Test void damagedTileIsTreatedAsUnknown() throws Exception {
        Files.write(folder.resolve("0_0.omt"), new byte[] {1, 2, 3});
        TileStore store = new TileStore(folder);
        assertNull(store.read(new TileKey(TileKey.SURFACE, 0, 0)));
        store.close();
    }
}
