package dev.diorama.data;

import dev.diorama.Projection;
import dev.diorama.bake.TileKey;
import dev.diorama.bake.TilePixels;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.zip.*;

/** OMT2: a bounded, checksummed pair. Projection identity is the store folder; key is in the header. */
final class TileBundle {
    private static final int MAGIC = 0x4f4d5432;
    private static final int SCHEMA = 1;
    private TileBundle() {}

    static TilePixels read(Path file, TileKey key) throws IOException {
        try (var data = new DataInputStream(Files.newInputStream(file))) {
            if (data.readInt() != MAGIC || data.readInt() != SCHEMA || data.readInt() != Projection.TILE_PIXELS
                || data.readInt() != key.layer() || data.readInt() != key.tileU()
                || data.readInt() != key.tileV() || data.readInt() != key.lod()
                || !data.readUTF().equals(projection(file))) throw new IOException("Invalid tile identity/schema/projection");
            int flags = data.readInt();
            if (flags != 0 && flags != 1) throw new IOException("Invalid lighting flags");
            long checksum = data.readLong();
            int length = Projection.TILE_PIXELS * Projection.TILE_PIXELS;
            var crc = new CRC32();
            try (var payload = new DataInputStream(new CheckedInputStream(new InflaterInputStream(data), crc))) {
                int[] day = plane(payload, length);
                int[] night = flags == 0 ? null : plane(payload, length);
                if (payload.read() != -1 || crc.getValue() != checksum) throw new IOException("Damaged tile payload");
                if (night != null) for (int i = 0; i < length; i++) {
                    if ((night[i] >>> 24) > (day[i] >>> 24)) throw new IOException("Lighting exceeds terrain coverage");
                }
                return new TilePixels(day, night);
            }
        }
    }

    private static int[] plane(DataInputStream data, int length) throws IOException {
        byte[] bytes = data.readNBytes(length * Integer.BYTES);
        if (bytes.length != length * Integer.BYTES) throw new EOFException("Incomplete tile plane");
        int[] out = new int[length];
        ByteBuffer.wrap(bytes).asIntBuffer().get(out);
        return out;
    }

    static long write(Path file, TileKey key, TilePixels pixels) throws IOException {
        int length = Projection.TILE_PIXELS * Projection.TILE_PIXELS;
        if (pixels.day().length != length) throw new IOException("Invalid tile size");
        byte[] bytes = new byte[(int) pixels.bytes()];
        var payload = ByteBuffer.wrap(bytes).asIntBuffer();
        payload.put(pixels.day());
        if (pixels.night() != null) payload.put(pixels.night());
        var crc = new CRC32();
        crc.update(bytes);
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try (var data = new DataOutputStream(Files.newOutputStream(temp))) {
            data.writeInt(MAGIC); data.writeInt(SCHEMA); data.writeInt(Projection.TILE_PIXELS);
            data.writeInt(key.layer()); data.writeInt(key.tileU()); data.writeInt(key.tileV()); data.writeInt(key.lod());
            data.writeUTF(projection(file));
            data.writeInt(pixels.night() == null ? 0 : 1); data.writeLong(crc.getValue());
            try (var compressed = new DeflaterOutputStream(data)) { compressed.write(bytes); }
        }
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return Files.size(file);
    }

    private static String projection(Path file) {
        Path parent = file.getParent();
        if (parent.getFileName().toString().startsWith("lod")) parent = parent.getParent();
        return parent.getFileName().toString();
    }
}
