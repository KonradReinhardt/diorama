package dev.diorama.data;

import dev.diorama.bake.SavedTerrain;
import org.jspecify.annotations.Nullable;
//? if >=26.3 {
import dev.diorama.Diorama;
import dev.diorama.mixin.BiomeManagerAccessor;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
//?}

/**
 * The integrated server's saved chunks, read straight from its region files without loading or
 * generating anything. Only fully generated chunks saved by this game version are used. Each
 * chunk read is parsed on the calling bake thread and kept in a small cache, since a tile's rays
 * and biome blending touch the same chunks many times.
 */
public final class SavedChunks {
	private SavedChunks() {
	}

	/** The saved terrain of the client's level, or null outside singleplayer or on versions without support. */
	public static @Nullable SavedTerrain open(Object clientLevel) {
		//? if >=26.3 {
		ClientLevel level = (ClientLevel) clientLevel;
		var server = Minecraft.getInstance().getSingleplayerServer();
		return server == null ? null : new Reader(server, level);
		//?} else {
		/*return null;
		*///?}
	}

	//? if >=26.3 {
	private static final class Reader implements SavedTerrain {
		/** Parsed chunks kept: enough for the rays of a few rows of tiles and their biome blending. */
		private static final int CACHED = 288;
		private static final long READ_TIMEOUT_SECONDS = 10;

		private final net.minecraft.server.MinecraftServer server;
		private final net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension;
		private final long biomeZoomSeed;
		private final int blendRadius;
		private final Holder<Biome> fallbackBiome;
		private final boolean hasSkyLight;
		/** The server's level and what parsing needs from it, looked up on the server thread at first use. */
		private @Nullable ServerLevel world;
		private @Nullable PalettedContainerFactory containers;
		private boolean resolved;
		private final Map<Long, Parsed> parsed = new LinkedHashMap<>(64, .75F, true);
		private final Map<Long, ChunkSnapshot> snapshots = new LinkedHashMap<>(64, .75F, true);
		private volatile boolean closed;

		/** One saved chunk's sections, light and biomes; {@link #MISSING} when nothing usable is saved. */
		private record Parsed(@Nullable LevelChunkSection[] sections, @Nullable DataLayer[] blockLight, @Nullable DataLayer[] skyLight,
			@Nullable PalettedContainerRO<Holder<Biome>>[] biomes) {
		}

		private static final Parsed MISSING = new Parsed(new LevelChunkSection[0], new DataLayer[0], new DataLayer[0], null);

		/** Client thread: captures what the client knows; nothing on the server is touched yet. */
		Reader(net.minecraft.server.MinecraftServer server, ClientLevel level) {
			this.server = server;
			this.dimension = level.dimension();
			this.biomeZoomSeed = ((BiomeManagerAccessor) level.getBiomeManager()).diorama$biomeZoomSeed();
			this.blendRadius = Minecraft.getInstance().options.biomeBlendRadius().get();
			this.fallbackBiome = level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
			this.hasSkyLight = level.dimensionType().hasSkyLight();
		}

		/** The server's level for this dimension, asked for once on the server thread. Bake threads only. */
		private synchronized @Nullable ServerLevel world() {
			if (!this.resolved && !this.closed) {
				this.resolved = true;
				try {
					this.world = this.server.submit(() -> this.server.getLevel(this.dimension)).get(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS);
					if (this.world != null) this.containers = PalettedContainerFactory.create(this.world.registryAccess());
				} catch (Exception failure) {
					Diorama.LOGGER.debug("Saved chunks unavailable for {}", this.dimension, failure);
				}
			}
			return this.world;
		}

		@Override
		public @Nullable ChunkSnapshot load(int chunkX, int chunkZ) {
			long key = ChunkSnapshot.key(chunkX, chunkZ);
			synchronized (this.snapshots) {
				ChunkSnapshot cached = this.snapshots.get(key);
				if (cached != null) {
					return cached;
				}
			}
			ServerLevel world = this.world();
			if (world == null) {
				return null;
			}
			Parsed self = this.parsed(chunkX, chunkZ);
			if (self == MISSING) {
				return null;
			}
			@SuppressWarnings("unchecked")
			PalettedContainerRO<Holder<Biome>>[][] biomes = new PalettedContainerRO[9][];
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					Parsed around = dx == 0 && dz == 0 ? self : this.parsed(chunkX + dx, chunkZ + dz);
					biomes[(dx + 1) * 3 + dz + 1] = around.biomes();
				}
			}
			ChunkSnapshot snapshot = ChunkSnapshot.saved(chunkX, chunkZ, world.getMinSectionY(), world.getMinY(), self.sections(),
				self.blockLight(), self.skyLight(), this.hasSkyLight, biomes, this.biomeZoomSeed, this.blendRadius, this.fallbackBiome);
			synchronized (this.snapshots) {
				this.snapshots.put(key, snapshot);
				trim(this.snapshots);
			}
			return snapshot;
		}

		private Parsed parsed(int chunkX, int chunkZ) {
			long key = ChunkSnapshot.key(chunkX, chunkZ);
			synchronized (this.parsed) {
				Parsed cached = this.parsed.get(key);
				if (cached != null) {
					return cached;
				}
			}
			Parsed result = this.read(this.world(), chunkX, chunkZ);
			synchronized (this.parsed) {
				this.parsed.put(key, result);
				trim(this.parsed);
			}
			return result;
		}

		@SuppressWarnings("unchecked")
		private Parsed read(@Nullable ServerLevel world, int chunkX, int chunkZ) {
			if (this.closed || world == null || this.containers == null) {
				return MISSING;
			}
			try {
				CompoundTag tag = world.getChunkSource().chunkMap.read(new ChunkPos(chunkX, chunkZ))
					.get(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS).orElse(null);
				// Older saves would need the data fixer, and partly generated chunks were never seen.
				if (tag == null || NbtUtils.getDataVersion(tag, -1) != SharedConstants.WORLD_VERSION
					|| SerializableChunkData.getChunkStatusFromTag(tag) != ChunkStatus.FULL) {
					return MISSING;
				}
				SerializableChunkData data = SerializableChunkData.parse(world, this.containers, tag);
				// Without finished light, redrawn pixels would be lit wrongly next to live ones.
				if (!data.lightCorrect()) {
					return MISSING;
				}
				int count = world.getSectionsCount();
				LevelChunkSection[] sections = new LevelChunkSection[count];
				DataLayer[] block = new DataLayer[count];
				DataLayer[] sky = new DataLayer[count];
				PalettedContainerRO<Holder<Biome>>[] biomes = new PalettedContainerRO[count];
				for (SerializableChunkData.SectionData section : data.sectionData()) {
					int index = section.y() - world.getMinSectionY();
					if (index < 0 || index >= count) {
						continue;
					}
					sections[index] = section.chunkSection();
					block[index] = section.blockLight();
					sky[index] = section.skyLight();
					biomes[index] = section.chunkSection() == null ? null : section.chunkSection().getBiomes();
				}
				return new Parsed(sections, block, sky, biomes);
			} catch (Exception failure) {
				if (!this.closed) {
					Diorama.LOGGER.debug("Couldn't read saved chunk {}, {}", chunkX, chunkZ, failure);
				}
				return MISSING;
			}
		}

		private static <V> void trim(Map<Long, V> cache) {
			var it = cache.values().iterator();
			while (cache.size() > CACHED && it.hasNext()) {
				it.next();
				it.remove();
			}
		}

		@Override
		public void close() {
			this.closed = true;
			synchronized (this.parsed) {
				this.parsed.clear();
			}
			synchronized (this.snapshots) {
				this.snapshots.clear();
			}
		}
	}
	//?}
}
