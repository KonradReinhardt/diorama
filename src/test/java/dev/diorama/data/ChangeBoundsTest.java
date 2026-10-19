package dev.diorama.data;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ChangeBoundsTest {
    @Test void unionsCoordinatesAndPreservesBothKindsOfChanges() {
        var first = new ChangeBounds(-17, -64, 0, -16, -60, 15, 1);
        var second = new ChangeBounds(0, 20, -32, 16, 30, -16, 2);
        assertEquals(new ChangeBounds(-17, -64, -32, 16, 30, 15, 3), first.union(second));
        assertEquals(first.union(second), second.union(first));
    }
}
