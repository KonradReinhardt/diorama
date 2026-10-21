package dev.diorama.data;

import net.minecraft.world.level.chunk.DataLayer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LightSnapshotTest {
	@Test void repeatedUniformAndPackedLightAreEqualButTorchRemovalIsDetected() {
		var uniform = new DataLayer(15);
		var packed = new DataLayer(new byte[2048]);
		packed.fill(15);
		packed.getData();
		assertTrue(ChunkSnapshot.sameLight(uniform, packed));
		assertTrue(ChunkSnapshot.sameLight(packed, packed.copy()));
		packed.set(15, 15, 15, 0);
		assertFalse(ChunkSnapshot.sameLight(uniform, packed));
		assertFalse(ChunkSnapshot.sameLight(null, new DataLayer(15)));
		assertTrue(ChunkSnapshot.sameLight(null, null));
	}
}
