package dev.diorama.ui;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * What a right-click on the world map offers for the spot under it, on a card of parchment: plant
 * a banner there, copy its coordinates, teleport there. Where the map knows no ground (unexplored
 * land) the height is unknown: the coordinates read "x, ?, z" and teleporting lands on the surface.
 */
public final class MapMenu {
	/** The block a right-click picked; without {@code heightKnown}, {@code y} is only a guess. */
	public record Target(int x, int y, int z, boolean heightKnown) {
		/** The coordinates as a player reads them. */
		public Component label() {
			return this.heightKnown
				? Component.literal(this.x + ", " + this.y + ", " + this.z)
				: Component.translatable("diorama.menu.unknown_height", this.x, this.z);
		}

		/** The coordinates as commands take them; a height not known stays the player's own. */
		public String command() {
			return this.x + " " + (this.heightKnown ? Integer.toString(this.y) : "~") + " " + this.z;
		}
	}

	/** One thing the menu offers. */
	public record Entry(Component label, BookWidgets.Art icon, Runnable action) {
	}

	private static final int ROW = 16, HEADER = 13, PAD = 4;

	private final Target target;
	private final List<Entry> entries;
	private int x;
	private int y;
	private int width;

	public MapMenu(Target target, List<Entry> entries, int x, int y) {
		this.target = target;
		this.entries = entries;
		this.x = x;
		this.y = y;
	}

	public Target target() {
		return this.target;
	}

	/** Adds the menu's rows to the screen, kept inside a screen of {@code screenW} by {@code screenH}. */
	public List<Item> build(MapBook.Host host, int screenW, int screenH) {
		int w = BookWidgets.font().width(this.target.label()) + 2 * PAD;
		for (Entry e : this.entries) {
			w = Math.max(w, BookWidgets.font().width(e.label()) + 22 + PAD);
		}
		this.width = w;
		int h = HEADER + this.entries.size() * ROW + 2;
		// Beside the cursor, flipped to stay on screen.
		int left = this.x + 2 + w > screenW ? this.x - 2 - w : this.x + 2;
		int top = this.y + 2 + h > screenH ? this.y - 2 - h : this.y + 2;
		left = Math.max(1, Math.min(screenW - w - 1, left));
		top = Math.max(1, Math.min(screenH - h - 1, top));
		List<Item> items = new ArrayList<>();
		items.add(host.add(new Item(left, top, w, HEADER, this.target.label(), null, null, true, false)));
		for (int i = 0; i < this.entries.size(); i++) {
			Entry e = this.entries.get(i);
			items.add(host.add(new Item(left, top + HEADER + i * ROW, w, ROW + (i == this.entries.size() - 1 ? 2 : 0), e.label(), e.icon(), e.action(), false, i == this.entries.size() - 1)));
		}
		return items;
	}

	/** A row of the menu, drawing its own strip of the card so the card draws above the book. */
	public static final class Item extends BookWidget {
		private final BookWidgets.@Nullable Art icon;
		private final @Nullable Runnable action;
		private final boolean header;
		private final boolean last;

		Item(int x, int y, int width, int height, Component label, BookWidgets.@Nullable Art icon, @Nullable Runnable action, boolean header, boolean last) {
			super(x, y, width, height, label);
			this.icon = icon;
			this.action = action;
			this.header = header;
			this.last = last;
			this.active = action != null;
		}

		@Override
		protected void draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean lit) {
			int x0 = this.getX(), y0 = this.getY(), x1 = x0 + this.width, y1 = y0 + this.height;
			graphics.fill(x0, y0, x1, y1, BookSprites.PAPER);
			if (lit) {
				graphics.fill(x0 + 1, y0, x1 - 1, y1 - (this.last ? 2 : 0), 0xFFFCE3C0);
			}
			// The card's ink outline: sides on every row, the top on the first and the bottom on the last.
			graphics.fill(x0, y0, x0 + 1, y1, BookSprites.INK);
			graphics.fill(x1 - 1, y0, x1, y1, BookSprites.INK);
			if (this.header) {
				graphics.fill(x0, y0, x1, y0 + 1, BookSprites.INK);
				graphics.fill(x0 + 3, y1 - 1, x1 - 3, y1, 0xFFE8B482);
				graphics.text(BookWidgets.font(), this.getMessage(), x0 + PAD, y0 + 3, BookSprites.INK_SOFT, false);
				return;
			}
			if (this.last) {
				graphics.fill(x0, y1 - 1, x1, y1, BookSprites.INK);
			}
			if (this.icon != null) {
				this.icon.draw(graphics, x0 + 3, y0, 16, 16, 1);
			}
			graphics.text(BookWidgets.font(), this.getMessage(), x0 + 22, y0 + 4, BookSprites.INK, false);
		}

		@Override
		protected void press(double x, double y) {
			if (this.action != null) {
				this.action.run();
			}
		}

		@Override
		protected boolean clickable() {
			return !this.header;
		}
	}
}
