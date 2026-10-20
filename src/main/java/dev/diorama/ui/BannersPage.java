package dev.diorama.ui;

import dev.diorama.Settings;
import dev.diorama.render.BannerAtlas;
import dev.diorama.render.BannerCompositor;
import dev.diorama.waypoint.BannerDesign;
import dev.diorama.waypoint.BannerEditScreen;
import dev.diorama.waypoint.Waypoint;
import dev.diorama.waypoint.WaypointDraft;
import dev.diorama.waypoint.WaypointEditScreen;
import dev.diorama.waypoint.WaypointListModel;
import dev.diorama.waypoint.WaypointListScreen;
import dev.diorama.waypoint.WaypointStore;
import dev.diorama.waypoint.XaeroImport;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The banners section's list: the waypoints of this dimension as banners in a grid of slots on the
 * left page, nearest first, and the one picked on the right, its name and position written in
 * place. A waypoint planted by a right-click on the map waits here as a draft until it is saved.
 */
final class BannersPage {
	private static final int SLOT_W = 34, SLOT_H = 36, GRID_ROWS = 3, GAP = 2;
	/** Ink for a coordinate that isn't a whole number. */
	private static final int BAD = 0xFFB02020;

	private static @Nullable UUID selected;
	private static int gridPage;

	/** A waypoint planted but not saved yet. */
	private @Nullable Waypoint draft;
	private @Nullable WaypointDraft fields;
	private @Nullable Waypoint fieldsFor;
	private boolean dirty;
	private boolean deleteArmed;
	/** The name field takes the keyboard when it is next built: a banner was just planted. */
	private boolean nameFirst;
	private @Nullable CompletableFuture<XaeroImport.@Nullable Found> xaero;
	private boolean xaeroShown;
	private @Nullable Component imported;

	void select(Waypoint waypoint) {
		this.commit();
		this.draft = null;
		selected = waypoint.id;
		this.deleteArmed = false;
		List<Waypoint> rows = WaypointListModel.rows();
		int index = rows.indexOf(waypoint);
		if (index >= 0) {
			gridPage = -1 - index;
		}
	}

	void draft(Waypoint waypoint) {
		this.commit();
		this.draft = waypoint;
		selected = null;
		this.deleteArmed = false;
		this.nameFirst = true;
	}

	/** The waypoint the right page shows: the draft, or the one picked if it still exists. */
	private @Nullable Waypoint current(List<Waypoint> rows) {
		if (this.draft != null) {
			return this.draft;
		}
		for (Waypoint w : rows) {
			if (w.id.equals(selected)) {
				return w;
			}
		}
		return null;
	}

	/** The waypoint the right page shows, if any. */
	@Nullable Waypoint picked() {
		return this.current(WaypointListModel.rows());
	}

	boolean drafting() {
		return this.draft != null;
	}

	/** Writes the waypoints changed in place since the last write. */
	void commit() {
		WaypointStore store = WaypointStore.get();
		if (this.dirty && store != null) {
			store.changed();
		}
		this.dirty = false;
	}

	/** Rebuilds once Xaero's files have been read, to offer the import. */
	void frame(MapBook book) {
		if (this.xaero != null && this.xaero.isDone() && !this.xaeroShown && book.isOpen() && book.section() == MapBook.Section.BANNERS) {
			this.xaeroShown = true;
			book.host().rebuild();
		}
	}

	void build(MapBook book, PageFlow flow) {
		if (this.xaero == null) {
			// Read on the client thread, then the files on another.
			XaeroImport.Request request = XaeroImport.request();
			this.xaero = request == null ? CompletableFuture.completedFuture(null) : CompletableFuture.supplyAsync(() -> XaeroImport.find(request));
		}
		List<Waypoint> rows = WaypointListModel.rows();
		Waypoint current = this.current(rows);
		flow.title(Component.translatable(MapBook.Section.BANNERS.key));
		this.grid(book, flow, rows);
		flow.breakPage();
		if (current == null) {
			flow.heading(Component.translatable("diorama.book.banners.none"));
			flow.paragraph(Component.translatable(rows.isEmpty() ? "diorama.book.banners.empty" : "diorama.book.banners.pick"));
		} else {
			this.detail(book, flow, current);
		}
	}

	private void grid(MapBook book, PageFlow flow, List<Waypoint> rows) {
		int cols = Math.max(1, (flow.width() + GAP) / (SLOT_W + GAP));
		int perPage = cols * GRID_ROWS;
		int pages = Math.max(1, (rows.size() + perPage - 1) / perPage);
		if (gridPage < 0) {
			// Turn to the page with the waypoint just picked.
			gridPage = (-1 - gridPage) / perPage;
		}
		gridPage = Math.max(0, Math.min(pages - 1, gridPage));
		if (rows.isEmpty()) {
			flow.paragraph(Component.translatable("diorama.book.banners.empty"));
		}
		int left = flow.x() + (flow.width() - (cols * SLOT_W + (cols - 1) * GAP)) / 2;
		LocalPlayer player = Minecraft.getInstance().player;
		for (int r = 0; r < GRID_ROWS && gridPage * perPage + r * cols < rows.size(); r++) {
			int at = flow.take(SLOT_H + GAP);
			for (int c = 0; c < cols; c++) {
				int index = gridPage * perPage + r * cols + c;
				if (index >= rows.size()) {
					break;
				}
				Waypoint w = rows.get(index);
				BookWidgets.Slot slot = new BookWidgets.Slot(left + c * (SLOT_W + GAP), at, SLOT_W, SLOT_H, Component.literal(w.name),
					(graphics, x, y, width, height, alpha) -> BannerAtlas.shared().draw(graphics, w.design(), BannerCompositor.Size.MAP,
						x + width / 2.0F, y + height - 3, 1, BannerAtlas.frameNow(Settings.get().animateBanners), false,
						BookWidgets.fade(-1, alpha * (w.visible ? 1 : 0.4F))),
					() -> w.id.equals(selected) && this.draft == null, () -> {
						this.select(w);
						book.host().rebuild();
					}, true);
				String distance = player == null ? "" : Math.round(w.distanceTo(player.getX(), player.getY(), player.getZ())) + " m";
				slot.setTooltip(Tooltip.create(Component.literal(w.name + "\n" + distance + " · " + w.x + ", " + w.y + ", " + w.z)));
				flow.add(slot);
			}
		}
		if (pages > 1) {
			int at = flow.take(12);
			int x = flow.x(), w = flow.width();
			if (gridPage > 0) {
				flow.add(new BookWidgets.Mark(x, at, BookSprites.ARROW_LEFT, BookSprites.ARROW_LEFT_HOVER, Component.translatable("diorama.book.previous"), () -> {
					gridPage--;
					book.host().rebuild();
				}));
			}
			if (gridPage < pages - 1) {
				flow.add(new BookWidgets.Mark(x + w - 7, at, BookSprites.ARROW_RIGHT, BookSprites.ARROW_RIGHT_HOVER, Component.translatable("diorama.book.next"), () -> {
					gridPage++;
					book.host().rebuild();
				}));
			}
			int page = gridPage + 1;
			flow.centered(() -> Component.translatable("diorama.book.page", page, pages), x, at + 1, w, BookSprites.INK_SOFT);
		}
		flow.gap(2);
		List<PageFlow.ButtonSpec> buttons = new ArrayList<>();
		buttons.add(new PageFlow.ButtonSpec(Component.translatable("diorama.book.new"), () -> {
			Waypoint fresh = dev.diorama.waypoint.Waypoints.atPlayer();
			if (fresh != null) {
				book.newWaypoint(fresh);
			}
		}));
		XaeroImport.Found found = this.xaero != null && this.xaero.isDone() ? this.xaero.getNow(null) : null;
		int importable = WaypointListModel.importable(found, WaypointStore.get());
		if (importable != 0 && found != null) {
			Component label = importable > 0 ? Component.translatable("diorama.book.import", importable) : Component.translatable("diorama.book.import_pick");
			buttons.add(new PageFlow.ButtonSpec(label, () -> {
				if (found.chosen() != null) {
					this.imported = WaypointListModel.importFrom(found.chosen());
					book.host().rebuild();
				} else {
					Minecraft.getInstance().gui.setScreen(new WaypointListScreen(book.host().screen()));
				}
			}));
		}
		flow.buttons(buttons);
		if (this.imported != null) {
			flow.paragraph(this.imported);
		}
	}

	private void detail(MapBook book, PageFlow flow, Waypoint w) {
		boolean isNew = w == this.draft;
		if (this.fieldsFor != w || this.fields == null) {
			this.fields = new WaypointDraft(w);
			this.fieldsFor = w;
		}
		WaypointDraft fields = this.fields;
		flow.heading(Component.translatable(isNew ? "diorama.book.new_waypoint" : "diorama.waypoint.edit"));
		flow.gap(3);
		int top = flow.take(64);
		int x = flow.x(), width = flow.width();
		// The banner, twice its map size, beside its name and position.
		flow.decorate((graphics, dy, alpha) -> BannerAtlas.shared().draw(graphics, w.design(), BannerCompositor.Size.MAP, x + 14, top + 62 + dy, 2,
			BannerAtlas.frameNow(Settings.get().animateBanners), false, BookWidgets.fade(-1, alpha * (w.visible ? 1 : 0.4F))));
		int fx = x + 34, fw = width - 34;
		flow.text(Component.translatable("diorama.waypoint.name"), fx, top, BookSprites.INK_SOFT);
		EditBox name = this.field(flow, fx, top + 10, fw, fields.name, 32, Component.translatable("diorama.waypoint.name"));
		flow.text(Component.translatable("diorama.book.position"), fx, top + 26, BookSprites.INK_SOFT);
		int cw = (fw - 8) / 3;
		EditBox[] coords = new EditBox[3];
		String[] values = {fields.x, fields.y, fields.z};
		String[] hints = {"diorama.waypoint.x", "diorama.waypoint.y", "diorama.waypoint.z"};
		for (int i = 0; i < 3; i++) {
			coords[i] = this.field(flow, fx + i * (cw + 4), top + 36, cw, values[i], 9, Component.translatable(hints[i]));
		}
		LocalPlayer player = Minecraft.getInstance().player;
		if (player != null) {
			flow.centered(() -> Component.translatable("diorama.book.distance", Math.round(w.distanceTo(player.getX(), player.getY(), player.getZ()))),
				fx, top + 52, fw, BookSprites.INK_SOFT);
		}
		Runnable edited = () -> {
			if (name != null) {
				fields.name = name.getValue();
			}
			if (coords[0] != null) {
				fields.x = coords[0].getValue();
				fields.y = coords[1].getValue();
				fields.z = coords[2].getValue();
				for (EditBox box : coords) {
					box.setTextColor(WaypointDraft.parse(box.getValue()) != null ? this.ink() : BAD);
				}
			}
			if (fields.applyTo(w) && !isNew) {
				this.dirty = true;
			}
		};
		if (name != null) {
			name.setResponder(text -> edited.run());
			if (this.nameFirst) {
				this.nameFirst = false;
				book.host().focus(name);
			}
		}
		for (EditBox box : coords) {
			if (box != null) {
				box.setResponder(text -> edited.run());
			}
		}
		flow.gap(4);
		List<PageFlow.ButtonSpec> buttons = new ArrayList<>();
		MapBook.Host host = book.host();
		if (isNew) {
			buttons.add(new PageFlow.ButtonSpec(Component.translatable("diorama.book.save"), () -> {
				WaypointStore store = WaypointStore.get();
				if (store != null && fields.valid()) {
					fields.applyTo(w);
					store.add(w);
					this.draft = null;
					selected = w.id;
					gridPage = -1 - Math.max(0, WaypointListModel.rows().indexOf(w));
				}
				host.rebuild();
			}));
			buttons.add(new PageFlow.ButtonSpec(Component.translatable("diorama.book.discard"), () -> {
				this.draft = null;
				host.rebuild();
			}));
		} else {
			buttons.add(new PageFlow.ButtonSpec(Component.translatable("diorama.book.center"), () -> host.centerOn(w)));
		}
		buttons.add(new PageFlow.ButtonSpec(Component.translatable("diorama.book.banner"), () -> {
			this.commit();
			Minecraft.getInstance().gui.setScreen(new BannerEditScreen(host.screen(), w.design(), design -> this.banner(w, design, isNew)));
		}));
		if (!isNew) {
			buttons.add(new PageFlow.ButtonSpec(Component.translatable(w.visible ? "diorama.book.hide" : "diorama.book.show"), () -> {
				w.visible = !w.visible;
				this.dirty = true;
				host.rebuild();
			}));
			buttons.add(new PageFlow.ButtonSpec(Component.translatable("diorama.book.share"), () -> WaypointEditScreen.shareInChat(w)));
			if (WaypointEditScreen.canTeleport()) {
				buttons.add(new PageFlow.ButtonSpec(Component.translatable("diorama.book.teleport"), () -> {
					this.commit();
					LocalPlayer p = Minecraft.getInstance().player;
					if (p != null) {
						p.connection.sendCommand("tp @s " + w.x + " " + w.y + " " + w.z);
					}
					Minecraft.getInstance().gui.setScreen(null);
				}));
			}
			buttons.add(new PageFlow.ButtonSpec(Component.translatable(this.deleteArmed ? "diorama.book.confirm" : "diorama.book.delete"), () -> {
				if (!this.deleteArmed) {
					this.deleteArmed = true;
				} else {
					WaypointStore store = WaypointStore.get();
					if (store != null) {
						store.remove(w);
					}
					this.dirty = false;
					this.deleteArmed = false;
					selected = null;
				}
				host.rebuild();
			}));
		}
		flow.buttons(buttons);
	}

	/** A line to write on, inked like the page with its paper drawn behind it, or vanilla's box where ink can't be. */
	private @Nullable EditBox field(PageFlow flow, int x, int y, int width, String value, int max, Component hint) {
		boolean inked = BookSprites.INKED_FIELDS;
		EditBox box = inked ? new EditBox(BookWidgets.font(), x + 2, y + 2, width - 4, 9, hint) : new EditBox(BookWidgets.font(), x, y, width, 12, hint);
		box.setMaxLength(max);
		box.setValue(value);
		box.setHint(hint);
		if (inked) {
			BookSprites.ink(box);
			flow.decorate((graphics, dy, alpha) -> BookSprites.nine(graphics, BookSprites.FIELD, x, y + dy, width, 12, BookWidgets.fade(-1, alpha), true));
		}
		box.setTextColor(WaypointDraft.parse(value) != null || max > 9 ? this.ink() : BAD);
		return flow.add(box);
	}

	/** The colour of text that reads: ink on the page, or vanilla's white in its box. */
	private int ink() {
		return BookSprites.INKED_FIELDS ? BookSprites.INK : EditBox.DEFAULT_TEXT_COLOR;
	}

	/** Sets a waypoint's banner from the loom-style editor. */
	private void banner(Waypoint w, BannerDesign design, boolean isNew) {
		if (design.equals(w.design())) {
			return;
		}
		// The colour kept beside the banner, for older versions: the old colour while the base dye is still its nearest.
		int colour = BannerDesign.Dye.nearest(w.color) == design.base() ? w.color : design.colour();
		w.banner = design;
		w.color = colour;
		WaypointStore store = WaypointStore.get();
		if (!isNew && store != null) {
			store.changed();
		}
	}
}
