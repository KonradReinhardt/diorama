package dev.diorama.ui;

import dev.diorama.MapDirection;
import dev.diorama.MapSession;
import dev.diorama.Settings;
import dev.diorama.SettingsScreen;
import dev.diorama.ui.SettingsCatalog.Entry;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.Component;

/** The book's settings pages, laid out from {@link SettingsCatalog}. */
final class BookPages {
	private static final int THUMB_SLOT_W = BookSprites.THUMB_W + 4, THUMB_SLOT_H = BookSprites.THUMB_H + 4;

	private BookPages() {
	}

	private static Entry entry(String key) {
		return SettingsCatalog.find(Settings.get(), "diorama.settings." + key);
	}

	/**
	 * Render and fog style as pictures, picked straight to a value: a new render style redraws the
	 * whole map, so the book never steps through the ones between.
	 */
	static void look(PageFlow flow) {
		Settings s = Settings.get();
		flow.title(Component.translatable(MapBook.Section.LOOK.key));
		Entry render = entry("render_style");
		flow.heading(Component.translatable(render.key()));
		flow.slots(pictures(render, BookSprites::renderThumb), THUMB_SLOT_W, THUMB_SLOT_H);
		valueLine(flow, render);
		// The look is baked into the tiles, so they are traced again; the view goes first.
		flow.paragraph(Component.translatable("diorama.book.look.render_hint"));
		Entry fog = entry("fog_style");
		flow.heading(Component.translatable(fog.key()));
		flow.slots(pictures(fog, BookSprites::fogThumb), THUMB_SLOT_W, THUMB_SLOT_H);
		valueLine(flow, fog);
		flow.setting(entry("antique_tint"), null);
		flow.setting(entry("seed_preview"), null);

		flow.breakPage();
		flow.heading(Component.translatable("diorama.book.map"));
		flow.setting(entry("fog_of_war"), null);
		flow.setting(entry("clouds"), null);
		flow.setting(entry("coast"), null);
		flow.setting(entry("depth_shading"), null);
		flow.setting(entry("quality"), null);
		flow.setting(entry("lighting"), null);
		flow.gap(3);
		Entry direction = entry("direction");
		flow.heading(Component.translatable(direction.key()));
		List<PageFlow.Choice> directions = new ArrayList<>();
		for (MapDirection d : MapDirection.values()) {
			Component name = SettingsScreen.valueName(direction, d.ordinal());
			directions.add(new PageFlow.Choice(name, (graphics, x, y, w, h, alpha) -> {
				boolean on = s.mapDirection == d;
				String letter = name.getString().substring(0, 1);
				if (on) {
					BookSprites.sprite(graphics, BookSprites.NEEDLE, x + (w - 9) / 2, y + 2, BookWidgets.fade(-1, alpha));
				}
				graphics.text(BookWidgets.font(), letter, x + (w - BookWidgets.font().width(letter)) / 2, y + (on ? 12 : 8),
					BookWidgets.fade(BookSprites.INK, alpha), false);
			}, () -> s.mapDirection == d, () -> direction.choose(d.ordinal())));
		}
		flow.slots(directions, 24, 22);
		valueLine(flow, direction);
	}

	/** The pictures of a style setting's values, one slot each. */
	private static List<PageFlow.Choice> pictures(Entry entry, java.util.function.IntFunction<BookSprites.Region> thumbs) {
		List<PageFlow.Choice> choices = new ArrayList<>();
		for (int i = 0; i < entry.count(); i++) {
			int value = i;
			BookSprites.Region thumb = thumbs.apply(i);
			choices.add(new PageFlow.Choice(SettingsScreen.valueName(entry, i),
				(graphics, x, y, w, h, alpha) -> BookSprites.sprite(graphics, thumb, x + 2, y + 2, BookWidgets.fade(-1, alpha)),
				() -> entry.value() == value, () -> entry.choose(value)));
		}
		return choices;
	}

	/** The name of a setting's current value, under its pictures. */
	private static void valueLine(PageFlow flow, Entry entry) {
		int at = flow.take(11);
		flow.centered(() -> SettingsScreen.valueName(entry, entry.value()), flow.x(), at, flow.width(), BookSprites.INK);
	}

	static void layers(MapBook book, PageFlow flow) {
		MapBook.Host host = book.host();
		flow.title(Component.translatable(MapBook.Section.LAYERS.key));
		flow.heading(Component.translatable("diorama.book.view"));
		List<PageFlow.Choice> views = new ArrayList<>();
		String[][] kinds = {{"grass_block", "diorama.book.surface"}, {"lantern", "diorama.book.caves"}};
		for (int i = 0; i < kinds.length; i++) {
			boolean caves = i == 1;
			var icon = BookSprites.stack(kinds[i][0]);
			Component name = Component.translatable(kinds[i][1]);
			views.add(new PageFlow.Choice(name, (graphics, x, y, w, h, alpha) -> {
				BookSprites.item(graphics, icon, x + 4, y + (h - 16) / 2);
				graphics.text(BookWidgets.font(), BookWidgets.fit(name, w - 26), x + 22, y + (h - 8) / 2 + 1, BookWidgets.fade(BookSprites.INK, alpha), false);
			}, () -> host.caves() == caves, () -> {
				if (host.caves() != caves) {
					host.toggleCaves();
				}
			}));
		}
		flow.slots(views, Math.min(72, (flow.width() - 3) / 2), 22);
		flow.stepper(Component.translatable("diorama.book.slice"), () -> Component.literal(host.caves() ? "y=" + host.layer() : "—"),
			d -> host.shiftSlice(4 * d), host::caves);
		flow.gap(2);
		flow.paragraph(Component.translatable("diorama.book.layers.hint"));

		flow.breakPage();
		flow.heading(Component.translatable("diorama.settings.overlays"));
		flow.setting(entry("chunk_grid"), null);
		Entry slime = entry("slime_chunks");
		// Slime chunks follow from the world seed, which only singleplayer and some servers give.
		flow.setting(new Entry(slime.key(), slime.section(), slime.field(), slime.values(), slime.literal(), slime.tooltip(), slime.get(), slime.set(),
			slime.shown(), () -> MapSession.get() != null && MapSession.get().slimeSeed() != null), null);
		flow.setting(entry("spawn_spots"), null);
	}

	static void radar(PageFlow flow) {
		flow.title(Component.translatable(MapBook.Section.RADAR.key));
		flow.setting(entry("players"), BookSprites.stack("player_head"));
		flow.setting(entry("hostile"), BookSprites.stack("creeper_head"));
		flow.setting(entry("neutral"), BookSprites.stack("wolf_spawn_egg"));
		flow.setting(entry("passive"), BookSprites.stack("cow_spawn_egg"));
		flow.setting(entry("range"), null);
		flow.breakPage();
		flow.heading(Component.translatable("diorama.book.radar.about"));
		flow.paragraph(Component.translatable("diorama.book.radar.hint"));
	}

	/**
	 * The regions' options: their style as pictures, picked straight to a value, the one place it
	 * is chosen; then the rest.
	 */
	static void regionOptions(PageFlow flow) {
		flow.title(Component.translatable("diorama.book.regions.options"));
		Entry style = entry("region_style");
		flow.heading(Component.translatable(style.key()));
		flow.slots(pictures(style, BookSprites::regionThumb), THUMB_SLOT_W, THUMB_SLOT_H);
		valueLine(flow, style);
		flow.gap(2);
		for (Entry e : SettingsCatalog.in(Settings.get(), SettingsCatalog.Section.REGIONS)) {
			if (!e.field().equals("regionStyle")) {
				flow.setting(e, null);
			}
		}
		// The minimap's own, offered here too beside the world map's.
		flow.setting(entry("regions_minimap"), null);
		flow.breakPage();
		flow.heading(Component.translatable("diorama.book.regions.tools"));
		flow.paragraph(Component.translatable("diorama.book.regions.tools_hint"));
	}

	static void bannerOptions(PageFlow flow) {
		flow.title(Component.translatable("diorama.book.options"));
		flow.heading(Component.translatable("diorama.settings.waypoints"));
		for (Entry e : SettingsCatalog.in(Settings.get(), SettingsCatalog.Section.WAYPOINTS)) {
			flow.setting(e, null);
		}
		flow.breakPage();
		flow.heading(Component.translatable("diorama.settings.banners"));
		for (Entry e : SettingsCatalog.in(Settings.get(), SettingsCatalog.Section.BANNERS)) {
			flow.setting(e, null);
		}
	}
}
