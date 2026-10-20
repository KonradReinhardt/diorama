package dev.diorama.waypoint;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * How a waypoint's banner looks, like a banner from the loom: a base dye and layers of patterns,
 * each in its own dye. Patterns are kept by id ({@code minecraft:creeper}), never by registry
 * index, so a design survives datapacks coming and going; an id the game doesn't know is skipped
 * when drawing and kept when saving. Pure data, no game classes, so tests can use it.
 */
public record BannerDesign(Dye base, List<Layer> layers) {
	/** As many layers as the loom allows; a vanilla banner item can't hold more. */
	public static final int VANILLA_LAYERS = 6;
	/** The most the mod allows when the player lifts the loom's limit. */
	public static final int MAX_LAYERS = 16;

	/** The sixteen dyes, in vanilla order, with the colour banners are tinted with. */
	public enum Dye {
		WHITE(0xF9FFFE), ORANGE(0xF9801D), MAGENTA(0xC74EBD), LIGHT_BLUE(0x3AB3DA),
		YELLOW(0xFED83D), LIME(0x80C71F), PINK(0xF38BAA), GRAY(0x474F52),
		LIGHT_GRAY(0x9D9D97), CYAN(0x169C9C), PURPLE(0x8932B8), BLUE(0x3C44AA),
		BROWN(0x835432), GREEN(0x5E7C16), RED(0xB02E26), BLACK(0x1D1D21);

		/** The banner tint, 0xRRGGBB, as vanilla's {@code DyeColor.getTextureDiffuseColor()}. */
		public final int rgb;

		Dye(int rgb) {
			this.rgb = rgb;
		}

		/** Vanilla's name, {@code light_blue}. */
		public String id() {
			return this.name().toLowerCase(Locale.ROOT);
		}

		public int argb() {
			return 0xFF000000 | this.rgb;
		}

		public static @Nullable Dye byId(@Nullable String id) {
			if (id != null) {
				for (Dye dye : values()) {
					if (dye.id().equals(id)) {
						return dye;
					}
				}
			}
			return null;
		}

		/** The dye closest to a colour, by distance in RGB weighted toward how eyes see it. */
		public static Dye nearest(int argb) {
			int r = (argb >> 16) & 255, g = (argb >> 8) & 255, b = argb & 255;
			Dye best = WHITE;
			long bestDistance = Long.MAX_VALUE;
			for (Dye dye : values()) {
				long dr = r - ((dye.rgb >> 16) & 255), dg = g - ((dye.rgb >> 8) & 255), db = b - (dye.rgb & 255);
				long distance = 3 * dr * dr + 4 * dg * dg + 2 * db * db;
				if (distance < bestDistance) {
					best = dye;
					bestDistance = distance;
				}
			}
			return best;
		}
	}

	/** One pattern, by its registry id, in a dye. */
	public record Layer(String pattern, Dye color) {
		public Layer {
			if (pattern == null || pattern.isBlank() || color == null) {
				throw new IllegalArgumentException("A layer needs a pattern and a dye");
			}
		}
	}

	public BannerDesign {
		if (base == null) {
			throw new IllegalArgumentException("A banner needs a base dye");
		}
		layers = List.copyOf(layers.size() > MAX_LAYERS ? layers.subList(0, MAX_LAYERS) : layers);
	}

	/** A banner of one dye with no patterns. */
	public static BannerDesign plain(Dye base) {
		return new BannerDesign(base, List.of());
	}

	/** The plain banner a waypoint of the old format gets: the dye nearest its colour. */
	public static BannerDesign forColour(int argb) {
		return plain(Dye.nearest(argb));
	}

	/** The banner new death markers get: black, with a skull and a border. */
	public static BannerDesign death() {
		return new BannerDesign(Dye.BLACK, List.of(
			new Layer("minecraft:skull", Dye.WHITE),
			new Layer("minecraft:border", Dye.GRAY)
		));
	}

	public BannerDesign withBase(Dye base) {
		return new BannerDesign(base, this.layers);
	}

	public BannerDesign withLayers(List<Layer> layers) {
		return new BannerDesign(this.base, layers);
	}

	/** Whether a vanilla banner item could hold this design. */
	public boolean fitsVanilla() {
		return this.layers.size() <= VANILLA_LAYERS;
	}

	/** The colour waypoints keep alongside the banner, for older versions and other mods: the base dye. */
	public int colour() {
		return this.base.argb();
	}

	// Sharing: a compact field at the end of a chat line. Patterns in the minecraft namespace
	// drop it, so a vanilla design with six layers stays well under the chat limit.

	/** The design as a short URL-safe base64 text, for {@link #decode}. */
	public String encode() {
		StringBuilder text = new StringBuilder(Integer.toHexString(this.base.ordinal()));
		for (Layer layer : this.layers) {
			String id = layer.pattern().startsWith("minecraft:") ? layer.pattern().substring(10) : layer.pattern();
			text.append(';').append(id).append(',').append(Integer.toHexString(layer.color().ordinal()));
		}
		return Base64.getUrlEncoder().withoutPadding().encodeToString(text.toString().getBytes(StandardCharsets.UTF_8));
	}

	/** Reads {@link #encode}'s text; null if it isn't one. */
	public static @Nullable BannerDesign decode(String encoded) {
		try {
			String text = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
			String[] parts = text.split(";", -1);
			Dye base = dye(parts[0]);
			if (base == null || parts.length - 1 > MAX_LAYERS) {
				return null;
			}
			List<Layer> layers = new ArrayList<>();
			for (int i = 1; i < parts.length; i++) {
				int comma = parts[i].lastIndexOf(',');
				if (comma <= 0) {
					return null;
				}
				String id = parts[i].substring(0, comma);
				Dye color = dye(parts[i].substring(comma + 1));
				if (color == null || !id.matches("[a-z0-9_.\\-]+(:[a-z0-9_./\\-]+)?")) {
					return null;
				}
				layers.add(new Layer(id.contains(":") ? id : "minecraft:" + id, color));
			}
			return new BannerDesign(base, layers);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static @Nullable Dye dye(String hex) {
		try {
			int index = Integer.parseInt(hex, 16);
			return index >= 0 && index < Dye.values().length ? Dye.values()[index] : null;
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
