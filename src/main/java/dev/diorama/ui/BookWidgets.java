package dev.diorama.ui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.diorama.SettingsScreen;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/** The book's controls: buttons, switches, value steppers, slots, ribbons, tabs and marks. */
public final class BookWidgets {
	/** Height of a switch or stepper row. */
	public static final int ROW = 13;
	/** Height of a button. */
	public static final int BUTTON = 14;

	private BookWidgets() {
	}

	static Font font() {
		return Minecraft.getInstance().font;
	}

	/** {@code argb} with its alpha scaled by {@code alpha}. */
	static int fade(int argb, float alpha) {
		int a = Math.round((argb >>> 24) * Math.max(0, Math.min(1, alpha)));
		return a << 24 | (argb & 0xFFFFFF);
	}

	/** The text, cut with an ellipsis to fit {@code width}. */
	public static Component fit(Component text, int width) {
		Font font = font();
		if (font.width(text) <= width) {
			return text;
		}
		String ellipsis = CommonComponents.ELLIPSIS.getString();
		return Component.literal(font.plainSubstrByWidth(text.getString(), Math.max(0, width - font.width(ellipsis))) + ellipsis);
	}

	private static long lastRustle;

	/**
	 * A soft rustle of cloth as a banner is pointed at, on the map or in the book: the leather sound
	 * of putting on armour, quiet and at a slightly different pitch each time, at most every 60 ms.
	 */
	public static void rustle() {
		long now = System.currentTimeMillis();
		if (now - lastRustle < 60) {
			return;
		}
		lastRustle = now;
		float pitch = 0.9F + (float) Math.random() * 0.35F;
		Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.ARMOR_EQUIP_LEATHER, pitch));
	}

	/** Plays a page turning, as the book's ribbons and arrows do, and the map as it opens and shuts. */
	public static void pageTurn(SoundManager sounds) {
		sounds.play(SimpleSoundInstance.forUI(SoundEvents.BOOK_PAGE_TURN, 1.0F));
	}

	/** A pressable stamp with a label. */
	public static final class Button extends BookWidget {
		private final Runnable action;

		public Button(int x, int y, int width, Component label, Runnable action) {
			super(x, y, width, BUTTON, label);
			this.action = action;
		}

		@Override
		protected void draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean lit) {
			BookSprites.Region r = !this.active ? BookSprites.BUTTON_OFF : lit ? BookSprites.BUTTON_HOVER : BookSprites.BUTTON;
			BookSprites.nine(graphics, r, this.getX(), this.getY(), this.width, this.height, fade(-1, this.alpha), true);
			Component label = fit(this.getMessage(), this.width - 6);
			int color = this.active ? BookSprites.INK : BookSprites.INK_SOFT;
			graphics.text(font(), label, this.getX() + (this.width - font().width(label)) / 2, this.getY() + 3, fade(color, this.alpha), false);
		}

		@Override
		protected void press(double x, double y) {
			this.action.run();
		}
	}

	/**
	 * A row that steps through values: its name on the left, "‹ value ›" on the right. A click on
	 * the left arrow steps back, anywhere else forward; the arrow keys step too.
	 */
	public static class Stepper extends BookWidget {
		private final Supplier<Component> value;
		private final IntConsumer step;
		private final BooleanSupplier enabled;
		private final @Nullable ItemStack icon;

		public Stepper(int x, int y, int width, Component name, @Nullable ItemStack icon, Supplier<Component> value, IntConsumer step, BooleanSupplier enabled) {
			super(x, y, width, icon != null ? 18 : ROW, name);
			this.value = value;
			this.step = step;
			this.enabled = enabled;
			this.icon = icon;
		}

		/** A setting from the catalog; {@code changed} runs after it is saved. */
		public static Stepper of(int x, int y, int width, SettingsCatalog.Entry entry, @Nullable ItemStack icon, Runnable changed) {
			Stepper stepper = entry.toggle()
				? new Toggle(x, y, width, entry, icon, changed)
				: new Stepper(x, y, width, Component.translatable(entry.key()), icon, () -> SettingsScreen.valueName(entry, entry.value()), d -> {
					entry.choose(entry.value() + d);
					changed.run();
				}, entry.active());
			if (entry.tooltip()) {
				stepper.setTooltip(Tooltip.create(Component.translatable(entry.tooltipKey())));
			}
			return stepper;
		}

		protected int textLeft() {
			return this.getX() + (this.icon != null ? 19 : 1);
		}

		protected int textY() {
			return this.getY() + (this.height - 8) / 2 + 1;
		}

		@Override
		protected void draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean lit) {
			this.active = this.enabled.getAsBoolean();
			if (lit) {
				graphics.fill(this.getX() - 2, this.getY(), this.getX() + this.width + 2, this.getY() + this.height, fade(0x40FFF4DC, this.alpha));
			}
			if (this.icon != null) {
				BookSprites.item(graphics, this.icon, this.getX() + 1, this.getY() + 1);
			}
			int color = fade(this.active ? BookSprites.INK : BookSprites.INK_SOFT, this.alpha);
			this.drawValue(graphics, lit, color);
		}

		protected void drawValue(GuiGraphicsExtractor graphics, boolean lit, int color) {
			Component value = fit(this.value.get(), this.width / 2);
			int right = this.getX() + this.width;
			int valueW = font().width(value);
			int arrowY = this.getY() + (this.height - 9) / 2;
			BookSprites.sprite(graphics, lit ? BookSprites.ARROW_RIGHT_HOVER : BookSprites.ARROW_RIGHT, right - 7, arrowY, fade(-1, this.alpha));
			graphics.text(font(), value, right - 9 - valueW, this.textY(), color, false);
			int leftArrow = right - 11 - valueW - 7;
			BookSprites.sprite(graphics, lit ? BookSprites.ARROW_LEFT_HOVER : BookSprites.ARROW_LEFT, leftArrow, arrowY, fade(-1, this.alpha));
			Component name = fit(this.getMessage(), leftArrow - 3 - this.textLeft());
			graphics.text(font(), name, this.textLeft(), this.textY(), color, false);
		}

		@Override
		protected void press(double x, double y) {
			Component value = fit(this.value.get(), this.width / 2);
			int leftArrow = this.getX() + this.width - 11 - font().width(value) - 7;
			this.step.accept(!Double.isNaN(x) && x < leftArrow + 8 ? -1 : 1);
		}

		@Override
		protected boolean arrow(int key) {
			if (key == InputConstants.KEY_LEFT || key == InputConstants.KEY_RIGHT) {
				this.playDownSound(Minecraft.getInstance().getSoundManager());
				this.step.accept(key == InputConstants.KEY_LEFT ? -1 : 1);
				return true;
			}
			return false;
		}
	}

	/** An on/off switch: its name on the left, an inked box on the right. */
	public static final class Toggle extends Stepper {
		private final SettingsCatalog.Entry entry;

		Toggle(int x, int y, int width, SettingsCatalog.Entry entry, @Nullable ItemStack icon, Runnable changed) {
			super(x, y, width, Component.translatable(entry.key()), icon, () -> SettingsScreen.valueName(entry, entry.value()), d -> {
				entry.choose(entry.value() + 1);
				changed.run();
			}, entry.active());
			this.entry = entry;
		}

		@Override
		protected void drawValue(GuiGraphicsExtractor graphics, boolean lit, int color) {
			boolean on = this.entry.value() == 1;
			BookSprites.Region box = this.active
				? on ? BookSprites.TOGGLE_ON : BookSprites.TOGGLE_OFF
				: on ? BookSprites.TOGGLE_ON_DISABLED : BookSprites.TOGGLE_OFF_DISABLED;
			int right = this.getX() + this.width;
			BookSprites.sprite(graphics, box, right - 9, this.getY() + (this.height - 9) / 2, fade(-1, this.alpha));
			Component name = fit(this.getMessage(), right - 12 - this.textLeft());
			graphics.text(font(), name, this.textLeft(), this.textY(), color, false);
		}

		@Override
		protected void press(double x, double y) {
			super.press(Double.NaN, Double.NaN);
		}

		@Override
		protected boolean arrow(int key) {
			return false;
		}

		@Override
		protected net.minecraft.network.chat.MutableComponent createNarrationMessage() {
			return Component.empty().append(this.getMessage()).append(": ").append(SettingsScreen.valueName(this.entry, this.entry.value()));
		}
	}

	/** What a slot shows inside its frame. */
	@FunctionalInterface
	public interface Art {
		void draw(GuiGraphicsExtractor graphics, int x, int y, int width, int height, float alpha);
	}

	/**
	 * A framed slot with a picture, picked by a click: a style, a direction, a waypoint. A banner's
	 * slot rustles when pointed at and turns a page when picked.
	 */
	public static final class Slot extends BookWidget {
		private final Art art;
		private final BooleanSupplier selected;
		private final Runnable action;
		private final boolean banner;
		private boolean wasHovered;

		public Slot(int x, int y, int width, int height, Component name, Art art, BooleanSupplier selected, Runnable action, boolean banner) {
			super(x, y, width, height, name);
			this.art = art;
			this.selected = selected;
			this.action = action;
			this.banner = banner;
		}

		@Override
		protected void draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean lit) {
			if (this.banner && this.isHovered && !this.wasHovered) {
				rustle();
			}
			this.wasHovered = this.isHovered;
			BookSprites.Region r = this.selected.getAsBoolean() ? BookSprites.SLOT_SELECTED : lit ? BookSprites.SLOT_HOVER : BookSprites.SLOT;
			BookSprites.nine(graphics, r, this.getX(), this.getY(), this.width, this.height, fade(-1, this.alpha), true);
			this.art.draw(graphics, this.getX(), this.getY(), this.width, this.height, this.alpha);
		}

		@Override
		protected void press(double x, double y) {
			this.action.run();
		}

		@Override
		public void playDownSound(SoundManager sounds) {
			if (this.banner) {
				pageTurn(sounds);
			} else {
				super.playDownSound(sounds);
			}
		}
	}

	/** An item's icon as an {@link Art}, 16 pixels across, centred. */
	public static Art item(ItemStack stack) {
		return (graphics, x, y, width, height, alpha) -> BookSprites.item(graphics, stack, x + (width - 16) / 2, y + (height - 16) / 2);
	}

	/**
	 * A bookmark ribbon with an icon on it, reaching out to the right of {@link #edge}: {@link #reach}
	 * pixels of it show, its right end, as if the rest were under the book. Shut, the whole ribbon
	 * shows, notched at both ends. The book moves both as it opens and shuts.
	 */
	public static final class Ribbon extends BookWidget {
		private final int tint;
		private final Art icon;
		private final boolean leaves;
		private final Runnable action;
		int edge;
		int reach;

		public Ribbon(int y, int tint, Art icon, boolean leaves, Component name, Runnable action) {
			super(0, y, BookLayout.RIBBON_OUT, BookLayout.RIBBON_H, name);
			this.tint = tint;
			this.icon = icon;
			this.leaves = leaves;
			this.action = action;
			this.setTooltip(Tooltip.create(name));
		}

		/** Places it reaching {@code reach} pixels right of {@code edge}. */
		public void place(int edge, int reach) {
			this.edge = edge;
			this.reach = Math.max(1, Math.min(BookSprites.RIBBON.w(), reach));
			this.setX(edge);
			this.width = this.reach;
		}

		@Override
		protected void draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean lit) {
			int full = BookSprites.RIBBON.w();
			// Pointed at, it reaches out a little; a whole ribbon slides out instead.
			int wanted = this.reach + (lit ? 3 : 0);
			int show = Math.min(full, wanted);
			int x = this.edge - Math.max(0, wanted - full);
			BookSprites.part(graphics, BookSprites.RIBBON, x, this.getY(), full - show, 0, show, BookSprites.RIBBON.h(), this.tint);
			// Between the notches when it shows whole; short of the right notch over the book.
			int iconX = show >= full ? x + (full - 16) / 2 : x + show - 22;
			this.icon.draw(graphics, iconX, this.getY() + 3, 16, 16, 1);
			if (this.leaves) {
				BookSprites.sprite(graphics, BookSprites.LEAVE, iconX + 13, this.getY() + 2, -1);
			}
		}

		@Override
		protected void press(double x, double y) {
			this.action.run();
		}

		@Override
		public void playDownSound(SoundManager sounds) {
			pageTurn(sounds);
		}
	}

	/** A tab on the book's top edge with an icon on it. */
	public static final class Tab extends BookWidget {
		private final Art icon;
		private final BooleanSupplier selected;
		private final Runnable action;

		public Tab(int x, int y, Art icon, Component name, BooleanSupplier selected, Runnable action) {
			super(x, y, BookLayout.TAB_W, BookLayout.TAB_RISE, name);
			this.icon = icon;
			this.selected = selected;
			this.action = action;
			this.setTooltip(Tooltip.create(name));
		}

		@Override
		protected void draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean lit) {
			boolean on = this.selected.getAsBoolean();
			int lift = on ? 2 : lit ? 1 : 0;
			BookSprites.part(graphics, on ? BookSprites.TAB_SELECTED : BookSprites.TAB, this.getX(), this.getY() - lift, 0, 0,
				BookLayout.TAB_W, this.height + lift, fade(-1, this.alpha));
			this.icon.draw(graphics, this.getX() + 4, this.getY() + 2 - lift, 16, 16, this.alpha);
		}

		@Override
		protected void press(double x, double y) {
			this.action.run();
		}

		@Override
		public void playDownSound(SoundManager sounds) {
			pageTurn(sounds);
		}
	}

	/** A small sprite that does something: the close mark, page arrows, the help seal. */
	public static final class Mark extends BookWidget {
		private final BookSprites.Region normal;
		private final BookSprites.Region hover;
		private final @Nullable Runnable action;

		public Mark(int x, int y, BookSprites.Region normal, BookSprites.Region hover, Component name, @Nullable Runnable action) {
			super(x, y, normal.w(), normal.h(), name);
			this.normal = normal;
			this.hover = hover;
			this.action = action;
		}

		@Override
		protected void draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean lit) {
			BookSprites.sprite(graphics, lit ? this.hover : this.normal, this.getX(), this.getY(), fade(-1, this.alpha));
		}

		@Override
		protected void press(double x, double y) {
			if (this.action != null) {
				this.action.run();
			}
		}
	}
}
