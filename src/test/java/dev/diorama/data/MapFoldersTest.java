package dev.diorama.data;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MapFoldersTest {
	@TempDir Path root;

	@Test void movesAnOldMapIntoTheSave() throws IOException {
		Path old = this.root.resolve("diorama").resolve("singleplayer-world-1234");
		Files.createDirectories(old.resolve("dim-overworld").resolve("surface-grid3-45"));
		Files.writeString(old.resolve("waypoints.json"), "[]");
		Files.writeString(old.resolve("dim-overworld").resolve("surface-grid3-45").resolve("0_0.omt2"), "tile");
		Path target = this.root.resolve("saves").resolve("World").resolve("diorama");
		Files.createDirectories(target.getParent());

		assertEquals(target, MapFolders.adopt(old, target));
		assertFalse(Files.exists(old));
		assertEquals("[]", Files.readString(target.resolve("waypoints.json")));
		assertEquals("tile", Files.readString(target.resolve("dim-overworld").resolve("surface-grid3-45").resolve("0_0.omt2")));
	}

	@Test void anEmptyTargetIsReplacedAndNothingToMoveUsesTheTarget() throws IOException {
		Path old = this.root.resolve("old");
		Path target = this.root.resolve("save").resolve("diorama");
		assertEquals(target, MapFolders.adopt(old, target));
		Files.createDirectories(old);
		Files.writeString(old.resolve("seen.bin"), "x");
		Files.createDirectories(target);
		assertEquals(target, MapFolders.adopt(old, target));
		assertEquals("x", Files.readString(target.resolve("seen.bin")));
	}

	@Test void neverMergesOrDeletesWhenBothHoldAMap() throws IOException {
		Path old = this.root.resolve("old");
		Path target = this.root.resolve("save").resolve("diorama");
		Files.createDirectories(old);
		Files.createDirectories(target);
		Files.writeString(old.resolve("waypoints.json"), "old");
		Files.writeString(target.resolve("waypoints.json"), "new");

		assertEquals(target, MapFolders.adopt(old, target));
		assertEquals("old", Files.readString(old.resolve("waypoints.json")));
		assertEquals("new", Files.readString(target.resolve("waypoints.json")));
	}
}
