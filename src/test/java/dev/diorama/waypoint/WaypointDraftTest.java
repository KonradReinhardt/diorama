package dev.diorama.waypoint;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WaypointDraftTest {
	@Test
	void appliesOnlyWhenEveryCoordinateReads() {
		Waypoint waypoint = new Waypoint("Base", 1, 64, -3, "minecraft:overworld", 0xFFFF0000);
		WaypointDraft draft = new WaypointDraft(waypoint);
		draft.x = "12a";
		assertFalse(draft.valid());
		assertFalse(draft.applyTo(waypoint));
		assertEquals(1, waypoint.x);
		draft.x = " 12 ";
		draft.name = "  Casa ";
		assertTrue(draft.applyTo(waypoint));
		assertEquals(12, waypoint.x);
		assertEquals("Casa", waypoint.name);
		assertFalse(draft.applyTo(waypoint));
	}

	@Test
	void anEmptyNameKeepsTheOldOne() {
		Waypoint waypoint = new Waypoint("Mina", 0, 10, 0, "minecraft:overworld", 0xFFFF0000);
		WaypointDraft draft = new WaypointDraft(waypoint);
		draft.name = "   ";
		draft.applyTo(waypoint);
		assertEquals("Mina", waypoint.name);
	}
}
