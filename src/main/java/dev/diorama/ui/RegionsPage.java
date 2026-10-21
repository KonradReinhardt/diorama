package dev.diorama.ui;

import dev.diorama.region.MapRegion;
import dev.diorama.region.RegionGeometry;
import dev.diorama.region.RegionStore;
import dev.diorama.region.RegionStyle;
import dev.diorama.waypoint.Waypoints;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The regions section's list: the regions of this dimension on the left page, nearest first, each
 * with its dye and size, and the one picked on the right: its name written in place, its dye, fill
 * and where it shows, and what to do with it. Every change but the name is one step of the regions'
 * history (Ctrl+Z on the map takes it back); the name is written as the region is left.
 */
final class RegionsPage {
	private static final int ROW = 15;

	private static @Nullable UUID selected;
	private static int listPage;

	/** The name typed for the region picked, not written yet. */
	private @Nullable String typed;
	private @Nullable UUID typedFor;
	private boolean deleteArmed;
	/** The name field takes the keyboard when it is next built: the region was just drawn. */
	private boolean nameFirst;

	void select(MapRegion region) {
		this.commit();
		selected = region.id;
		this.deleteArmed = false;
		int index = rows().indexOf(region);
		if (index >= 0) {
			listPage = -1 - index;
		}
	}

	/** Picks a region just drawn, to be named. */
	void name(MapRegion region) {
		this.select(region);
		this.nameFirst = true;
	}

	/** The region the right page shows, if it still exists. */
	@Nullable MapRegion picked() {
		RegionStore store = RegionStore.get();
		return store == null || selected == null ? null : store.find(selected);
	}

	/** Writes the name typed, as one step of the history. */
	void commit() {
		String typed = this.typed;
		UUID id = this.typedFor;
		this.typed = null;
		this.typedFor = null;
		RegionStore store = RegionStore.get();
		MapRegion region = store == null || id == null ? null : store.find(id);
		if (region == null || typed == null || typed.isBlank() || typed.strip().equals(region.name)) {
			return;
		}
		MapRegion after = region.copy();
		after.name = typed.strip();
		store.commit(region.copy(), after);
	}

	/** The regions of this dimension, nearest the player first. */
	static List<MapRegion> rows() {
		RegionStore store = RegionStore.get();
		String dimension = Waypoints.dimension();
		LocalPlayer player = Minecraft.getInstance().player;
		if (store == null || dimension == null) {
			return List.of();
		}
		List<MapRegion> rows = new ArrayList<>(store.in(dimension));
		if (player != null) {
			double x = player.getX(), z = player.getZ();
			rows.sort(Comparator.comparingDouble(r -> RegionGeometry.distance(r.rings, x, z)));
		}
		return rows;
	}

	void build(MapBook book, PageFlow flow) {
		List<MapRegion> rows = rows();
		MapRegion current = this.picked();
		flow.title(Component.translatable(MapBook.Section.REGIONS.key));
		this.list(book, flow, rows);
		flow.breakPage();
		if (current == null) {
			flow.heading(Component.translatable("diorama.book.regions.none"));
			flow.paragraph(Component.translatable(rows.isEmpty() ? "diorama.book.regions.empty" : "diorama.book.regions.pick"));
		} else {
			this.detail(book, flow, current);
		}
	}

	private void list(MapBook book, PageFlow flow, List<MapRegion> rows) {
		// As many rows as leave room for the arrows and the button under them.
		int fit = Math.max(3, (flow.content().bottom() - flow.content().y() - 17 - 12 - BookWidgets.BUTTON - 8) / ROW);
		int pages = Math.max(1, (rows.size() + fit - 1) / fit);
		if (listPage < 0) {
			// Turn to the page with the region just picked.
			listPage = (-1 - listPage) / fit;
		}
		listPage = Math.max(0, Math.min(pages - 1, listPage));
		if (rows.isEmpty()) {
			flow.paragraph(Component.translatable("diorama.book.regions.empty"));
		}
		for (int i = listPage * fit; i < Math.min(rows.size(), (listPage + 1) * fit); i++) {
			MapRegion region = rows.get(i);
			int at = flow.take(ROW);
			Row row = new Row(flow.x(), at, flow.width(), region, () -> region.id.equals(selected), () -> {
				this.select(region);
				book.host().rebuild();
			});
			flow.add(row);
		}
		if (pages > 1) {
			int at = flow.take(12);
			int x = flow.x(), w = flow.width();
			if (listPage > 0) {
				flow.add(new BookWidgets.Mark(x, at, BookSprites.ARROW_LEFT, BookSprites.ARROW_LEFT_HOVER, Component.translatable("diorama.book.previous"), () -> {
					listPage--;
					book.host().rebuild();
				}));
			}
			if (listPage < pages - 1) {
				flow.add(new BookWidgets.Mark(x + w - 7, at, BookSprites.ARROW_RIGHT, BookSprites.ARROW_RIGHT_HOVER, Component.translatable("diorama.book.next"), () -> {
					listPage++;
					book.host().rebuild();
				}));
			}
			int page = listPage + 1;
			flow.centered(() -> Component.translatable("diorama.book.page", page, pages), x, at + 1, w, BookSprites.INK_SOFT);
		}
		flow.gap(2);
		flow.buttons(List.of(new PageFlow.ButtonSpec(Component.translatable("diorama.book.regions.new"), () -> {
			this.commit();
			book.host().drawRegions(null);
		})));
	}

	private void detail(MapBook book, PageFlow flow, MapRegion region) {
		MapBook.Host host = book.host();
		RegionStore store = RegionStore.get();
		boolean editable = store != null && !store.readOnly();
		flow.heading(Component.translatable("diorama.book.regions.region"));
		int at = flow.take(14);
		EditBox box = this.field(flow, flow.x(), at, flow.width(), region.id.equals(this.typedFor) && this.typed != null ? this.typed : region.name);
		if (box != null) {
			box.setEditable(editable);
			box.setResponder(text -> {
				this.typed = text;
				this.typedFor = region.id;
			});
			if (this.nameFirst) {
				this.nameFirst = false;
				host.focus(box);
			}
		}
		flow.gap(2);
		flow.heading(Component.translatable("diorama.book.regions.dye"));
		int palette = flow.take(2 * DyeSwatch.SIZE + 3);
		int left = flow.x() + (flow.width() - 8 * DyeSwatch.SIZE) / 2;
		DyeSwatch.palette(left, palette, () -> region.dye, dye -> this.change(host, region, r -> r.dye = dye), flow::add);
		flow.stepper(Component.translatable("diorama.regions.fill"), () -> Component.translatable("diorama.regions.fill." + name(region.fill)),
			d -> this.change(host, region, r -> r.fill = MapRegion.Fill.values()[Math.floorMod(r.fill.ordinal() + d, MapRegion.Fill.values().length)]),
			() -> editable);
		flow.stepper(Component.translatable("diorama.book.regions.on_map"), () -> onOff(region.visible),
			d -> this.change(host, region, r -> r.visible = !r.visible), () -> editable);
		flow.stepper(Component.translatable("diorama.book.regions.on_minimap"), () -> onOff(region.onMinimap),
			d -> this.change(host, region, r -> r.onMinimap = !r.onMinimap), () -> editable);
		flow.stepper(Component.translatable("diorama.book.regions.label"), () -> onOff(region.showLabel),
			d -> this.change(host, region, r -> r.showLabel = !r.showLabel), () -> editable);
		RegionStyle[] styles = RegionStyle.values();
		flow.stepper(Component.translatable("diorama.book.regions.style"), () -> region.style == RegionStyle.AUTO
				? Component.translatable("diorama.book.regions.style.auto")
				: Component.translatable("diorama.settings.region_style." + name(region.style)),
			d -> this.change(host, region, r -> r.style = styles[Math.floorMod(r.style.ordinal() + d, styles.length)]), () -> editable);
		flow.gap(2);
		List<PageFlow.ButtonSpec> buttons = new ArrayList<>();
		buttons.add(new PageFlow.ButtonSpec(Component.translatable("diorama.book.center"), () -> host.centerOn(region)));
		buttons.add(new PageFlow.ButtonSpec(Component.translatable("diorama.book.regions.edit"), () -> {
			this.commit();
			host.drawRegions(region);
		}, editable));
		buttons.add(new PageFlow.ButtonSpec(Component.translatable(this.deleteArmed ? "diorama.book.confirm" : "diorama.book.delete"), () -> {
			if (!this.deleteArmed) {
				this.deleteArmed = true;
			} else {
				this.typed = null;
				this.typedFor = null;
				if (store != null) {
					store.commit(region.copy(), null);
				}
				this.deleteArmed = false;
				selected = null;
			}
			host.rebuild();
		}, editable));
		flow.buttons(buttons);
		if (store != null && store.readOnly()) {
			flow.paragraph(Component.translatable("diorama.regions.read_only"));
		}
	}

	/** One step of the history: the region changed by {@code edit}, the page drawn again. */
	private void change(MapBook.Host host, MapRegion region, Consumer<MapRegion> edit) {
		this.commit();
		RegionStore store = RegionStore.get();
		MapRegion now = store == null ? null : store.find(region.id);
		if (now != null) {
			MapRegion after = now.copy();
			edit.accept(after);
			store.commit(now.copy(), after);
		}
		this.deleteArmed = false;
		host.rebuild();
	}

	private static String name(Enum<?> value) {
		return value.name().toLowerCase(java.util.Locale.ROOT);
	}

	private static Component onOff(boolean on) {
		return Component.translatable(on ? "options.on" : "options.off");
	}

	/** A line to write on, as the banners' page has. */
	private @Nullable EditBox field(PageFlow flow, int x, int y, int width, String value) {
		Component hint = Component.translatable("diorama.waypoint.name");
		boolean inked = BookSprites.INKED_FIELDS;
		EditBox box = inked ? new EditBox(BookWidgets.font(), x + 2, y + 2, width - 4, 9, hint) : new EditBox(BookWidgets.font(), x, y, width, 12, hint);
		box.setMaxLength(32);
		box.setValue(value);
		box.setHint(hint);
		if (inked) {
			BookSprites.ink(box);
			flow.decorate((graphics, dy, alpha) -> BookSprites.nine(graphics, BookSprites.FIELD, x, y + dy, width, 12, BookWidgets.fade(-1, alpha), true));
			box.setTextColor(BookSprites.INK);
		}
		return flow.add(box);
	}

	/** A region in the list: its dye, its name and how big it is. */
	private static final class Row extends BookWidget {
		private final MapRegion region;
		private final java.util.function.BooleanSupplier picked;
		private final Runnable action;

		Row(int x, int y, int width, MapRegion region, java.util.function.BooleanSupplier picked, Runnable action) {
			super(x, y, width, ROW - 1, Component.literal(region.name));
			this.region = region;
			this.picked = picked;
			this.action = action;
			this.setTooltip(Tooltip.create(Component.literal(region.name)));
		}

		@Override
		protected void draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean lit) {
			int x = this.getX(), y = this.getY(), w = this.width, h = this.height;
			if (this.picked.getAsBoolean() || lit) {
				BookSprites.nine(graphics, this.picked.getAsBoolean() ? BookSprites.SLOT_SELECTED : BookSprites.SLOT_HOVER, x, y, w, h, BookWidgets.fade(-1, this.alpha), true);
			}
			float shown = this.alpha * (this.region.visible ? 1 : 0.45F);
			graphics.fill(x + 3, y + 3, x + 11, y + 11, BookWidgets.fade(BookSprites.INK, shown));
			graphics.fill(x + 4, y + 4, x + 10, y + 10, BookWidgets.fade(this.region.dye.argb(), shown));
			String area = Math.round(this.region.area()) + " m²";
			int areaW = BookWidgets.font().width(area);
			Component label = BookWidgets.fit(Component.literal(this.region.name), w - 18 - areaW - 6);
			graphics.text(BookWidgets.font(), label, x + 15, y + 3, BookWidgets.fade(BookSprites.INK, shown), false);
			graphics.text(BookWidgets.font(), area, x + w - 3 - areaW, y + 3, BookWidgets.fade(BookSprites.INK_SOFT, shown), false);
		}

		@Override
		protected void press(double x, double y) {
			this.action.run();
		}
	}
}
