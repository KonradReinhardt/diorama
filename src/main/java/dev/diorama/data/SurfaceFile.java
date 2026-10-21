package dev.diorama.data;

import dev.diorama.Projection;
import dev.diorama.bake.TileKey;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.zip.*;
import org.jspecify.annotations.Nullable;

/**
 * A tile's {@link dev.diorama.bake.Surface} plane, beside its OMT2 bundle as {@code <u>_<v>.omts}.
 * Apart from the bundle, so builds that don't know it still read the bundle as it was. It names the
 * tile and carries the CRC of the day plane it was baked with: a bundle written since by a build
 * that doesn't know surfaces no longer matches it, and then it is ignored rather than trusted.
 * Written atomically after the bundle.
 */
final class SurfaceFile {
	private static final int MAGIC = 0x4F4D5331;

	private SurfaceFile() {
	}

	/** The CRC of a day plane, as the surface file records it. */
	static long dayChecksum(int[] day) {
		byte[] bytes = new byte[day.length * Integer.BYTES];
		ByteBuffer.wrap(bytes).asIntBuffer().put(day);
		CRC32 crc = new CRC32();
		crc.update(bytes);
		return crc.getValue();
	}

	/** The plane saved for these day pixels, or null if there is none or it belongs to other pixels. */
	static byte @Nullable [] read(Path file, TileKey key, int[] day) {
		if (!Files.isRegularFile(file)) {
			return null;
		}
		try (var data = new DataInputStream(Files.newInputStream(file))) {
			if (data.readInt() != MAGIC || data.readInt() != Projection.TILE_PIXELS || data.readInt() != key.tileU()
				|| data.readInt() != key.tileV() || data.readLong() != dayChecksum(day)) {
				return null;
			}
			int length = Projection.TILE_PIXELS * Projection.TILE_PIXELS;
			byte[] plane = new InflaterInputStream(data).readNBytes(length);
			return plane.length == length ? plane : null;
		} catch (IOException | RuntimeException failure) {
			return null;
		}
	}

	/**
	 * Writes the plane for these day pixels. Without one, an older file stays: it is only read back
	 * for exactly the day pixels it was written with, for which it still holds.
	 */
	static void write(Path file, TileKey key, int[] day, byte @Nullable [] plane) throws IOException {
		if (plane == null) {
			return;
		}
		Path temp = file.resolveSibling(file.getFileName() + ".tmp");
		try (var data = new DataOutputStream(Files.newOutputStream(temp))) {
			data.writeInt(MAGIC);
			data.writeInt(Projection.TILE_PIXELS);
			data.writeInt(key.tileU());
			data.writeInt(key.tileV());
			data.writeLong(dayChecksum(day));
			try (var compressed = new DeflaterOutputStream(data)) {
				compressed.write(plane);
			}
		}
		Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}
}
