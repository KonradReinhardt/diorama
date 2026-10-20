package dev.diorama.ui;

import dev.diorama.Settings;
import dev.diorama.SettingsScreen;
import dev.diorama.render.Spring;
import dev.diorama.waypoint.Waypoint;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The world map's menus as a book over the map. Shut, only its ribbons show at the screen's right
 * edge; a ribbon opens it at its section, and the ribbons move to the book's side. The map stays
 * live around it, so a change of look shows at once. The map screen owns one and hands it its
 * widgets, draws and input; the section and the waypoint picked are remembered while the game runs.
 */
public final class MapBook {
	/** The sections, top to bottom along the ribbons. */
	public enum Section {
		BANNERS("diorama.book.banners", 0xFFD8453A, "white_banner"),
		LOOK("diorama.book.look", 0xFF4A86D8, "filled_map"),
		LAYERS("diorama.book.layers", 0xFF5AAE4C, "lantern"),
		RADAR("diorama.book.radar", 0xFFE8B83A, "spyglass"),
		/** Leaves the map for the minimap's settings. */
		MINIMAP("diorama.book.minimap", 0xFFF4E6C8, "compass");

		public final String key;
		public final int tint;
		public final String item;

		Section(String key, int tint, String item) {
			this.key = key;
			this.tint = tint;
			this.item = item;
		}
	}

	/** What the book needs from the map screen it sits on. */
	public interface Host {
		<T extends GuiEventListener & Renderable & NarratableEntry> T add(T widget);

		/** Builds the screen's widgets again, which builds the book again. */
		void rebuild();

		/** The map screen, to come back to from the screens the book opens. */
		Screen screen();

		void focus(GuiEventListener widget);

		boolean caves();

		int layer();

		void toggleCaves();

		void shiftSlice(int delta);

		void centerOn(Waypoint waypoint);
	}

	/** Text on a page; {@code width} above 0 centres it on that band. */
	record Label(Supplier<Component> text, int x, int y, int width, int color, boolean centered) {
	}

	/** Something drawn on the pages before their widgets, {@code dy} lower while the book rises. */
	@FunctionalInterface
	public interface Decoration {
		void draw(GuiGraphicsExtractor graphics, int dy, float alpha);
	}

	/** The banners' icon: a plain white banner as the maps draw it, waving with them. */
	public static final BookWidgets.Art BANNER_ICON = (graphics, x, y, width, height, alpha) -> dev.diorama.render.BannerAtlas.shared().draw(graphics,
		new dev.diorama.waypoint.BannerDesign(dev.diorama.waypoint.BannerDesign.Dye.WHITE, List.of()), dev.diorama.render.BannerCompositor.Size.SMALL,
		x + width / 2.0F, y + height + 1, 1, dev.diorama.render.BannerAtlas.frameNow(Settings.get().animateBanners), false, BookWidgets.fade(-1, alpha));

	private static BookWidgets.Art icon(Section s) {
		return s == Section.BANNERS ? BANNER_ICON : BookWidgets.item(BookSprites.stack(s.item));
	}

	/** How far below its place the book starts as it opens. */
	private static final int RISE = 10;

	private static Section lastSection = Section.BANNERS;
	private static int lastTab;

	private final Host host;
	private final BannersPage banners = new BannersPage();
	private boolean open;
	private Section section = lastSection;
	private int tab = lastTab;
	private int sheet;
	private int sheets = 1;
	private final Spring openness = new Spring(Spring.SOFT);
	private double shown;
	private @Nullable BookLayout layout;
	private final List<BookWidgets.Ribbon> ribbons = new ArrayList<>();
	/** The page widgets and where each sits once the book is open. */
	private final Map<AbstractWidget, Integer> pages = new LinkedHashMap<>();
	private final List<Label> labels = new ArrayList<>();
	private final List<Decoration> decorations = new ArrayList<>();
	/**
	 * The widgets no longer match the book: it shut, and its pages go once it has, or the open sheet
	 * moved. Rebuilt from {@link #frame}, outside the screen's init().
	 */
	private boolean stale;

	public MapBook(Host host) {
		this.host = host;
	}

	public boolean isOpen() {
		return this.open;
	}

	public Section section() {
		return this.section;
	}

	public int tab() {
		return this.tab;
	}

	public int sheet() {
		return this.sheet;
	}

	public int sheets() {
		return this.sheets;
	}

	Host host() {
		return this.host;
	}

	BannersPage banners() {
		return this.banners;
	}

	/** Lays out the ribbons and, when open, the open section; call from the screen's init(). */
	public void build(int width, int height) {
		BookLayout layout = BookLayout.of(width, height, Section.values().length);
		this.layout = layout;
		this.ribbons.clear();
		this.pages.clear();
		this.labels.clear();
		this.decorations.clear();
		this.stale = false;
		for (Section s : Section.values()) {
			BookWidgets.Ribbon ribbon = new BookWidgets.Ribbon(layout.ribbons().get(s.ordinal()).y(), s.tint, icon(s),
				s == Section.MINIMAP, Component.translatable(s.key), () -> this.ribbon(s));
			this.ribbons.add(this.host.add(ribbon));
		}
		if (this.open) {
			this.page(new BookWidgets.Mark(layout.close().x(), layout.close().y(), BookSprites.CLOSE, BookSprites.CLOSE_HOVER,
				Component.translatable("diorama.book.close"), this::close));
			if (this.section == Section.BANNERS) {
				BookWidgets.Art[] icons = {BANNER_ICON, BookWidgets.item(BookSprites.stack("loom"))};
				String[] names = {"diorama.book.list", "diorama.book.options"};
				for (int i = 0; i < names.length; i++) {
					int index = i;
					this.page(new BookWidgets.Tab(layout.left().x() + 4 + i * (BookLayout.TAB_W + 2), layout.tabY(), icons[i],
						Component.translatable(names[i]), () -> this.tab == index, () -> this.showTab(index)));
				}
			}
			PageFlow flow = new PageFlow(this, layout, this.sheet);
			switch (this.section) {
				case BANNERS -> {
					if (this.tab == 0) {
						this.banners.build(this, flow);
					} else {
						BookPages.bannerOptions(flow);
					}
				}
				case LOOK -> BookPages.look(flow);
				case LAYERS -> BookPages.layers(this, flow);
				case RADAR -> BookPages.radar(flow);
				default -> {
				}
			}
			this.sheets = flow.sheets();
			if (this.sheet >= this.sheets) {
				// The section got shorter (a smaller window): turn back next frame, not inside init().
				this.sheet = this.sheets - 1;
				this.stale = true;
			}
			this.arrows(layout);
		}
		this.place();
	}

	/** Page-turning arrows at the bottom corners when the section runs over more than one spread. */
	private void arrows(BookLayout layout) {
		if (this.sheets <= 1) {
			return;
		}
		int y = layout.left().bottom() - 13;
		if (this.sheet > 0) {
			this.page(new BookWidgets.Mark(layout.left().x() + 6, y, BookSprites.ARROW_LEFT, BookSprites.ARROW_LEFT_HOVER,
				Component.translatable("diorama.book.previous"), () -> this.turn(-1)));
		}
		if (this.sheet < this.sheets - 1) {
			this.page(new BookWidgets.Mark(layout.right().right() - 13, y, BookSprites.ARROW_RIGHT, BookSprites.ARROW_RIGHT_HOVER,
				Component.translatable("diorama.book.next"), () -> this.turn(1)));
		}
		BookLayout.Rect page = layout.twoPages() ? new BookLayout.Rect(layout.left().x(), y, layout.right().right() - layout.left().x(), 9) : layout.left();
		int sheet = this.sheet + 1, of = this.sheets;
		this.label(new Label(() -> Component.translatable("diorama.book.page", sheet, of), page.x(), y + 1, page.w(), BookSprites.INK_SOFT, true));
	}

	<W extends AbstractWidget> W page(W widget) {
		this.pages.put(widget, widget.getY());
		return this.host.add(widget);
	}

	void label(Label label) {
		this.labels.add(label);
	}

	void decorate(Decoration decoration) {
		this.decorations.add(decoration);
	}

	/** Saves a setting the book changed; the map picks it up next frame. */
	void changed() {
		Settings.get().save();
	}

	private void ribbon(Section s) {
		if (s == Section.MINIMAP) {
			this.banners.commit();
			net.minecraft.client.Minecraft.getInstance().gui.setScreen(new SettingsScreen(this.host.screen(), SettingsCatalog.Section.MINIMAP));
			return;
		}
		if (this.open && this.section == s) {
			this.close();
			return;
		}
		this.show(s, s == this.section ? this.tab : 0);
	}

	/** Opens the book at a section (and tab), or turns to it if it is open. */
	public void show(Section s, int tab) {
		if (s != this.section || tab != this.tab) {
			this.banners.commit();
			this.sheet = 0;
		}
		this.section = s;
		this.tab = tab;
		lastSection = s;
		lastTab = tab;
		this.open = true;
		this.host.rebuild();
	}

	private void showTab(int tab) {
		if (tab != this.tab) {
			this.show(this.section, tab);
		}
	}

	private void turn(int d) {
		this.sheet = Math.max(0, Math.min(this.sheets - 1, this.sheet + d));
		this.host.rebuild();
	}

	public void close() {
		if (!this.open) {
			return;
		}
		this.banners.commit();
		this.open = false;
		// The pages fade with their widgets; they go once the book has shut.
		this.stale = true;
		this.place();
	}

	/** The banners section with this waypoint picked, as a click on its banner on the map asks. */
	public void showWaypoint(Waypoint waypoint) {
		this.banners.select(waypoint);
		this.show(Section.BANNERS, 0);
	}

	/** The banners section with a new waypoint to name, as a right-click on the map asks. */
	public void newWaypoint(Waypoint waypoint) {
		this.banners.draft(waypoint);
		this.show(Section.BANNERS, 0);
	}

	/** Saves edits not yet written; the map screen calls this as it is left. */
	public void removed() {
		this.banners.commit();
	}

	/** Whether a point is on the open book, so the map under it takes no clicks or scrolls. */
	public boolean covers(double x, double y) {
		BookLayout layout = this.layout;
		if (layout == null || this.shown < 0.05 && !this.open) {
			return false;
		}
		BookLayout.Rect c = layout.cover();
		boolean tabs = this.section == Section.BANNERS && y >= layout.tabY() && y < c.y() && x >= c.x() && x < c.right();
		return c.contains(x, y) || tabs;
	}

	/** Whether a point is on a ribbon, open or shut. */
	public boolean onRibbon(double x, double y) {
		for (BookWidgets.Ribbon ribbon : this.ribbons) {
			if (ribbon.isMouseOver(x, y)) {
				return true;
			}
		}
		return false;
	}

	/** Moves the book along its opening; call once a frame before drawing. */
	public void frame(long nanos) {
		this.shown = this.openness.update(this.open ? 1 : 0, nanos);
		if (this.stale && (this.open || this.shown <= 0.001)) {
			this.stale = false;
			this.host.rebuild();
			return;
		}
		this.banners.frame(this);
		this.place();
	}

	/** Puts the ribbons and page widgets where the book's opening has them now. */
	private void place() {
		BookLayout layout = this.layout;
		if (layout == null) {
			return;
		}
		double t = this.shown;
		for (BookWidgets.Ribbon ribbon : this.ribbons) {
			Section s = Section.values()[this.ribbons.indexOf(ribbon)];
			int reach = BookLayout.RIBBON_OUT + (this.open && s == this.section ? BookLayout.RIBBON_SELECTED : 0);
			int edge = (int) Math.round(layout.ribbonShutX() + (layout.ribbonOpenX() - layout.ribbonShutX()) * t);
			ribbon.place(edge, (int) Math.round(BookLayout.RIBBON_SHUT + (reach - BookLayout.RIBBON_SHUT) * t));
		}
		int dy = this.dy();
		for (Map.Entry<AbstractWidget, Integer> e : this.pages.entrySet()) {
			e.getKey().setY(e.getValue() + dy);
			e.getKey().setAlpha((float) Math.max(0, Math.min(1, t)));
		}
	}

	private int dy() {
		return (int) Math.round(RISE * (1 - Math.max(0, Math.min(1, this.shown))));
	}

	/** The cover, pages and their text, under the widgets; call after the map. */
	public void drawBehind(GuiGraphicsExtractor graphics) {
		BookLayout layout = this.layout;
		if (layout == null || this.shown <= 0.01) {
			return;
		}
		float alpha = (float) Math.max(0, Math.min(1, this.shown));
		int dy = this.dy();
		int tint = BookWidgets.fade(-1, alpha);
		BookLayout.Rect c = layout.cover();
		BookSprites.nine(graphics, BookSprites.COVER, c.x(), c.y() + dy, c.w(), c.h(), tint, false);
		BookLayout.Rect l = layout.left();
		BookSprites.nine(graphics, BookSprites.PAGE, l.x(), l.y() + dy, l.w(), l.h(), tint, true);
		if (layout.twoPages()) {
			BookLayout.Rect r = layout.right();
			BookSprites.nine(graphics, BookSprites.PAGE, r.x(), r.y() + dy, r.w(), r.h(), tint, true);
			BookLayout.Rect s = layout.spine();
			BookSprites.down(graphics, BookSprites.SPINE, s.x(), s.y() + dy, s.h(), tint);
		}
		for (Label label : this.labels) {
			Component text = label.text().get();
			int x = label.centered() ? label.x() + (label.width() - BookWidgets.font().width(text)) / 2 : label.x();
			graphics.text(BookWidgets.font(), text, x, label.y() + dy, BookWidgets.fade(label.color(), alpha), false);
		}
		for (Decoration decoration : this.decorations) {
			decoration.draw(graphics, dy, alpha);
		}
	}

	/** For tests: the waypoint the banners section shows, and whether it is a new one not saved yet. */
	public @Nullable Waypoint picked() {
		return this.banners.picked();
	}

	public boolean drafting() {
		return this.banners.drafting();
	}

	/** For tests: the ribbon of a section. */
	public AbstractWidget ribbonOf(Section s) {
		return this.ribbons.get(s.ordinal());
	}

	/** For tests: the widgets on the open pages, as they are now. */
	public List<AbstractWidget> pageWidgets() {
		return List.copyOf(this.pages.keySet());
	}

	public @Nullable BookLayout layout() {
		return this.layout;
	}

	/** How open the book is drawn, 0 to 1. */
	public double shown() {
		return this.shown;
	}
}
