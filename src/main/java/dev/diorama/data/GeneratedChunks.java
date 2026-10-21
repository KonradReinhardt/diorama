package dev.diorama.data;

import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;
//? if >=26.3 {
import dev.diorama.Diorama;
import dev.diorama.mixin.BiomeManagerAccessor;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.resources.ResourceKey;
//?}

/**
 * Chunks the integrated server loads for the map, generating any it never made, so a player in
 * creative or with cheats can draw land they haven't explored (see {@code RegionReveal}). Only in
 * singleplayer: a server elsewhere never sends chunks far from the player. The server checks the
 * player's permission again before handing out each chunk, and stops on its first refusal.
 */
public final class GeneratedChunks {
	//? if >=26.3 {
	private final MinecraftServer server;
	private final ResourceKey<Level> dimension;
	private final UUID player;
	private final long biomeZoomSeed;
	private final int blendRadius;
	private final Holder<Biome> fallbackBiome;
	private final boolean hasSkyLight;
	//?}
	private volatile boolean denied;
	private volatile boolean closed;

	//? if >=26.3 {
	/** Client thread: captures what snapshots need from the client; nothing on the server is touched yet. */
	private GeneratedChunks(MinecraftServer server, ClientLevel level, LocalPlayer player) {
		this.server = server;
		this.dimension = level.dimension();
		this.player = player.getUUID();
		this.biomeZoomSeed = ((BiomeManagerAccessor) level.getBiomeManager()).diorama$biomeZoomSeed();
		this.blendRadius = Minecraft.getInstance().options.biomeBlendRadius().get();
		this.fallbackBiome = level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
		this.hasSkyLight = level.dimensionType().hasSkyLight();
	}
	//?}

	/**
	 * Whether the client's player may draw unexplored land now: in singleplayer, in creative or
	 * allowed game master commands (cheats). Client thread.
	 */
	public static boolean allowed() {
		//? if >=26.3 {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		return mc.getSingleplayerServer() != null && player != null
			&& (player.isCreative() || player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER));
		//?} else {
		/*return false;
		*///?}
	}

	/** The client level's chunks on the integrated server, or null where {@link #allowed} isn't. Client thread. */
	public static @Nullable GeneratedChunks open(Object clientLevel) {
		//? if >=26.3 {
		Minecraft mc = Minecraft.getInstance();
		MinecraftServer server = mc.getSingleplayerServer();
		return server == null || mc.player == null || !allowed() ? null : new GeneratedChunks(server, (ClientLevel) clientLevel, mc.player);
		//?} else {
		/*return null;
		*///?}
	}

	/**
	 * A copy of the chunk as the server has it, generated if it never was; null if the chunk
	 * couldn't be had or the player may no longer have it. Call from a thread other than the
	 * server's, where asking for a chunk would wait for it. Completes on the server thread.
	 */
	public CompletableFuture<@Nullable ChunkSnapshot> request(int chunkX, int chunkZ) {
		//? if >=26.3 {
		if (this.closed || this.denied) {
			return CompletableFuture.completedFuture(null);
		}
		ChunkPos pos = new ChunkPos(chunkX, chunkZ);
		return CompletableFuture.supplyAsync(() -> {
				ServerLevel world = this.permitted() ? this.server.getLevel(this.dimension) : null;
				if (world != null) {
					world.getChunkSource().addTicketWithRadius(TICKET, pos, 0);
				}
				return world;
			}, this.server)
			// Asked for from the pool, not the server thread: there getChunkFuture would block its tick.
			.thenComposeAsync(world -> world == null ? CompletableFuture.<@Nullable ChunkSnapshot>completedFuture(null)
				: this.load(world, chunkX, chunkZ).whenCompleteAsync((snapshot, failure) -> world.getChunkSource().removeTicketWithRadius(TICKET, pos, 0), this.server))
			.exceptionally(failure -> {
				if (!this.closed) {
					Diorama.LOGGER.warn("Couldn't load chunk {}, {} for the map", chunkX, chunkZ, failure);
				}
				return null;
			});
		//?} else {
		/*return CompletableFuture.completedFuture(null);
		*///?}
	}

	//? if >=26.3 {
	/**
	 * Holds a chunk loaded from the request until it's copied: the ticket getChunkFuture adds itself
	 * lasts a tick, and generating takes longer. It only loads (nothing in the chunk ticks), isn't
	 * saved with the world, and, compared by identity, never matches a ticket of the game's.
	 */
	private static final TicketType TICKET = new TicketType(TicketType.NO_TIMEOUT, TicketType.FLAG_LOADING);

	/** Pool thread: the chunk itself in full, and its neighbours' biomes for blending tints along its edges. */
	private CompletableFuture<@Nullable ChunkSnapshot> load(ServerLevel world, int chunkX, int chunkZ) {
		@SuppressWarnings("unchecked")
		CompletableFuture<ChunkResult<ChunkAccess>>[] around = new CompletableFuture[9];
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				ChunkStatus status = dx == 0 && dz == 0 ? ChunkStatus.FULL : ChunkStatus.BIOMES;
				around[(dx + 1) * 3 + dz + 1] = world.getChunkSource().getChunkFuture(chunkX + dx, chunkZ + dz, status, true);
			}
		}
		return CompletableFuture.allOf(around).thenApplyAsync(ignored -> this.snapshot(world, chunkX, chunkZ, around), this.server);
	}

	/** Server thread: whether the player is still in creative or allowed cheats. A refusal is final. */
	private boolean permitted() {
		if (this.closed || this.denied) {
			return false;
		}
		ServerPlayer player = this.server.getPlayerList().getPlayer(this.player);
		boolean ok = player != null && (player.isCreative() || player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER));
		if (!ok) {
			this.denied = true;
		}
		return ok;
	}

	/** Server thread, right after the chunk loaded: copies its blocks and light, and holds its biomes. */
	@SuppressWarnings("unchecked")
	private @Nullable ChunkSnapshot snapshot(ServerLevel world, int chunkX, int chunkZ, CompletableFuture<ChunkResult<ChunkAccess>>[] around) {
		if (!this.permitted()) {
			return null;
		}
		ChunkResult<ChunkAccess> self = around[4].join();
		if (!(self.orElse(null) instanceof LevelChunk chunk)) {
			Diorama.LOGGER.warn("Chunk {}, {} came back as {} ({})", chunkX, chunkZ, self.orElse(null), self.isSuccess() ? "loaded" : self.getError());
			return null;
		}
		LevelChunkSection[] live = chunk.getSections();
		LevelChunkSection[] sections = new LevelChunkSection[live.length];
		DataLayer[] block = new DataLayer[live.length];
		DataLayer[] sky = new DataLayer[live.length];
		LevelLightEngine light = world.getLightEngine();
		for (int i = 0; i < live.length; i++) {
			sections[i] = live[i] == null ? null : live[i].copy();
			SectionPos pos = SectionPos.of(chunkX, chunk.getMinSectionY() + i, chunkZ);
			DataLayer b = light.getLayerListener(LightLayer.BLOCK).getDataLayerData(pos);
			DataLayer s = light.getLayerListener(LightLayer.SKY).getDataLayerData(pos);
			block[i] = b == null ? null : b.copy();
			sky[i] = s == null ? null : s.copy();
		}
		// The game never writes into a biome container, only swaps in new ones: they're held as they are.
		PalettedContainerRO<Holder<Biome>>[][] biomes = new PalettedContainerRO[9][];
		for (int i = 0; i < 9; i++) {
			ChunkAccess neighbour = i == 4 ? chunk : around[i].join().orElse(null);
			if (neighbour == null) {
				continue;
			}
			LevelChunkSection[] near = neighbour.getSections();
			biomes[i] = new PalettedContainerRO[near.length];
			for (int s = 0; s < near.length; s++) {
				biomes[i][s] = near[s] == null ? null : near[s].getBiomes();
			}
		}
		return ChunkSnapshot.saved(chunkX, chunkZ, chunk.getMinSectionY(), chunk.getMinY(), sections, block, sky, this.hasSkyLight,
			biomes, this.biomeZoomSeed, this.blendRadius, this.fallbackBiome);
	}
	//?}

	/** Whether the server refused a chunk because the player lost creative and cheats. */
	public boolean denied() {
		return this.denied;
	}

	/** Stops handing out chunks; requests still on their way come back empty. */
	public void close() {
		this.closed = true;
	}
}
