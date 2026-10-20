package dev.diorama.data;

import dev.diorama.Diorama;
import dev.diorama.bake.TileKey;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.CRC32;

/**
 * Which saved surface tiles were drawn entirely with the current map look. A tile is fresh when
 * every pixel it ever had was traced again from real terrain under that look; pixels kept from an
 * older save mean it isn't. Changing the look empties the set, so every tile is redrawn once from
 * the chunks the game saved, as it's shown. Thread-safe. A missing or damaged file only means
 * tiles get redrawn again, never that a stale tile is taken for fresh.
 */
public final class AppearanceStamps {
	private static final int MAGIC = 0x44415031;
	private final Set<Long> fresh = ConcurrentHashMap.newKeySet();
	private volatile int fingerprint;
	private volatile boolean dirty;

	private AppearanceStamps(int fingerprint) {
		this.fingerprint = fingerprint;
	}

	public static AppearanceStamps load(Path file, int fingerprint) {
		AppearanceStamps stamps = new AppearanceStamps(fingerprint);
		if (!Files.isRegularFile(file)) {
			return stamps;
		}
		try {
			ByteBuffer data = ByteBuffer.wrap(Files.readAllBytes(file));
			if (data.remaining() < 16 || data.getInt() != MAGIC || data.getInt() != fingerprint) {
				return stamps;
			}
			int count = data.getInt(), checksum = data.getInt();
			if (count < 0 || data.remaining() != count * Long.BYTES) {
				return stamps;
			}
			CRC32 crc = new CRC32();
			crc.update(data.duplicate());
			if ((int) crc.getValue() != checksum) {
				return stamps;
			}
			for (int i = 0; i < count; i++) {
				stamps.fresh.add(data.getLong());
			}
		} catch (IOException | RuntimeException failure) {
			Diorama.LOGGER.warn("Couldn't read map appearance stamps {}; tiles will be redrawn", file, failure);
		}
		return stamps;
	}

	public int fingerprint() {
		return this.fingerprint;
	}

	public boolean isFresh(TileKey key) {
		return key.lod() == 0 && this.fresh.contains(id(key));
	}

	/** Records a tile drawn entirely under {@code fingerprint}; ignored if the look changed since. */
	public void markFresh(TileKey key, int fingerprint) {
		if (key.lod() == 0 && fingerprint == this.fingerprint && this.fresh.add(id(key))) {
			this.dirty = true;
		}
	}

	/** A new look: nothing saved was drawn with it yet. */
	public void reset(int fingerprint) {
		if (fingerprint != this.fingerprint) {
			this.fingerprint = fingerprint;
			this.fresh.clear();
			this.dirty = true;
		}
	}

	/** Writes the stamps atomically if they changed. Call only once the tiles they vouch for are on disk. */
	public synchronized void save(Path file) {
		if (!this.dirty) {
			return;
		}
		this.dirty = false;
		Long[] keys = this.fresh.toArray(Long[]::new);
		ByteBuffer body = ByteBuffer.allocate(keys.length * Long.BYTES);
		for (Long key : keys) {
			body.putLong(key);
		}
		body.flip();
		CRC32 crc = new CRC32();
		crc.update(body.duplicate());
		ByteBuffer out = ByteBuffer.allocate(16 + body.remaining());
		out.putInt(MAGIC).putInt(this.fingerprint).putInt(keys.length).putInt((int) crc.getValue()).put(body);
		try {
			Files.createDirectories(file.getParent());
			Path temp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.write(temp, out.array());
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException | RuntimeException failure) {
			this.dirty = true;
			Diorama.LOGGER.warn("Couldn't save map appearance stamps {}", file, failure);
		}
	}

	private static long id(TileKey key) {
		return (long) key.tileU() << 32 | key.tileV() & 0xFFFFFFFFL;
	}
}
