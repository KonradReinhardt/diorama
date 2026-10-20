package dev.diorama.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OpeningZoomTest {
	@Test
	void easesFromFarOutToExactlyOne() {
		OpeningZoom zoom = new OpeningZoom();
		assertEquals(1, zoom.factor(0));
		zoom.start(1_000);
		assertEquals(OpeningZoom.FROM, zoom.factor(1_000), 1e-12);
		double before = 0;
		for (long t = 0; t <= OpeningZoom.DURATION_NANOS; t += 5_000_000) {
			double f = zoom.factor(1_000 + t);
			assertTrue(f >= before, "not monotonic at " + t);
			assertTrue(f <= 1);
			before = f;
		}
		assertEquals(1, zoom.factor(1_000 + OpeningZoom.DURATION_NANOS));
		assertFalse(zoom.running(1_000 + OpeningZoom.DURATION_NANOS));
	}

	@Test
	void easesOutFasterAtTheStart() {
		OpeningZoom zoom = new OpeningZoom();
		zoom.start(0);
		double half = zoom.factor(OpeningZoom.DURATION_NANOS / 2);
		// Ease-out: more than halfway there at half time.
		assertTrue(half > OpeningZoom.FROM + (1 - OpeningZoom.FROM) / 2);
	}

	@Test
	void cancellingEndsAtFullZoom() {
		OpeningZoom zoom = new OpeningZoom();
		zoom.start(0);
		assertTrue(zoom.running(10));
		zoom.cancel();
		assertFalse(zoom.running(20));
		assertEquals(1, zoom.factor(20));
	}
}
