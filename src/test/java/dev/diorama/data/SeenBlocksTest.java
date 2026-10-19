package dev.diorama.data;
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.DataInputStream;
import java.util.zip.InflaterInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class SeenBlocksTest {
    @TempDir Path folder;
    @Test void quickReopenRetainsLiveVisibilityAndOrdersFinalWrites() throws Exception {
        Path file = folder.resolve("seen.bin");
        SeenBlocks first = SeenBlocks.load(file);
        first.markSeen(-16, -16, -16);
        first.saveFinalAsync(file);
        SeenBlocks second = SeenBlocks.load(file);
        assertSame(first, second);
        assertTrue(second.isSeen(-16, -16, -16));
        second.markSeen(16, 16, 16);
        second.saveFinalAsync(file);
        second.saving().join();
        try (var in = new DataInputStream(new InflaterInputStream(Files.newInputStream(file)))) {
            in.readInt();
            assertEquals(2, in.readInt());
            for (int section = 0; section < 2; section++) {
                in.readLong();
                assertEquals(1, in.readLong());
                for (int word = 1; word < 64; word++) assertEquals(0, in.readLong());
            }
        }
    }
}
