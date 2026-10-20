package dev.diorama.waypoint;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BannerItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import org.jspecify.annotations.Nullable;

/**
 * The game's banner patterns, vanilla's and those of datapacks and mods, read from the world's
 * registry. Everything the mod touches of the banner API is here, so version differences stay in
 * one place. Client thread only.
 */
public final class BannerPatternCatalog {
	/** The pattern under every banner, the whole cloth; the loom doesn't offer it. */
	public static final String BASE = "minecraft:base";

	/** A pattern the loom can apply: its registry id, its texture and its name's translation key. */
	public record Pattern(String id, Identifier texture, String translationKey) {
		/** The pattern's name in a dye, as the loom shows it: "Black Creeper Charge". */
		public Component name(BannerDesign.Dye dye) {
			return Component.translatable(this.translationKey + "." + dye.id());
		}
	}

	private BannerPatternCatalog() {
	}

	/** Every pattern in the registry except the base, in registry order; empty outside a world. */
	public static List<Pattern> all() {
		List<Pattern> out = new ArrayList<>();
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return out;
		}
		mc.level.registryAccess().lookupOrThrow(Registries.BANNER_PATTERN).listElements().forEach(reference -> {
			String id = reference.key().identifier().toString();
			if (!id.equals(BASE)) {
				out.add(new Pattern(id, texture(reference.value().assetId()), reference.value().translationKey()));
			}
		});
		return out;
	}

	/**
	 * The texture for a pattern id: from the registry when the world has it, otherwise vanilla's
	 * naming, which also covers the base and works on the title screen.
	 */
	public static Identifier texture(String id) {
		Minecraft mc = Minecraft.getInstance();
		Identifier key = Identifier.tryParse(id);
		if (key == null) {
			key = Identifier.withDefaultNamespace("base");
		}
		if (mc.level != null) {
			var found = mc.level.registryAccess().lookupOrThrow(Registries.BANNER_PATTERN)
				.get(net.minecraft.resources.ResourceKey.create(Registries.BANNER_PATTERN, key));
			if (found.isPresent()) {
				return texture(found.get().value().assetId());
			}
		}
		return texture(key);
	}

	/** Whether the world's registry knows a pattern; outside a world, every id counts as known. */
	public static boolean known(String id) {
		Minecraft mc = Minecraft.getInstance();
		Identifier key = Identifier.tryParse(id);
		if (key == null) {
			return false;
		}
		return mc.level == null || mc.level.registryAccess().lookupOrThrow(Registries.BANNER_PATTERN)
			.get(net.minecraft.resources.ResourceKey.create(Registries.BANNER_PATTERN, key)).isPresent();
	}

	private static Identifier texture(Identifier assetId) {
		return assetId.withPath(path -> "textures/entity/banner/" + path + ".png");
	}

	/**
	 * The design of the banner the player holds, or of the first banner in their inventory; null
	 * if they have none. Layers whose pattern has no registry id (inline ones) are skipped.
	 */
	public static @Nullable BannerDesign fromInventory() {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) {
			return null;
		}
		BannerDesign held = fromItem(player.getMainHandItem());
		if (held == null) {
			held = fromItem(player.getOffhandItem());
		}
		var inventory = player.getInventory();
		for (int i = 0; held == null && i < inventory.getContainerSize(); i++) {
			held = fromItem(inventory.getItem(i));
		}
		return held;
	}

	static @Nullable BannerDesign fromItem(ItemStack stack) {
		if (!(stack.getItem() instanceof BannerItem banner)) {
			return null;
		}
		List<BannerDesign.Layer> layers = new ArrayList<>();
		BannerPatternLayers patterns = stack.get(DataComponents.BANNER_PATTERNS);
		if (patterns != null) {
			for (BannerPatternLayers.Layer layer : patterns.layers()) {
				layer.pattern().unwrapKey().ifPresent(key ->
					layers.add(new BannerDesign.Layer(key.identifier().toString(), BannerDesign.Dye.values()[layer.color().getId()])));
			}
		}
		return new BannerDesign(BannerDesign.Dye.values()[banner.getColor().getId()], layers);
	}
}
