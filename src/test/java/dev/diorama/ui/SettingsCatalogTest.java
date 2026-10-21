package dev.diorama.ui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.diorama.Settings;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SettingsCatalogTest {
	private static final Set<String> MINIMAP_ONLY = Set.of(
		"minimap", "minimapSize", "minimapCorner", "minimapFrame", "minimapZoom", "compass", "coordinates", "biome", "autoCaves", "minimapClouds");

	@Test
	void everyPublicSettingAppearsOnce() {
		Set<String> fields = new HashSet<>();
		for (Field field : Settings.class.getDeclaredFields()) {
			int m = field.getModifiers();
			if (Modifier.isPublic(m) && !Modifier.isStatic(m)) {
				fields.add(field.getName());
			}
		}
		List<SettingsCatalog.Entry> all = SettingsCatalog.all(new Settings());
		Set<String> seen = new HashSet<>();
		for (SettingsCatalog.Entry entry : all) {
			assertTrue(seen.add(entry.field()), "listed twice: " + entry.field());
			assertTrue(fields.contains(entry.field()), "not a public setting: " + entry.field());
		}
		assertEquals(fields, seen);
	}

	@Test
	void onlyMinimapSettingsLeaveTheWorldMap() {
		for (SettingsCatalog.Entry entry : SettingsCatalog.all(new Settings())) {
			assertEquals(MINIMAP_ONLY.contains(entry.field()), entry.section() == SettingsCatalog.Section.MINIMAP, entry.field());
			assertEquals(!MINIMAP_ONLY.contains(entry.field()), entry.section().onMap(), entry.field());
		}
	}

	@Test
	void choosingWritesTheSettingAndWraps() {
		Settings s = new Settings();
		SettingsCatalog.Entry render = SettingsCatalog.find(s, "diorama.settings.render_style");
		render.choose(Settings.RenderStyle.PAINTED.ordinal());
		assertEquals(Settings.RenderStyle.PAINTED, s.renderStyle);
		render.choose(render.count());
		assertEquals(Settings.RenderStyle.PLAIN, s.renderStyle);
		SettingsCatalog.Entry range = SettingsCatalog.find(s, "diorama.settings.range");
		range.choose(3);
		assertEquals(Settings.RANGES[3], s.radarRange);
		assertEquals(3, range.value());
		SettingsCatalog.Entry grid = SettingsCatalog.find(s, "diorama.settings.chunk_grid");
		assertTrue(grid.toggle());
		grid.choose(1);
		assertTrue(s.chunkGrid);
	}

	@Test
	void antiqueTintIsActiveOnlyOnAntiqueMaps() {
		Settings s = new Settings();
		SettingsCatalog.Entry tint = SettingsCatalog.find(s, "diorama.settings.antique_tint");
		s.fogStyle = Settings.FogStyle.CLOUDS;
		assertFalse(tint.active().getAsBoolean());
		s.fogStyle = Settings.FogStyle.ANCIENT_MAP_COLOR;
		assertTrue(tint.active().getAsBoolean());
	}

	@Test
	void everyKeyIsTranslated() throws Exception {
		for (String language : List.of("en_us", "pt_br")) {
			JsonObject lang;
			try (var in = new InputStreamReader(SettingsCatalogTest.class.getResourceAsStream("/assets/diorama/lang/" + language + ".json"), StandardCharsets.UTF_8)) {
				lang = JsonParser.parseReader(in).getAsJsonObject();
			}
			for (SettingsCatalog.Section section : SettingsCatalog.Section.values()) {
				assertTrue(lang.has(section.key), language + ": " + section.key);
			}
			assertTrue(lang.has("diorama.settings.title_minimap"), language);
			for (SettingsCatalog.Entry entry : SettingsCatalog.all(new Settings())) {
				assertTrue(lang.has(entry.key()), language + ": " + entry.key());
				if (entry.tooltip()) {
					assertTrue(lang.has(entry.tooltipKey()), language + ": " + entry.tooltipKey());
				}
				if (!entry.literal()) {
					for (String value : entry.values()) {
						assertTrue(value.startsWith("options.") || lang.has(value), language + ": " + value);
					}
				}
			}
		}
	}
}
