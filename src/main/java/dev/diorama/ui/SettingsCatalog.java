package dev.diorama.ui;

import dev.diorama.EnvironmentLighting;
import dev.diorama.MapDirection;
import dev.diorama.Settings;
import dev.diorama.render.gpu.MapEffect;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Predicate;

/**
 * Every setting a screen can change, once: its translation key, the section it belongs to, the
 * {@link Settings} field it reads and writes, and the names of its values. The settings screen,
 * the map's book and Mod Menu all build from this, so they always offer the same options.
 * Pure apart from the settings it is given, for tests.
 */
public final class SettingsCatalog {
	/** The groups the settings are shown in. Only {@link #MINIMAP} is about the minimap alone. */
	public enum Section {
		MINIMAP("diorama.settings.minimap"),
		RADAR("diorama.settings.radar"),
		OVERLAYS("diorama.settings.overlays"),
		BANNERS("diorama.settings.banners"),
		WAYPOINTS("diorama.settings.waypoints"),
		REGIONS("diorama.settings.regions"),
		LOOK("diorama.settings.look");

		public final String key;

		Section(String key) {
			this.key = key;
		}

		/** Whether the world map's book offers it; the rest leave the map for the settings screen. */
		public boolean onMap() {
			return this != MINIMAP;
		}
	}

	/**
	 * One setting. {@code values} are translation keys, or plain text when {@code literal}; the
	 * value shown is {@code values[get()]}. {@code shown} is false where this version can't do it
	 * at all (day and night lighting), and the setting is left out; {@code active} is false while
	 * another setting makes it do nothing (the antique tint under the clouds), and it is greyed out.
	 */
	public record Entry(
		String key, Section section, String field, String[] values, boolean literal, boolean tooltip,
		IntSupplier get, IntConsumer set, BooleanSupplier shown, BooleanSupplier active
	) {
		public int count() {
			return this.values.length;
		}

		/** Whether it is an off/on switch. */
		public boolean toggle() {
			return this.values.length == 2 && "options.off".equals(this.values[0]) && "options.on".equals(this.values[1]);
		}

		public int value() {
			return Math.floorMod(this.get.getAsInt(), this.values.length);
		}

		/** Sets value {@code i} (wrapped into range). Call {@link Settings#save()} after. */
		public void choose(int i) {
			this.set.accept(Math.floorMod(i, this.values.length));
		}

		public String tooltipKey() {
			return this.key + ".tooltip";
		}
	}

	private SettingsCatalog() {
	}

	/** Every setting, in the order the settings screen lists them. */
	public static List<Entry> all(Settings s) {
		List<Entry> out = new ArrayList<>();
		BooleanSupplier always = () -> true;

		out.add(toggle("diorama.settings.minimap_shown", Section.MINIMAP, "minimap", () -> s.minimap, v -> s.minimap = v));
		out.add(choice("diorama.settings.size", Section.MINIMAP, "minimapSize", Settings.Size.values(), () -> s.minimapSize, v -> s.minimapSize = v));
		out.add(choice("diorama.settings.corner", Section.MINIMAP, "minimapCorner", Settings.Corner.values(), () -> s.minimapCorner, v -> s.minimapCorner = v));
		out.add(choice("diorama.settings.minimap_frame", Section.MINIMAP, "minimapFrame", Settings.MinimapFrame.values(), () -> s.minimapFrame, v -> s.minimapFrame = v));
		String[] zooms = new String[Settings.ZOOMS.length];
		for (int i = 0; i < zooms.length; i++) {
			zooms[i] = Settings.ZOOMS[i] + "×";
		}
		out.add(new Entry("diorama.settings.zoom", Section.MINIMAP, "minimapZoom", zooms, true, false,
			() -> s.minimapZoom, i -> s.minimapZoom = i, always, always));
		out.add(toggle("diorama.settings.compass", Section.MINIMAP, "compass", () -> s.compass, v -> s.compass = v));
		out.add(toggle("diorama.settings.coordinates", Section.MINIMAP, "coordinates", () -> s.coordinates, v -> s.coordinates = v));
		out.add(toggle("diorama.settings.biome", Section.MINIMAP, "biome", () -> s.biome, v -> s.biome = v));
		out.add(toggle("diorama.settings.auto_caves", Section.MINIMAP, "autoCaves", () -> s.autoCaves, v -> s.autoCaves = v));
		out.add(toggle("diorama.settings.regions_minimap", Section.MINIMAP, "minimapRegions", () -> s.minimapRegions, v -> s.minimapRegions = v));
		out.add(shownIf(toggle("diorama.settings.minimap_clouds", Section.MINIMAP, "minimapClouds", () -> s.minimapClouds, v -> s.minimapClouds = v),
			MapEffect::supported));

		out.add(toggle("diorama.settings.players", Section.RADAR, "radarPlayers", () -> s.radarPlayers, v -> s.radarPlayers = v));
		out.add(toggle("diorama.settings.hostile", Section.RADAR, "radarHostile", () -> s.radarHostile, v -> s.radarHostile = v));
		out.add(toggle("diorama.settings.neutral", Section.RADAR, "radarNeutral", () -> s.radarNeutral, v -> s.radarNeutral = v));
		out.add(toggle("diorama.settings.passive", Section.RADAR, "radarPassive", () -> s.radarPassive, v -> s.radarPassive = v));
		int[] ranges = Settings.RANGES;
		String[] rangeNames = new String[ranges.length];
		for (int i = 0; i < ranges.length; i++) {
			rangeNames[i] = ranges[i] + " m";
		}
		out.add(new Entry("diorama.settings.range", Section.RADAR, "radarRange", rangeNames, true, false,
			() -> indexOf(ranges, s.radarRange), i -> s.radarRange = ranges[i], always, always));

		out.add(toggle("diorama.settings.chunk_grid", Section.OVERLAYS, "chunkGrid", () -> s.chunkGrid, v -> s.chunkGrid = v));
		out.add(toggle("diorama.settings.slime_chunks", Section.OVERLAYS, "slimeChunks", () -> s.slimeChunks, v -> s.slimeChunks = v));
		out.add(choice("diorama.settings.spawn_spots", Section.OVERLAYS, "spawnSpots", Settings.SpawnSpots.values(), () -> s.spawnSpots, v -> s.spawnSpots = v));

		out.add(toggle("diorama.settings.animate_banners", Section.BANNERS, "animateBanners", () -> s.animateBanners, v -> s.animateBanners = v));
		out.add(toggle("diorama.settings.sepia_banners", Section.BANNERS, "sepiaBanners", () -> s.sepiaBanners, v -> s.sepiaBanners = v));
		out.add(tooltip(toggle("diorama.settings.extra_banner_layers", Section.BANNERS, "extraBannerLayers", () -> s.extraBannerLayers, v -> s.extraBannerLayers = v)));

		out.add(toggle("diorama.settings.regions_map", Section.REGIONS, "mapRegions", () -> s.mapRegions, v -> s.mapRegions = v));

		out.add(toggle("diorama.settings.waypoints_map", Section.WAYPOINTS, "waypointsOnMap", () -> s.waypointsOnMap, v -> s.waypointsOnMap = v));
		out.add(toggle("diorama.settings.waypoints_world", Section.WAYPOINTS, "waypointsInWorld", () -> s.waypointsInWorld, v -> s.waypointsInWorld = v));
		out.add(toggle("diorama.settings.deaths", Section.WAYPOINTS, "deathMarkers", () -> s.deathMarkers, v -> s.deathMarkers = v));

		out.add(toggle("diorama.settings.depth_shading", Section.LOOK, "depthShading", () -> s.depthShading, v -> s.depthShading = v));
		out.add(tooltip(choice("diorama.settings.render_style", Section.LOOK, "renderStyle", Settings.RenderStyle.values(), () -> s.renderStyle, v -> s.renderStyle = v)));
		out.add(tooltip(choice("diorama.settings.fog_style", Section.LOOK, "fogStyle", Settings.FogStyle.values(), () -> s.fogStyle, v -> s.fogStyle = v)));
		out.add(tooltip(toggle("diorama.settings.seed_preview", Section.LOOK, "seedPreview", () -> s.seedPreview, v -> s.seedPreview = v)));
		Entry tint = tooltip(toggle("diorama.settings.antique_tint", Section.LOOK, "antiqueTintsExplored", () -> s.antiqueTintsExplored, v -> s.antiqueTintsExplored = v));
		// It only does anything on the two antique maps.
		out.add(new Entry(tint.key(), tint.section(), tint.field(), tint.values(), false, true, tint.get(), tint.set(), always, s::antiqueFog));
		out.add(toggle("diorama.settings.fog_of_war", Section.LOOK, "alwaysFogOfWar", () -> s.alwaysFogOfWar, v -> s.alwaysFogOfWar = v));
		out.add(shownIf(tooltip(toggle("diorama.settings.clouds", Section.LOOK, "mapClouds", () -> s.mapClouds, v -> s.mapClouds = v)),
			MapEffect::supported));
		out.add(shownIf(tooltip(toggle("diorama.settings.coast", Section.LOOK, "mapCoast", () -> s.mapCoast, v -> s.mapCoast = v)),
			MapEffect::supported));
		out.add(choice("diorama.settings.quality", Section.LOOK, "quality", Settings.Quality.values(), () -> s.quality, v -> s.quality = v));
		out.add(choice("diorama.settings.direction", Section.LOOK, "mapDirection", MapDirection.values(), () -> s.mapDirection, v -> s.mapDirection = v));
		Entry lighting = choice("diorama.settings.lighting", Section.LOOK, "lighting", EnvironmentLighting.Mode.values(), () -> s.lighting, v -> s.lighting = v);
		out.add(new Entry(lighting.key(), lighting.section(), lighting.field(), lighting.values(), false, false, lighting.get(), lighting.set(),
			EnvironmentLighting::supported, always));
		return out;
	}

	/** The settings of one section, in order. */
	public static List<Entry> in(Settings s, Section section) {
		return filter(s, e -> e.section() == section);
	}

	public static List<Entry> filter(Settings s, Predicate<Entry> keep) {
		List<Entry> out = new ArrayList<>();
		for (Entry entry : all(s)) {
			if (keep.test(entry)) {
				out.add(entry);
			}
		}
		return out;
	}

	/** The setting with this translation key. */
	public static Entry find(Settings s, String key) {
		for (Entry entry : all(s)) {
			if (entry.key().equals(key)) {
				return entry;
			}
		}
		throw new IllegalArgumentException("No setting " + key);
	}

	/** The entry, left out where this version can't do it. */
	private static Entry shownIf(Entry e, BooleanSupplier shown) {
		return new Entry(e.key(), e.section(), e.field(), e.values(), e.literal(), e.tooltip(), e.get(), e.set(), shown, e.active());
	}

	private static Entry toggle(String key, Section section, String field, BooleanSupplier get, Consumer<Boolean> set) {
		return new Entry(key, section, field, new String[] {"options.off", "options.on"}, false, false,
			() -> get.getAsBoolean() ? 1 : 0, i -> set.accept(i == 1), () -> true, () -> true);
	}

	/** An enum setting; each value's name is the key plus its constant in lower case. */
	private static <E extends Enum<E>> Entry choice(String key, Section section, String field, E[] constants, java.util.function.Supplier<E> get, Consumer<E> set) {
		String[] names = new String[constants.length];
		for (int i = 0; i < constants.length; i++) {
			names[i] = key + "." + constants[i].name().toLowerCase(Locale.ROOT);
		}
		return new Entry(key, section, field, names, false, false, () -> get.get().ordinal(), i -> set.accept(constants[i]), () -> true, () -> true);
	}

	private static Entry tooltip(Entry e) {
		return new Entry(e.key(), e.section(), e.field(), e.values(), e.literal(), true, e.get(), e.set(), e.shown(), e.active());
	}

	private static int indexOf(int[] values, int value) {
		for (int i = 0; i < values.length; i++) {
			if (values[i] == value) {
				return i;
			}
		}
		return 1;
	}
}
