package dev.diorama.preview;

import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SeedPreviewTest {
	@TempDir Path folder;
	private static void await(java.util.function.BooleanSupplier ready) throws Exception {
		long until = System.nanoTime() + 5_000_000_000L;
		while (!ready.getAsBoolean() && System.nanoTime() < until) Thread.sleep(10);
		assertTrue(ready.getAsBoolean(), "Preview did not become ready");
	}
	@Test void negativeCoordinatesCacheAndDiskReloadDoNotRepeatSampling() throws Exception {
		var calls = new AtomicInteger();
		var bounds = new SeedPreview.Bounds(-4000, -4000, -100, -100, false);
		try (var preview = new SeedPreview(() -> new SeedPreview.Source((x, z) -> {
			assertTrue(x < 0 && z < 0); calls.incrementAndGet(); return 3;
		}, "test-world"), folder)) {
			await(() -> { preview.request(bounds); return preview.sampleCached(-2000, -2000) == 3; });
			assertEquals(256, calls.get());
			for (int i = 0; i < 100; i++) { preview.request(bounds); assertEquals(3, preview.cursor().sample(-2000, -2000)); }
			assertEquals(256, calls.get());
			await(() -> Files.exists(folder.resolve("test-world/-1_-1.biomes")));
		}
		try (var preview = new SeedPreview(() -> new SeedPreview.Source((x, z) -> {
			fail("A persisted coarse region must not resample biomes"); return 1;
		}, "test-world"), folder)) {
			await(() -> { preview.request(bounds); return preview.sampleCached(-2000, -2000) == 3; });
			assertEquals(0, preview.samples());
		}
	}

	@Test void refinementReusesCoarseSamplesAndDoesNotTouchAnotherWorld() throws Exception {
		var bounds = new SeedPreview.Bounds(20, 20, 100, 100, true);
		try (var preview = new SeedPreview(() -> new SeedPreview.Source((x, z) -> 2, "other-world"), folder)) {
			await(() -> { preview.request(bounds); return preview.samples() == 4096 && preview.revision() >= 2; });
			assertEquals(2, preview.sampleCached(63, 63));
			assertEquals(0, preview.sampleCached(-1, 0));
			long count = preview.samples();
			preview.request(null);
			assertEquals(count, preview.samples());
		}
	}

	@Test void missingSeedAndCorruptCacheRemainRecoverable() throws Exception {
		try (var preview = new SeedPreview(() -> null, folder)) {
			await(() -> preview.unavailable() != null);
			preview.request(new SeedPreview.Bounds(0, 0, 1, 1, false));
			assertEquals(0, preview.sampleCached(0, 0));
		}
		Files.createDirectories(folder.resolve("corrupt"));
		Files.write(folder.resolve("corrupt/0_0.biomes"), new byte[268]);
		try (var preview = new SeedPreview(() -> new SeedPreview.Source((x, z) -> 5, "corrupt"), folder)) {
			await(() -> { preview.request(new SeedPreview.Bounds(0, 0, 1, 1, false)); return preview.sampleCached(0, 0) == 5; });
			assertEquals(256, preview.samples());
		}
	}
}
