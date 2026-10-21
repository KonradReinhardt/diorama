package dev.diorama.preview;

import dev.diorama.Diorama;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jspecify.annotations.Nullable;
//? if >=26.3 {
import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
//?}

/** Captures immutable generator inputs on the integrated server, creates a private worker sampler. */
public final class SeedBiomeSource {
	private SeedBiomeSource() {}
	public static @Nullable SeedPreview open(ClientLevel level, @Nullable Path folder) {
		//? if >=26.3 {
		var server = Minecraft.getInstance().getSingleplayerServer();
		if (server == null || level.dimension() != Level.OVERWORLD) return null;
		var captured = server.submit(() -> {
			var world = server.getLevel(level.dimension());
			if (world == null || !(world.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator generator)
				|| !(generator.getBiomeSource() instanceof MultiNoiseBiomeSource)) return null;
			var registries = world.registryAccess();
			var ops = registries.createSerializationContext(JsonOps.INSTANCE);
			JsonElement biomes = BiomeSource.CODEC.encodeStart(ops, generator.getBiomeSource()).getOrThrow();
			var settings = generator.generatorSettings().value();
			JsonElement encodedSettings = NoiseGeneratorSettings.DIRECT_CODEC.encodeStart(ops, settings).getOrThrow();
			long seed = world.getSeed();
			String identity = "diorama-biomes-v2/26.3/" + seed + "/" + biomes + "/" + encodedSettings;
			// No chunk lookup or generation. The actual server RandomState is never shared.
			return (java.util.function.Supplier<SeedPreview.Source>) () -> {
				var cloned = BiomeSource.CODEC.parse(ops, biomes).getOrThrow();
				if (biomes.isJsonObject() && biomes.getAsJsonObject().has("preset")) {
					var key = net.minecraft.resources.ResourceKey.create(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST,
						net.minecraft.resources.Identifier.parse(biomes.getAsJsonObject().get("preset").getAsString()));
					var parameters = registries.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST).getOrThrow(key).value().parameters();
					// A separate search tree avoids using the live generator's biome lookup caches.
					cloned = MultiNoiseBiomeSource.createFromList(new net.minecraft.world.level.biome.Climate.ParameterList<>(parameters.values()));
				}
				var state = RandomState.create(registries.lookupOrThrow(Registries.NOISE), seed, settings);
				var resolver = cloned.createUncachedResolver(state);
				try {
					MessageDigest digest = MessageDigest.getInstance("SHA-256");
					digest.update(identity.getBytes(StandardCharsets.UTF_8));
					// Referenced noise/density keys can retain their names across datapack edits.
					fingerprintRegistry(digest, registries.lookupOrThrow(Registries.NOISE), net.minecraft.world.level.levelgen.synth.NormalNoise.DIRECT_CODEC, ops);
					fingerprintRegistry(digest, registries.lookupOrThrow(Registries.DENSITY_FUNCTION), net.minecraft.world.level.levelgen.densityfunction.DensityFunction.CODEC, ops);
					fingerprintRegistry(digest, registries.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST), net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList.DIRECT_CODEC, ops);
					String fingerprint = HexFormat.of().formatHex(digest.digest());
					return new SeedPreview.Source((x, z) -> classify(resolver.getNoiseBiome(x >> 2, 16, z >> 2)), fingerprint);
				} catch (Exception e) { throw new IllegalStateException(e); }
			};
		});
		return new SeedPreview(() -> {
			try {
				var initialize = captured.get(10, java.util.concurrent.TimeUnit.SECONDS);
				return initialize == null ? null : initialize.get();
			} catch (InterruptedException stopped) {
				Thread.currentThread().interrupt();
				return null;
			} catch (Exception error) {
				Diorama.LOGGER.warn("Seed cartography unavailable; retaining parchment", error);
				return null;
			}
		}, folder == null ? null : folder.resolve("biome-preview"));
		//?} else {
		/*return null;
		*///?}
	}

	//? if >=26.3 {
	private static <T> void fingerprintRegistry(MessageDigest digest, net.minecraft.core.Registry<T> registry,
		com.mojang.serialization.Codec<T> codec, net.minecraft.resources.RegistryOps<JsonElement> ops) {
		registry.entrySet().stream().sorted(java.util.Comparator.comparing(e -> e.getKey().identifier().toString())).forEach(entry -> {
			digest.update(entry.getKey().identifier().toString().getBytes(StandardCharsets.UTF_8));
			digest.update(codec.encodeStart(ops, entry.getValue()).getOrThrow().toString().getBytes(StandardCharsets.UTF_8));
		});
	}
	//?}

	//? if >=26.3 {
	/**
	 * Biome tags for each class, in the order the names are tried below. Vanilla's own tags and the
	 * common "c" tags that Fabric, NeoForge and most biome mods and datapacks fill, so modded
	 * biomes land in the right class whatever they're called.
	 */
	private static final java.util.List<java.util.Map.Entry<Byte, java.util.List<net.minecraft.tags.TagKey<net.minecraft.world.level.biome.Biome>>>> TAGS = java.util.List.of(
		java.util.Map.entry((byte) 1, tags("minecraft:is_ocean", "minecraft:is_deep_ocean", "minecraft:is_river", "c:is_ocean", "c:is_deep_ocean", "c:is_shallow_ocean", "c:is_river")),
		java.util.Map.entry((byte) 4, tags("minecraft:is_mountain", "minecraft:is_hill", "c:is_mountain", "c:is_mountain/peak", "c:is_mountain/slope", "c:is_hill")),
		java.util.Map.entry((byte) 3, tags("minecraft:is_forest", "minecraft:is_taiga", "minecraft:is_jungle", "c:is_forest", "c:is_taiga", "c:is_jungle", "c:is_coniferous_tree", "c:is_deciduous_tree")),
		java.util.Map.entry((byte) 5, tags("minecraft:is_badlands", "minecraft:is_savanna", "c:is_desert", "c:is_badlands", "c:is_savanna", "c:is_sandy")),
		java.util.Map.entry((byte) 6, tags("c:is_snowy", "c:is_icy")),
		java.util.Map.entry((byte) 7, tags("c:is_swamp", "c:is_wetland", "c:is_mushroom")));

	private static java.util.List<net.minecraft.tags.TagKey<net.minecraft.world.level.biome.Biome>> tags(String... ids) {
		return java.util.Arrays.stream(ids).map(id -> net.minecraft.tags.TagKey.create(Registries.BIOME, net.minecraft.resources.Identifier.parse(id))).toList();
	}

	/** The class of a biome: by its tags, else by its name. */
	static byte classify(net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> biome) {
		try {
			for (var entry : TAGS) {
				for (var tag : entry.getValue()) if (biome.is(tag)) return entry.getKey();
			}
		} catch (RuntimeException unbound) {
			// A biome without bound tags (an unusual biome source) still has its name.
		}
		return classify(biome.unwrapKey().map(k -> k.identifier().getPath()).orElse("plains"));
	}
	//?}

	/** Semantic symbols rather than generated objects. No structures, trees or terrain blocks. */
	public static byte classify(String biome) {
		if (biome.contains("ocean") || biome.contains("river")) return 1;
		if (biome.contains("peak") || biome.contains("mountain") || biome.contains("slope") || biome.contains("grove")) return 4;
		if (biome.contains("forest") || biome.contains("taiga") || biome.contains("jungle")) return 3;
		if (biome.contains("desert") || biome.contains("badlands") || biome.contains("savanna")) return 5;
		if (biome.contains("snow") || biome.contains("ice") || biome.contains("frozen")) return 6;
		if (biome.contains("swamp") || biome.contains("mushroom")) return 7;
		return 2;
	}
}
