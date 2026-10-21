package dev.diorama.data;

import dev.diorama.Diorama;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.stream.Stream;

/**
 * Moves a world's map and waypoints from where earlier versions kept them into their new folder.
 * Nothing is ever deleted: a world with something already in the new folder keeps both, and when a
 * move can't be done in one rename (another drive), the old folder is copied and then renamed
 * aside, so a failure part way leaves the old folder whole and in use.
 */
public final class MapFolders {
	/** Suffix of an old folder left behind after its copy reached the new place. */
	public static final String MOVED = ".moved-to-save";

	private MapFolders() {
	}

	/**
	 * The folder to use for a world whose data belongs in {@code target} and may still be in
	 * {@code old}: {@code target} once the old folder moved there, or was empty, or there was
	 * nothing to move; {@code old} when it couldn't move.
	 */
	public static Path adopt(Path old, Path target) {
		if (!Files.isDirectory(old) || old.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize())) {
			return target;
		}
		try {
			if (Files.isDirectory(target) && !isEmpty(target)) {
				Diorama.LOGGER.warn("The map folder {} already exists; the older {} is left as it is", target, old);
				return target;
			}
			Files.createDirectories(target.getParent());
			Files.deleteIfExists(target);
			try {
				Files.move(old, target, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException rename) {
				// Another drive: copy under a temporary name, swap it in, then set the old one aside.
				Path partial = target.resolveSibling(target.getFileName() + ".partial");
				deleteTree(partial);
				copyTree(old, partial);
				Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
				Files.move(old, old.resolveSibling(old.getFileName() + MOVED));
			}
			Diorama.LOGGER.info("Moved the map and waypoints from {} to {}", old, target);
			return target;
		} catch (IOException | RuntimeException failure) {
			Diorama.LOGGER.error("Couldn't move the map from {} to {}; it stays there", old, target, failure);
			return Files.isDirectory(target) && !isEmptyQuietly(target) ? target : old;
		}
	}

	private static boolean isEmpty(Path folder) throws IOException {
		try (Stream<Path> entries = Files.list(folder)) {
			return entries.findAny().isEmpty();
		}
	}

	private static boolean isEmptyQuietly(Path folder) {
		try {
			return isEmpty(folder);
		} catch (IOException e) {
			return true;
		}
	}

	private static void copyTree(Path from, Path to) throws IOException {
		Files.walkFileTree(from, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
				Files.createDirectories(to.resolve(from.relativize(dir).toString()));
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				Files.copy(file, to.resolve(from.relativize(file).toString()), StandardCopyOption.COPY_ATTRIBUTES);
				return FileVisitResult.CONTINUE;
			}
		});
	}

	/** Only for a leftover {@code .partial} copy of our own. */
	private static void deleteTree(Path folder) throws IOException {
		if (!Files.exists(folder)) {
			return;
		}
		Files.walkFileTree(folder, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				Files.delete(file);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
				Files.delete(dir);
				return FileVisitResult.CONTINUE;
			}
		});
	}
}
