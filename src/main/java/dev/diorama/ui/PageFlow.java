package dev.diorama.ui;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Lays a section's contents down the book's pages, top to bottom: what doesn't fit on a page goes
 * on the next, the left page before the right, and pages past the open spread make more spreads
 * (sheets) the arrows turn to. With one page showing, every page is a sheet of its own. Only what
 * lands on the open sheet is added to the screen.
 */
public final class PageFlow {
	private final MapBook book;
	private final BookLayout layout;
	private final int sheet;
	private int page;
	private int y;

	PageFlow(MapBook book, BookLayout layout, int sheet) {
		this.book = book;
		this.layout = layout;
		this.sheet = sheet;
		this.y = this.content().y();
	}

	public BookLayout.Rect content() {
		return BookLayout.content(this.layout.twoPages() && this.page % 2 == 1 ? this.layout.right() : this.layout.left());
	}

	public int x() {
		return this.content().x();
	}

	public int width() {
		return this.content().w();
	}

	/** Whether what is laid now lands on the open sheet. */
	public boolean here() {
		return (this.layout.twoPages() ? this.page / 2 : this.page) == this.sheet;
	}

	public int sheets() {
		return (this.layout.twoPages() ? this.page / 2 : this.page) + 1;
	}

	/** Room for {@code h} pixels: its top, on this page or the next if it doesn't fit. */
	public int take(int h) {
		BookLayout.Rect c = this.content();
		if (this.y + h > c.bottom() && this.y > c.y()) {
			this.next();
		}
		int at = this.y;
		this.y += h;
		return at;
	}

	public void gap(int h) {
		this.y += h;
	}

	/** Goes on to the next page, unless nothing is on this one yet. */
	public void breakPage() {
		if (this.y > this.content().y()) {
			this.next();
		}
	}

	private void next() {
		this.page++;
		this.y = this.content().y();
	}

	/** Adds a widget laid at the current place, if it is on the open sheet. */
	public <W extends AbstractWidget> @Nullable W add(W widget) {
		return this.here() ? this.book.page(widget) : null;
	}

	/** Text on the page, in ink. */
	public void text(Component text, int x, int y, int color) {
		if (this.here()) {
			this.book.label(new MapBook.Label(() -> text, x, y, 0, color, false));
		}
	}

	/** Text that changes, centred on a band {@code width} wide. */
	public void centered(Supplier<Component> text, int x, int y, int width, int color) {
		if (this.here()) {
			this.book.label(new MapBook.Label(text, x, y, width, color, true));
		}
	}

	public void decorate(MapBook.Decoration decoration) {
		if (this.here()) {
			this.book.decorate(decoration);
		}
	}

	/** The section's name across the page's top, over a flourish. */
	public void title(Component text) {
		int at = this.take(17);
		int x = this.x(), w = this.width();
		this.centered(() -> text, x, at, w, BookSprites.INK);
		this.decorate((graphics, dy, alpha) -> {
			int line = at + 11 + dy;
			BookSprites.across(graphics, BookSprites.SEPARATOR, x, line, w, BookWidgets.fade(-1, alpha));
			BookSprites.sprite(graphics, BookSprites.FLOURISH, x + (w - BookSprites.FLOURISH.w()) / 2, line - 1, BookWidgets.fade(-1, alpha));
		});
	}

	/** A small heading over a group of controls. */
	public void heading(Component text) {
		int at = this.take(11);
		this.text(BookWidgets.fit(text, this.width()), this.x(), at + 1, BookSprites.INK_SOFT);
	}

	/** Text wrapped to the page, line by line. */
	public void paragraph(Component text) {
		List<FormattedCharSequence> lines = BookWidgets.font().split(text, this.width());
		for (FormattedCharSequence line : lines) {
			int at = this.take(10);
			int x = this.x();
			if (this.here()) {
				this.book.decorate((graphics, dy, alpha) ->
					graphics.text(BookWidgets.font(), line, x, at + dy, BookWidgets.fade(BookSprites.INK, alpha), false));
			}
		}
		this.gap(2);
	}

	/** A setting from the catalog, as a switch or a stepper. */
	public void setting(SettingsCatalog.Entry entry, @Nullable ItemStack icon) {
		if (!entry.shown().getAsBoolean()) {
			return;
		}
		int at = this.take(icon != null ? 19 : BookWidgets.ROW + 1);
		this.add(BookWidgets.Stepper.of(this.x(), at, this.width(), entry, icon, this.book::changed));
	}

	/** A stepper that isn't a setting: the cave slice. */
	public void stepper(Component name, Supplier<Component> value, IntConsumer step, BooleanSupplier enabled) {
		int at = this.take(BookWidgets.ROW + 1);
		this.add(new BookWidgets.Stepper(this.x(), at, this.width(), name, null, value, step, enabled));
	}

	/** One choice of a row of slots. */
	public record Choice(Component name, BookWidgets.Art art, BooleanSupplier selected, Runnable pick) {
	}

	/** Choices as a row of slots, wrapping onto more rows if the page is narrow. */
	public void slots(List<Choice> choices, int slotW, int slotH) {
		int gap = 3;
		int perRow = Math.max(1, Math.min(choices.size(), (this.width() + gap) / (slotW + gap)));
		for (int i = 0; i < choices.size(); i += perRow) {
			int n = Math.min(perRow, choices.size() - i);
			int at = this.take(slotH + gap);
			int left = this.x() + (this.width() - (n * slotW + (n - 1) * gap)) / 2;
			for (int j = 0; j < n; j++) {
				Choice c = choices.get(i + j);
				BookWidgets.Slot slot = new BookWidgets.Slot(left + j * (slotW + gap), at, slotW, slotH, c.name(), c.art(), c.selected(), () -> {
					c.pick().run();
					this.book.changed();
				}, false);
				slot.setTooltip(net.minecraft.client.gui.components.Tooltip.create(c.name()));
				this.add(slot);
			}
		}
	}

	/** Buttons two to a row. */
	public void buttons(List<ButtonSpec> buttons) {
		int gap = 4;
		int w = (this.width() - gap) / 2;
		for (int i = 0; i < buttons.size(); i += 2) {
			int at = this.take(BookWidgets.BUTTON + 3);
			for (int j = 0; j < 2 && i + j < buttons.size(); j++) {
				ButtonSpec spec = buttons.get(i + j);
				boolean alone = i + 1 >= buttons.size();
				BookWidgets.Button button = new BookWidgets.Button(this.x() + j * (w + gap), at, alone ? this.width() : w, spec.label(), spec.action());
				button.active = spec.active();
				this.add(button);
			}
		}
	}

	public record ButtonSpec(Component label, Runnable action, boolean active) {
		public ButtonSpec(Component label, Runnable action) {
			this(label, action, true);
		}
	}
}
